package com.tang.player.data.network

import com.hierynomus.mssmb2.SMBApiException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class NetworkFailureKind { SYSTEM_BLOCKED, AUTHENTICATION, PERMISSION, SHARE_NOT_FOUND, PATH_NOT_FOUND,
    HOST_NOT_FOUND, CONNECTION_REFUSED, UNREACHABLE, TIMEOUT, PROTOCOL, OTHER }
enum class NetworkStage(val label: String) { CONNECT("建立连接"), AUTHENTICATE("登录认证"), SHARE("打开共享"), LIST("读取目录"), READ("读取文件") }

/** Only controlled text/status codes are shown or logged; exception messages may contain credentials. */
fun explainNetworkFailure(protocol: String, stage: NetworkStage, error: Throwable): RemoteAccessException {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    val api = causes.filterIsInstance<SMBApiException>().firstOrNull()
    val socket = causes.filterIsInstance<SocketException>().firstOrNull()
    val creationRejected = socket?.message?.let {
        it.contains("socket failed", true) && listOf("ECONNREFUSED", "EACCES", "EPERM").any { code -> it.contains(code, true) }
    } == true
    val kind = when {
        creationRejected || causes.any { it is SecurityException } -> NetworkFailureKind.SYSTEM_BLOCKED
        causes.any { it is UnknownHostException } -> NetworkFailureKind.HOST_NOT_FOUND
        causes.any { it is SocketTimeoutException } -> NetworkFailureKind.TIMEOUT
        causes.any { it is NoRouteToHostException } -> NetworkFailureKind.UNREACHABLE
        api != null -> when (api.statusCode) {
            0xC000006DL, 0xC0000071L, 0xC0000072L, 0xC000015BL -> NetworkFailureKind.AUTHENTICATION
            0xC0000022L -> if (stage == NetworkStage.AUTHENTICATE) NetworkFailureKind.AUTHENTICATION else NetworkFailureKind.PERMISSION
            0xC00000CCL, 0xC00000BEL -> NetworkFailureKind.SHARE_NOT_FOUND
            0xC0000034L, 0xC000003AL, 0xC0000033L -> NetworkFailureKind.PATH_NOT_FOUND
            else -> NetworkFailureKind.PROTOCOL
        }
        causes.any { it is ConnectException } -> NetworkFailureKind.CONNECTION_REFUSED
        else -> NetworkFailureKind.OTHER
    }
    val detail = when (kind) {
        NetworkFailureKind.SYSTEM_BLOCKED -> "系统拒绝创建网络连接。请在本应用的系统设置中允许网络访问；这时连接尚未到达服务器。"
        NetworkFailureKind.AUTHENTICATION -> "认证失败。请检查用户名、密码和域；共享不允许访客时请关闭访客访问。"
        NetworkFailureKind.PERMISSION -> "账号没有读取该共享或目录的权限。"
        NetworkFailureKind.SHARE_NOT_FOUND -> "找不到共享。请填写服务器的共享名，例如 smb://服务器/Movies，而不是服务器上的本地路径。"
        NetworkFailureKind.PATH_NOT_FOUND -> "找不到目录或文件。请检查共享名之后的路径，或刷新目录。"
        NetworkFailureKind.HOST_NOT_FOUND -> "找不到服务器。请检查主机名 / IP，必要时使用 IP 地址。"
        NetworkFailureKind.CONNECTION_REFUSED -> if (protocol == "SMB") "服务器拒绝连接。请检查 SMB 服务和端口（通常为 445）。"
            else "服务器拒绝连接。请检查服务器的网络服务、端口与防火墙。"
        NetworkFailureKind.UNREACHABLE -> "无法到达服务器。请检查 Wi-Fi、局域网和 VPN 路由。"
        NetworkFailureKind.TIMEOUT -> "连接超时。请检查服务器地址、网络和防火墙。"
        NetworkFailureKind.PROTOCOL -> "服务器返回协议错误，请检查协议支持与服务器配置。"
        NetworkFailureKind.OTHER -> "操作失败，请检查网络、服务器状态与来源配置。"
    }
    val code = api?.statusCode?.let { "（状态 0x${it.toString(16).uppercase()}）" }.orEmpty()
    return RemoteAccessException("$protocol ${stage.label}失败：$detail$code", error, kind)
}
