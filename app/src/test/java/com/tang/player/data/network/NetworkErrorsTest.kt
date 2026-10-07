package com.tang.player.data.network

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import org.junit.Assert.*
import org.junit.Test
import java.net.*

class NetworkErrorsTest {
    private fun explain(error: Throwable, stage: NetworkStage = NetworkStage.CONNECT) = explainNetworkFailure("SMB", stage, error)
    private fun status(code: Long, stage: NetworkStage) = explain(SMBApiException(code, SMB2MessageCommandCode.SMB2_SESSION_SETUP,
        "must-not-expose-password", null), stage)
    @Test fun systemDenialIsDistinctFromServerRefusalEvenWhenWrapped() {
        val blocked = explain(IllegalStateException(SocketException("socket failed: ECONNREFUSED (Connection refused)")))
        assertEquals(NetworkFailureKind.SYSTEM_BLOCKED, blocked.kind)
        assertTrue(blocked.message!!.contains("尚未到达服务器"))
        assertEquals(NetworkFailureKind.CONNECTION_REFUSED, explain(ConnectException("Connection refused")).kind)
    }
    @Test fun permissionFailureIsReportedAtItsStage() {
        assertEquals(NetworkFailureKind.AUTHENTICATION, status(0xC0000022L, NetworkStage.AUTHENTICATE).kind)
        assertEquals(NetworkFailureKind.PERMISSION, status(0xC0000022L, NetworkStage.LIST).kind)
        assertEquals(NetworkFailureKind.AUTHENTICATION, status(0xC000006DL, NetworkStage.AUTHENTICATE).kind)
    }
    @Test fun badShareAndMissingPathHaveDifferentAdvice() {
        assertEquals(NetworkFailureKind.SHARE_NOT_FOUND, status(0xC00000CCL, NetworkStage.SHARE).kind)
        assertEquals(NetworkFailureKind.PATH_NOT_FOUND, status(0xC0000034L, NetworkStage.LIST).kind)
    }
    @Test fun networkFailuresKeepTheirMeaning() {
        assertEquals(NetworkFailureKind.HOST_NOT_FOUND, explain(UnknownHostException()).kind)
        assertEquals(NetworkFailureKind.TIMEOUT, explain(SocketTimeoutException()).kind)
        assertEquals(NetworkFailureKind.UNREACHABLE, explain(NoRouteToHostException()).kind)
    }
    @Test fun privateExceptionMessagesNeverBecomeUserFacingText() {
        val result = status(0xC000006DL, NetworkStage.AUTHENTICATE)
        assertFalse(result.message!!.contains("must-not-expose-password"))
        assertTrue(result.message!!.contains("C000006D"))
        assertFalse(explain(Exception("secret-account-or-token")).message!!.contains("secret-account-or-token"))
    }
    @Test fun securityExceptionProvidesSettingsAdvice() {
        assertEquals(NetworkFailureKind.SYSTEM_BLOCKED, explain(SecurityException("private-permission-info")).kind)
    }
}
