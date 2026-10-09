package io.github.micro123.mediaplayer.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

class UpdateCheckException(message: String, val useReleasePage: Boolean = false) : IOException(message)

/** Public release metadata only; no token, device identifiers or media data are sent. */
class GithubReleaseClient(private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) {
    suspend fun latest(): GithubRelease = withContext(Dispatchers.IO) {
        try { parseRelease(readResponse()) }
        catch (error: UpdateCheckException) {
            if (!error.useReleasePage) throw error
            try { readReleasePage() } catch (_: IOException) { throw error }
        }
    }

    internal fun readResponse(): String {
        val connection = connect(API_URL)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.setRequestProperty("User-Agent", "MediaPlayer-UpdateCheck")
            when (val code = connection.responseCode) {
                200 -> Unit
                404 -> throw UpdateCheckException("尚未找到正式发布版本，可打开发布页面查看")
                429 -> throw UpdateCheckException("GitHub 请求次数已达上限，请稍后重试或打开发布页面", useReleasePage = true)
                403 -> throw UpdateCheckException(if (connection.getHeaderField("X-RateLimit-Remaining") == "0" || connection.getHeaderField("Retry-After") != null)
                    "GitHub 请求次数已达上限，请稍后重试或打开发布页面" else "GitHub 拒绝了更新请求，请稍后重试或打开发布页面", useReleasePage = true)
                else -> throw UpdateCheckException("检查更新失败（HTTP $code），请稍后重试或打开发布页面")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (output.size() <= MAX_BYTES) {
                    val count = input.read(bytes, 0, minOf(bytes.size, MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(bytes, 0, count)
                }
                if (output.size() > MAX_BYTES) throw UpdateCheckException("更新信息过大，请打开发布页面查看")
                output.toString(Charsets.UTF_8.name())
            }
        } catch (_: SocketTimeoutException) {
            throw UpdateCheckException("连接 GitHub 超时，请检查网络后重试")
        } finally { connection.disconnect() }
    }

    /** The documented latest-release link resolves to a versioned page without consuming API quota. */
    private fun readReleasePage(): GithubRelease {
        val connection = connect(RELEASES_URL)
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "MediaPlayer-UpdateCheck")
            if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) throw UpdateCheckException("无法读取最新发布页面")
            val location = connection.getHeaderField("Location") ?: throw UpdateCheckException("最新发布页面未提供版本")
            val page = runCatching { URI(RELEASES_URL).resolve(location).toString() }.getOrElse { throw UpdateCheckException("发布页面地址无效") }
            val prefix = "/micro123/media-player/releases/tag/"
            if (!trustedUrl(page, prefix)) throw UpdateCheckException("发布页面地址无效")
            val tag = URI(page).path.removePrefix(prefix)
            if (ReleaseVersion.parse(tag) == null) throw UpdateCheckException("无法识别发布版本号")
            val apks = try { readPageAssets(tag) } catch (_: IOException) { emptyList() }
            return GithubRelease(tag, "媒体播放器 $tag", "已获取发布版本。完整更新说明可在发布页面查看。", "", page, apks, metadataComplete = false)
        } finally { connection.disconnect() }
    }

    private fun readPageAssets(tag: String): List<ReleaseApk> {
        val connection = connect("$REPOSITORY_URL/releases/expanded_assets/$tag")
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "MediaPlayer-UpdateCheck")
            if (connection.responseCode != 200) throw UpdateCheckException("暂时无法读取发布附件")
            val html = connection.inputStream.use { input ->
                val bytes = input.readBytesBounded(MAX_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
            return pageApks(html, tag)
        } finally { connection.disconnect() }
    }

    companion object {
        const val REPOSITORY_URL = "https://github.com/micro123/media-player"
        const val RELEASES_URL = "$REPOSITORY_URL/releases/latest"
        const val API_URL = "https://api.github.com/repos/micro123/media-player/releases/latest"
        internal const val MAX_BYTES = 1024 * 1024
        private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= limit) {
                val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            if (output.size() > limit) throw UpdateCheckException("发布附件信息过大")
            return output.toByteArray()
        }

        internal fun pageApks(html: String, tag: String): List<ReleaseApk> {
            return Regex("<li\\b[\\s\\S]*?</li>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { row ->
                val hrefs = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(row.value)
                val url = hrefs.mapNotNull { match -> runCatching { URI(REPOSITORY_URL).resolve(match.groupValues[1].replace("&amp;", "&")).toString() }.getOrNull() }
                    .firstOrNull { trustedUrl(it, "/micro123/media-player/releases/download/$tag/") && it.endsWith(".apk", true) } ?: return@mapNotNull null
                val name = URI(url).path.substringAfterLast('/')
                if (!name.startsWith("media-player-")) return@mapNotNull null
                val digest = Regex("sha256:([0-9a-fA-F]{64})").find(row.value)?.groupValues?.get(1)?.lowercase()
                ReleaseApk(name, url, 0, digest)
            }.distinctBy { it.url }.toList()
        }

        internal fun parseRelease(text: String): GithubRelease {
            try {
                val json = JSONObject(text)
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw UpdateCheckException("尚未找到正式发布版本，可打开发布页面查看")
                val tag = json.getString("tag_name")
                if (ReleaseVersion.parse(tag) == null) throw UpdateCheckException("无法识别发布版本号，请打开发布页面查看")
                val page = json.getString("html_url")
                if (!trustedUrl(page, "/micro123/media-player/releases/tag/")) throw UpdateCheckException("发布页面地址无效，请打开仓库发布页面查看")
                val assets = json.optJSONArray("assets")
                val apks = (0 until (assets?.length() ?: 0)).mapNotNull { index ->
                    val asset = assets!!.optJSONObject(index) ?: return@mapNotNull null
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (!name.startsWith("media-player-") || !name.endsWith(".apk", true) ||
                        asset.optString("state", "uploaded") != "uploaded" || !trustedUrl(url, "/micro123/media-player/releases/download/")) null
                    else ReleaseApk(name, url, asset.optLong("size").coerceAtLeast(0),
                        asset.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase())
                }
                return GithubRelease(tag, json.optString("name").ifBlank { tag }, (if (json.isNull("body")) "" else json.optString("body")).take(24_000),
                    json.optString("published_at"), page, apks)
            } catch (error: UpdateCheckException) { throw error }
            catch (_: Exception) { throw UpdateCheckException("无法读取 GitHub 更新信息，请稍后重试或打开发布页面") }
        }

        private fun trustedUrl(address: String, pathPrefix: String): Boolean = runCatching {
            val uri = URI(address)
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
                uri.path.startsWith(pathPrefix) && uri.normalize().path.startsWith(pathPrefix)
        }.getOrDefault(false)
    }
}
