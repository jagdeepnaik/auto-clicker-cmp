package com.jagdeep.transfer.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmPairingTlsTransportTest {
    @Test
    fun `given QR pinned server when scanner connects then pairing transport authenticates server without trusting scanner yet`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("zynk-pairing-tls")
        try {
            val inviter = JvmPeerIdentityStore(directory.resolve("inviter.p12")).create("inviter-password".toCharArray())
            val scanner = JvmPeerIdentityStore(directory.resolve("scanner.p12")).create("scanner-password".toCharArray())
            val listener = JvmTlsTransport(JvmPairingTlsContext.server(inviter)).listen(requireClientAuthentication = false)
            coroutineScope {
                val accepted = async { listener.accept() }
                val client = JvmTlsTransport(JvmPairingTlsContext.client(scanner, inviter.certificate))
                    .connect(PeerEndpoint("127.0.0.1", listener.endpoint.port))
                val server = accepted.await()
                try {
                    assertEquals(inviter.certificateFingerprint, client.peerCertificateFingerprint)
                    assertNull(server.peerCertificateFingerprint)
                    client.sendControl("pairing-proof".encodeToByteArray())
                    assertEquals("pairing-proof", server.receiveControl().decodeToString())
                } finally {
                    client.close()
                    server.close()
                }
            }
            listener.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
