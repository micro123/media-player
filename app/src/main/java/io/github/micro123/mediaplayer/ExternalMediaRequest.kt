package io.github.micro123.mediaplayer

import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Keep the provider's URI: a temporary read grant applies to this exact address. */
data class ExternalMediaRequest(val uri: Uri, val mimeType: String?) {
    companion object {
        fun fromIntent(intent: Intent): ExternalMediaRequest? {
            if (intent.action != Intent.ACTION_VIEW) return null
            val uri = requireNotNull(intent.data) { "没有收到文件地址，请从文件管理器重新打开" }
            require(uri.toString().length <= 8192 && uri.isHierarchical && when (uri.scheme) {
                "content" -> !uri.authority.isNullOrBlank()
                "file" -> uri.path?.startsWith('/') == true && uri.authority.isNullOrEmpty()
                else -> false
            }) { "此打开方式仅支持本地音视频文件" }
            return ExternalMediaRequest(uri, mediaMimeType(intent.type))
        }
    }
}

internal fun mediaMimeType(value: String?): String? {
    val mime = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
    return when {
        mime == "application/x-matroska" -> "video/x-matroska"
        mime == "application/ogg" -> "audio/ogg"
        (mime.startsWith("audio/") || mime.startsWith("video/")) && mime.substringAfter('/').let { it.isNotBlank() && '*' !in it } -> mime
        else -> null
    }
}
