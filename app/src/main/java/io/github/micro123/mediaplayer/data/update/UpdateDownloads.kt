package io.github.micro123.mediaplayer.data.update

import android.app.DownloadManager
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

data class UpdateDownloadTask(val id: Long, val apk: ReleaseApk, val tag: String, val fileName: String)
sealed interface UpdateDownloadState {
    data object Empty : UpdateDownloadState
    data class Transferring(val task: UpdateDownloadTask, val received: Long = 0, val total: Long = 0, val message: String = "准备下载…") : UpdateDownloadState
    data class Verifying(val task: UpdateDownloadTask) : UpdateDownloadState
    data class Ready(val task: UpdateDownloadTask, val uri: Uri, val canInstall: Boolean, val message: String) : UpdateDownloadState
    data class Failed(val task: UpdateDownloadTask, val message: String) : UpdateDownloadState
}

/** DownloadManager owns transfer/resume/notifications. This repository only tracks our one update. */
@SuppressLint("ApplySharedPref", "UseKtx") // All checked durable writes run on Dispatchers.IO.
class UpdateDownloads(context: Context, preferencesName: String = "update_download") {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val preferences = app.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private var watcher: Job? = null
    private var task: UpdateDownloadTask? = null
    private val mutableState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Empty)
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                task = runCatching {
                    val json = JSONObject(preferences.getString("task", null) ?: return@runCatching null)
                    UpdateDownloadTask(json.getLong("id"), ReleaseApk(json.getString("name"), json.getString("url"), json.getLong("size"),
                        json.optString("sha256").takeIf { it.isNotBlank() }), json.getString("tag"), json.getString("file"))
                        .also { require(it.fileName.matches(Regex("update-[a-f0-9-]+\\.apk"))); validateApk(it.apk); require(ReleaseVersion.parse(it.tag) != null) }
                }.getOrNull()
                task?.let(::observe)
            } finally { initialized.complete(Unit) }
        }
    }

    fun start(apk: ReleaseApk, tag: String) {
        scope.launch {
            initialized.await()
            mutex.withLock {
                if (state.value is UpdateDownloadState.Transferring || state.value is UpdateDownloadState.Verifying) return@withLock
                val pending = UpdateDownloadTask(-1, apk, tag, "update-${UUID.randomUUID()}.apk")
                try {
                    validateApk(apk); require(ReleaseVersion.parse(tag) != null) { "更新版本号无效" }
                    val storage = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("下载存储不可用")
                    val directory = File(storage, "updates")
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建更新下载目录" }
                    watcher?.cancelAndJoin()
                    task?.let { manager.remove(it.id) }
                    val request = DownloadManager.Request(apk.url.toUri())
                        .setTitle("媒体播放器更新 · $tag").setDescription(apk.name)
                        .setMimeType(APK_MIME).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/${pending.fileName}")
                        .addRequestHeader("User-Agent", "MediaPlayer-UpdateDownload")
                    val current = pending.copy(id = manager.enqueue(request))
                    check(current.id > 0) { "无法创建下载任务" }
                    task = current
                    persist(current)
                    observe(current)
                } catch (error: Exception) {
                    mutableState.value = UpdateDownloadState.Failed(pending, error.message ?: "无法开始下载，请检查系统下载服务和存储空间")
                }
            }
        }
    }

    fun cancel() {
        scope.launch {
            initialized.await()
            mutex.withLock {
                watcher?.cancelAndJoin()
                task?.let { manager.remove(it.id) }
                task = null
                check(preferences.edit().remove("task").commit()) { "无法清除下载记录" }
                mutableState.value = UpdateDownloadState.Empty
            }
        }
    }

    fun retry() { (state.value as? UpdateDownloadState.Failed)?.task?.let { start(it.apk, it.tag) } }
    fun close() { scope.cancel() } // The system transfer is deliberately not removed.

    suspend fun installationIntent(): Intent = withContext(Dispatchers.IO) {
        val ready = state.value as? UpdateDownloadState.Ready ?: error("安装包尚未下载完成")
        val refreshed = verify(ready.task)
        check(refreshed.canInstall) { refreshed.message }
        installIntent(refreshed.uri)
    }

    private fun observe(current: UpdateDownloadTask) {
        watcher?.cancel()
        mutableState.value = UpdateDownloadState.Transferring(current, total = current.apk.sizeBytes)
        watcher = scope.launch {
            try {
                while (isActive) {
                    val done = manager.query(DownloadManager.Query().setFilterById(current.id)).use { cursor ->
                        check(cursor != null && cursor.moveToFirst()) { "下载任务已被移除，请重新下载" }
                        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                        val status = number(DownloadManager.COLUMN_STATUS).toInt()
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> true
                            DownloadManager.STATUS_FAILED -> error(downloadFailure(number(DownloadManager.COLUMN_REASON).toInt()))
                            else -> {
                                val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 } ?: current.apk.sizeBytes
                                mutableState.value = UpdateDownloadState.Transferring(current, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0), total,
                                    if (status == DownloadManager.STATUS_PAUSED) "等待网络或系统恢复下载…" else "正在下载…")
                                false
                            }
                        }
                    }
                    if (done) {
                        mutableState.value = UpdateDownloadState.Verifying(current)
                        val ready = verify(current)
                        ensureActive()
                        mutableState.value = ready
                        return@launch
                    }
                    delay(750)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (isActive) mutableState.value = UpdateDownloadState.Failed(current, error.message ?: "下载失败，请重试") }
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(current: UpdateDownloadTask): UpdateDownloadState.Ready {
        val file = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/${current.fileName}")
        check(file.isFile && file.length() > 0) { "下载文件不存在，请重新下载" }
        if (current.apk.sizeBytes > 0) check(file.length() == current.apk.sizeBytes) { "安装包大小不符，请重新下载" }
        current.apk.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expected, true)) { "安装包校验失败，请重新下载" }
        }
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("文件不是有效的 APK，请重新下载")
        check(archive.packageName == app.packageName) { "安装包不属于媒体播放器，不能安装" }
        val version = archive.versionName?.let(ReleaseVersion::parse)
        check(version != null && version.compareTo(requireNotNull(ReleaseVersion.parse(current.tag))) == 0) { "安装包版本与发布版本不符，请重新下载" }
        val installed = pm.getPackageInfo(app.packageName, flags)
        val installedSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val archiveSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else archive.signatures
        val compatible = installedSigners?.isNotEmpty() == true && archiveSigners?.isNotEmpty() == true && installedSigners.all { signer -> archiveSigners.any { it == signer } }
            && (if (Build.VERSION.SDK_INT >= 28 && archive.signingInfo?.hasMultipleSigners() != true && installed.signingInfo?.hasMultipleSigners() != true) true
                else installedSigners.size == archiveSigners.size)
        val newer = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode > installed.longVersionCode else archive.versionCode > installed.versionCode
        val message = when {
            !compatible -> "安装包与当前应用签名不同，无法覆盖安装。下载文件已保留。"
            !newer -> "安装包版本不高于当前应用，不能作为更新安装。下载文件已保留。"
            else -> "安装包已检查，点击安装后由系统请求确认。"
        }
        val uri = manager.getUriForDownloadedFile(current.id) ?: error("无法读取已下载的安装包")
        return UpdateDownloadState.Ready(current, uri, compatible && newer, message)
    }

    private fun persist(current: UpdateDownloadTask) {
        val json = JSONObject().put("id", current.id).put("name", current.apk.name).put("url", current.apk.url).put("size", current.apk.sizeBytes)
            .put("sha256", current.apk.sha256 ?: "").put("tag", current.tag).put("file", current.fileName)
        check(preferences.edit().putString("task", json.toString()).commit()) { "无法保存下载任务" }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        fun installIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("更新安装包", uri) }
        internal fun validateApk(apk: ReleaseApk) {
            val uri = URI(apk.url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
                uri.path.startsWith("/micro123/media-player/releases/download/") && uri.normalize().path.startsWith("/micro123/media-player/releases/download/") &&
                apk.name.startsWith("media-player-") && apk.name.endsWith(".apk", true)) { "更新下载地址无效" }
            require(apk.sha256 == null || apk.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "更新校验信息无效" }
        }
        private fun downloadFailure(reason: Int) = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请释放空间后重试"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用，请重试"
            in 400..599 -> "下载安装包失败（HTTP $reason），请检查网络后重试"
            else -> "下载失败（$reason），请检查网络、存储或系统下载服务后重试"
        }
    }
}
