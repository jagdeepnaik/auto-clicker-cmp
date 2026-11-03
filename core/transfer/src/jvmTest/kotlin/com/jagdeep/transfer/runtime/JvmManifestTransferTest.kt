package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JvmManifestTransferTest {
    @Test
    fun `paired peers transfer a nested folder with verified relative paths`() = runBlocking {
        val directory = Files.createTempDirectory("zynk-manifest-transfer")
        val password = "test-only-secret".toCharArray()
        try {
            val sourceDirectory = Files.createDirectories(directory.resolve("source/nested"))
            Files.write(directory.resolve("source/root.txt"), "root".encodeToByteArray())
            Files.write(directory.resolve("source/second.txt"), "second".encodeToByteArray())
            Files.write(sourceDirectory.resolve("child.txt"), "child".encodeToByteArray())
            val receiveDirectory = Files.createDirectories(directory.resolve("received"))
            val senderIdentity = JvmPeerIdentityStore(directory.resolve("sender.p12")).create(password)
            val receiverIdentity = JvmPeerIdentityStore(directory.resolve("receiver.p12")).create(password)
            val senderContext = JvmTrustedTlsContext.create(senderIdentity, password, listOf(receiverIdentity.certificate))
            val receiverContext = JvmTrustedTlsContext.create(receiverIdentity, password, listOf(senderIdentity.certificate))
            val listener = JvmTlsTransport(receiverContext).listen(InetAddress.getLoopbackAddress())
            try {
                coroutineScope {
                    var approvalCount = 0
                    val receiver = async {
                        listener.accept().use { connection ->
                            JvmManifestTransfer(receiverIdentity.asPeer("receiver", "Receiver")).receive(
                                connection, JvmFileFinalizer(receiveDirectory), { _, manifest ->
                                    approvalCount++
                                    manifest.entries.size == 3
                                },
                            )
                        }
                    }
                    val sender = JvmTlsTransport(senderContext)
                        .connect(PeerEndpoint("127.0.0.1", listener.endpoint.port))
                        .use { connection ->
                            JvmManifestTransfer(senderIdentity.asPeer("sender", "Sender")).sendDirectory(
                                connection, "folder-transfer", directory.resolve("source"),
                            )
                        }

                    assertIs<SingleFileTransferResult.Completed>(sender)
                    assertIs<SingleFileTransferResult.Completed>(receiver.await())
                    assertEquals(1, approvalCount)
                }
            } finally {
                listener.close()
            }
            assertEquals("root", Files.readString(receiveDirectory.resolve("root.txt")))
            assertEquals("second", Files.readString(receiveDirectory.resolve("second.txt")))
            assertEquals("child", Files.readString(receiveDirectory.resolve("nested/child.txt")))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            password.fill('\u0000')
        }
    }
}

private fun JvmTlsIdentity.asPeer(deviceId: String, displayName: String) =
    PeerIdentity(deviceId, displayName, certificateFingerprint)
