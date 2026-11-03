package com.jagdeep.transfer.runtime

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConnectionErrorMapperTest {
    @Test
    fun `maps connection failures to actionable categories`() {
        assertEquals(DesktopConnectionError.TimedOut, DesktopConnectionErrorMapper.map(SocketTimeoutException()))
        assertEquals(DesktopConnectionError.Refused, DesktopConnectionErrorMapper.map(ConnectException()))
        assertEquals(DesktopConnectionError.NetworkUnavailable, DesktopConnectionErrorMapper.map(NoRouteToHostException()))
        assertEquals(DesktopConnectionError.PeerNotTrusted, DesktopConnectionErrorMapper.map(SSLHandshakeException("untrusted")))
        assertEquals(DesktopConnectionError.SecureChannelFailed, DesktopConnectionErrorMapper.map(SSLException("TLS failure")))
    }
}
