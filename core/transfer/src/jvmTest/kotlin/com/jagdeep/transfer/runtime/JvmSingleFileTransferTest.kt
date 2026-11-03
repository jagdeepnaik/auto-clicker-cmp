package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferManifestEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmSingleFileTransferTest {
    @Test
    fun `paired peers transfer an approved file over TLS and verify its final output`() = runBlocking {
        val directory = Files.createTempDirectory("zynk-transfer-test")
        val password = "test-only-secret".toCharArray()
        try {
            val senderIdentity = JvmPeerIdentityStore(directory.resolve("sender.p12")).create(password)
            val receiverIdentity = JvmPeerIdentityStore(directory.resolve("receiver.p12")).create(password)
            val senderPeer = senderIdentity.asPeer("sender", "Sender")
            val receiverPeer = receiverIdentity.asPeer("receiver", "Receiver")
            val senderContext = JvmTrustedTlsContext.create(senderIdentity, password, listOf(receiverIdentity.certificate))
            val receiverContext = JvmTrustedTlsContext.create(receiverIdentity, password, listOf(senderIdentity.certificate))
            val receiverDirectory = Files.createDirectory(directory.resolve("received"))
            val content = "verified peer-to-peer data".encodeToByteArray()
            val manifest = TransferManifest(
                transferId = "transfer-1",
                entries = listOf(TransferManifestEntry("shared/file.txt", content.size.toLong(), content.sha256())),
            )
            val listener = JvmTlsTransport(receiverContext).listen(InetAddress.getLoopbackAddress())
            val sentProgress = mutableListOf<TransferProgress>()
            val receivedProgress = mutableListOf<TransferProgress>()
            val receivedFinalizations = mutableListOf<SingleFileTransferResult>()
            try {
                coroutineScope {
                    val receiver = async {
                        listener.accept().use { connection ->
                            JvmSingleFileTransfer(receiverPeer).receive(
                                connection = connection,
                                finalizer = JvmFileFinalizer(receiverDirectory),
                                approver = { _, _ -> true },
                                onProgress = { receivedProgress += it },
                                onFinished = { result, _ -> receivedFinalizations += result },
                            )
                        }
                    }
                    val sender = JvmTlsTransport(senderContext)
                        .connect(PeerEndpoint("127.0.0.1", listener.endpoint.port))
                        .use { connection ->
                            JvmSingleFileTransfer(senderPeer).send(
                                connection,
                                manifest,
                                ByteArrayInputStream(content),
                            ) { sentProgress += it }
                        }

                    assertIs<SingleFileTransferResult.Completed>(sender)
                    assertIs<SingleFileTransferResult.Completed>(receiver.await())
                    assertEquals(content.size.toLong(), sentProgress.last().transferredBytes)
                    assertTrue(sentProgress.last().estimatedRemainingMillis == 0L)
                    assertEquals(content.size.toLong(), receivedProgress.last().transferredBytes)
                    assertIs<SingleFileTransferResult.Completed>(receivedFinalizations.single())
                }
            } finally {
                listener.close()
            }
            assertEquals(content.toList(), Files.readAllBytes(receiverDirectory.resolve("shared/file.txt")).toList())
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
            password.fill('\u0000')
        }
    }
}

private fun JvmTlsIdentity.asPeer(deviceId: String, displayName: String) =
    PeerIdentity(deviceId, displayName, certificateFingerprint)

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
