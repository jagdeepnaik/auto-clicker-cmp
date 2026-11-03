package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JvmPairingSessionDispatcherTest {
    @Test
    fun `given QR pinned listener and signed scanner proof when dispatched then scanner is verified but not trusted`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("zynk-pairing-dispatch")
        try {
            val inviter = JvmPeerIdentityStore(directory.resolve("inviter.p12")).create("inviter-password".toCharArray())
            val scanner = JvmPeerIdentityStore(directory.resolve("scanner.p12")).create("scanner-password".toCharArray())
            val listener = JvmTlsTransport(JvmPairingTlsContext.server(inviter)).listen(requireClientAuthentication = false)
            coroutineScope {
                val received = async {
                    val connection = listener.accept()
                    try { JvmPairingSessionDispatcher(JvmPairingIdentityProof(inviter)).receiveInitialProof(connection) }
                    finally { connection.close() }
                }
                val client = JvmTlsTransport(JvmPairingTlsContext.client(scanner, inviter.certificate))
                    .connect(PeerEndpoint("127.0.0.1", listener.endpoint.port))
                try {
                    client.sendPairingMessage(
                        JvmPairingSessionDispatcher(JvmPairingIdentityProof(scanner)).initialProof(
                            signer = JvmPairingIdentityProof(scanner),
                            sessionId = "session-1",
                            transcriptHash = "a".repeat(64),
                            displayName = "Scanner",
                        ),
                    )
                    val result = received.await()
                    val verified = assertIs<PairingDispatchResult.ProofVerified>(result)
                    assertEquals(scanner.certificateFingerprint, verified.peer.certificateFingerprint)
                } finally { client.close() }
            }
            listener.close()
        } finally { directory.toFile().deleteRecursively() }
    }
}
