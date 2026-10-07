package com.tang.player.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.net.toUri
import com.tang.player.core.MediaItem
import com.tang.player.core.MediaSourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

data class ClipRange(val media: MediaItem, val startMs: Long, val endMs: Long)
data class ClipExportResult(val uri: String, val actualStartMs: Long, val endMs: Long)
data class ClipPreview(val actualStartMs: Long, val endMs: Long)

interface ClipExporter {
    suspend fun prepare(range: ClipRange): ClipPreview
    suspend fun export(range: ClipRange, output: Uri): ClipExportResult
}

/** Lossless packet copy. Playback speed never changes the output's original timestamps. */
class AndroidClipExporter(context: Context) : ClipExporter {
    private val context = context.applicationContext
    override suspend fun prepare(range: ClipRange): ClipPreview = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try { val (_, origin) = inspect(range, extractor); ClipPreview(origin / 1000, range.endMs) }
        finally { extractor.release() }
    }

    private fun inspect(range: ClipRange, extractor: MediaExtractor): Pair<Map<Int, MediaFormat>, Long> {
        require(range.startMs >= 0 && range.endMs > range.startMs) { "终点必须晚于起点" }
        require(range.media.sourceKind in setOf(MediaSourceKind.LOCAL, MediaSourceKind.HTTP)) { "此来源暂不支持区间导出" }
        extractor.setDataSource(context, range.media.uri.toUri(), null)
        val formats = (0 until extractor.trackCount).associateWith(extractor::getTrackFormat)
        val video = formats.entries.firstOrNull { it.value.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: error("没有可导出的视频轨道")
        val tracks = formats.filter { (index, format) -> index == video.key || format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        val supported = setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")
        check(tracks.values.all { it.getString(MediaFormat.KEY_MIME) in supported }) {
            "当前无损导出支持 H.264/H.265 等 MP4 兼容轨道；此视频需要后续转码支持"
        }
        extractor.selectTrack(video.key)
        extractor.seekTo(range.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val originUs = extractor.sampleTime.takeIf { it >= 0 } ?: error("无法定位区间起点")
        check(originUs < range.endMs * 1000) { "区间内没有视频帧" }
        extractor.unselectTrack(video.key)
        return tracks to originUs
    }

    override suspend fun export(range: ClipRange, output: Uri): ClipExportResult = withContext(Dispatchers.IO) {
        require(range.startMs >= 0 && range.endMs > range.startMs) { "终点必须晚于起点" }
        require(range.media.sourceKind in setOf(MediaSourceKind.LOCAL, MediaSourceKind.HTTP)) { "此来源暂不支持区间导出" }
        require(output.toString() != range.media.uri) { "不能覆盖原视频" }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var outputFd: android.os.ParcelFileDescriptor? = null
        try {
            val (tracks, originUs) = inspect(range, extractor)
            val video = tracks.entries.first { it.value.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            tracks.keys.forEach(extractor::selectTrack)
            extractor.seekTo(originUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            outputFd = context.contentResolver.openFileDescriptor(output, "rwt") ?: error("无法创建片段文件")
            muxer = MediaMuxer(outputFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (video.value.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(video.value.getInteger(MediaFormat.KEY_ROTATION))
            val mapping = tracks.mapValues { muxer.addTrack(it.value) }
            muxer.start()
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            var videoSamples = 0
            val info = MediaCodec.BufferInfo()
            // B-frame presentation timestamps can be out of order. Finish each track, not the whole file,
            // when its first timestamp passes the boundary; decode order remains intact in the muxer.
            val finished = mutableSetOf<Int>()
            while (true) {
                coroutineContext.ensureActive()
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (time >= range.endMs * 1000) finished += track
                if (finished.size == tracks.size) break
                if (track !in finished && time >= originUs) {
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "不支持导出加密轨道" }
                    val size = if (android.os.Build.VERSION.SDK_INT >= 28) extractor.sampleSize
                        else (tracks[track]?.let { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else null }
                            ?: (4 * 1024 * 1024)).toLong()
                    check(size in 1..(32L * 1024 * 1024)) { "媒体帧过大或损坏" }
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, count, time - originUs, flags)
                    muxer.writeSampleData(requireNotNull(mapping[track]), buffer, info)
                    if (track == video.key) videoSamples++
                }
                if (!extractor.advance()) break
            }
            check(videoSamples > 0) { "区间内没有可导出的视频帧" }
            muxer.stop()
            ClipExportResult(output.toString(), originUs / 1000, range.endMs)
        } catch (error: Throwable) {
            // SAF CreateDocument returns a newly created document; remove partial output, never source.
            if (output.toString() != range.media.uri) runCatching { context.contentResolver.delete(output, null, null) }
            throw error
        } finally {
            runCatching { muxer?.release() }
            outputFd?.close()
            extractor.release()
        }
    }
}
