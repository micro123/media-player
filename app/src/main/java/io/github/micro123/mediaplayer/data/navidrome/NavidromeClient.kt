package io.github.micro123.mediaplayer.data.navidrome

import io.github.micro123.mediaplayer.data.network.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/** Subsonic 1.16.1, supported by Navidrome. No private web API or persistent bearer URL. */
class NavidromeClient(private val profile: NetworkProfile) {
    private val server = NavidromeAddress.parse(profile.address).server

    fun url(endpoint: String, params: Map<String, String> = emptyMap()): String {
        require(endpoint.matches(Regex("[A-Za-z0-9]+")))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val auth = profile.credentials
        val query = params + mapOf("u" to auth.username, "t" to token(auth.password, salt), "s" to salt,
            "v" to "1.16.1", "c" to "MediaPlayer", "f" to "json")
        return "$server/rest/$endpoint.view?" + query.entries.joinToString("&") { (k, v) -> "${NavidromeAddress.encode(k)}=${NavidromeAddress.encode(v)}" }
    }

    fun request(endpoint: String, params: Map<String, String> = emptyMap()): JSONObject {
        try {
            val response = JSONObject(String(read(url(endpoint, params), 8 * 1024 * 1024), Charsets.UTF_8)).getJSONObject("subsonic-response")
            if (response.optString("status") != "ok") throw apiFailure(response.optJSONObject("error")?.optInt("code") ?: 0)
            return response
        } catch (error: RemoteAccessException) { throw error }
        catch (_: org.json.JSONException) { throw RemoteAccessException("服务器响应不是有效的 Subsonic 数据，请检查地址与反向代理配置") }
    }

    fun cover(id: String): ByteArray = read(url("getCoverArt", mapOf("id" to id, "size" to "768")), 4 * 1024 * 1024)

    private fun read(address: String, limit: Int): ByteArray {
        var target = address
        repeat(4) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(target).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json, image/*")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val next = URI(target).resolve(connection.getHeaderField("Location") ?: "")
                    val origin = URI(server)
                    require(attempt < 3 && next.scheme == origin.scheme && next.host.equals(origin.host, true) && next.port == origin.port && next.rawUserInfo == null) {
                        "Navidrome 重定向到不同服务器，请填写最终服务器地址"
                    }
                    target = next.toASCIIString()
                } else {
                    if (code == 401 || code == 403) throw apiFailure(if (code == 401) 40 else 50)
                    if (code !in 200..299) throw RemoteAccessException("无法读取 Navidrome（HTTP $code），请检查服务器地址和服务状态")
                    if (connection.contentLengthLong > limit) throw RemoteAccessException("服务器响应过大，请缩小查询范围")
                    return connection.inputStream.use { input ->
                        val result = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (result.size() <= limit) {
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                            val count = input.read(chunk, 0, minOf(chunk.size, limit + 1 - result.size()))
                            if (count < 0) break
                            result.write(chunk, 0, count)
                        }
                        if (result.size() > limit) throw RemoteAccessException("服务器响应过大，请缩小查询范围")
                        result.toByteArray()
                    }
                }
            } catch (error: RemoteAccessException) { throw error }
            catch (_: IllegalArgumentException) { throw RemoteAccessException("Navidrome 重定向无效，请填写最终服务器地址") }
            catch (error: Exception) { throw explainNetworkFailure("Navidrome", NetworkStage.READ, error) }
            finally { connection?.disconnect() }
        }
        throw RemoteAccessException("Navidrome 重定向次数过多")
    }

    private fun apiFailure(code: Int) = RemoteAccessException(when (code) {
        40, 41, 42, 43, 44 -> "Navidrome 登录失败，请检查用户名和密码"
        50 -> "此账号没有访问该音乐库或播放列表的权限"
        70 -> "曲目、专辑或播放列表已不存在，请刷新音乐库"
        20, 30 -> "服务器不支持所需 Subsonic API 版本"
        else -> "Navidrome 请求失败（错误 $code），请检查服务器配置"
    }, kind = when (code) { 40, 41, 42, 43, 44 -> NetworkFailureKind.AUTHENTICATION; 50 -> NetworkFailureKind.PERMISSION; else -> NetworkFailureKind.PROTOCOL })

    companion object {
        internal fun token(password: String, salt: String): String = MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
