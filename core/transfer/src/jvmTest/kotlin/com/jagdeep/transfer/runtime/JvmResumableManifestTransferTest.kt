package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JvmResumableManifestTransferTest {
    @Test
    fun `verified partial bytes are negotiated and only remaining bytes are streamed`() = runBlocking {
        val directory = Files.createTempDirectory("zynk-resume-transfer")
        val password = "test-only-secret".toCharArray()
        try {
            val source = directory.resolve("source.txt")
            val content = "a transfer resumed over verified TLS".encodeToByteArray()
            Files.write(source, content)
            val manifest = JvmTransferManifestBuilder(256 * 1024).build("resume-transfer", source)
            val receiveDirectory = Files.createDirectories(directory.resolve("received"))
            val partialStore = JvmPartialTransferStore(receiveDirectory)
            partialStore.open(manifest.transferId, manifest.entries.single())
                .appendAndFinalize(ByteArrayInputStream(content.copyOfRange(0, 8)), 256 * 1024)
            val senderIdentity = JvmPeerIdentityStore(directory.resolve("sender.p12")).create(password)
            val receiverIdentity = JvmPeerIdentityStore(directory.resolve("receiver.p12")).create(password)
            val senderContext = JvmTrustedTlsContext.create(senderIdentity, password, listOf(receiverIdentity.certificate))
            val receiverContext = JvmTrustedTlsContext.create(receiverIdentity, password, listOf(senderIdentity.certificate))
            val listener = JvmTlsTransport(receiverContext).listen(InetAddress.getLoopbackAddress())
            val senderProgress = mutableListOf<TransferProgress>()
            try {
                coroutineScope {
                    val receiver = async {
                        listener.accept().use { connection ->
                            JvmResumableManifestTransfer(receiverIdentity.asPeer("receiver", "Receiver"))
                                .receive(connection, partialStore, { _, _ -> true })
                        }
                    }
                    val sender = JvmTlsTransport(senderContext)
                        .connect(PeerEndpoint("127.0.0.1", listener.endpoint.port))
                        .use { connection ->
                            JvmResumableManifestTransfer(senderIdentity.asPeer("sender", "Sender"))
                                .send(connection, manifest, listOf(source)) { senderProgress += it }
                        }
                    assertIs<SingleFileTransferResult.Completed>(sender)
                    assertIs<SingleFileTransferResult.Completed>(receiver.await())
                }
            } finally {
                listener.close()
            }
            assertEquals(8, senderProgress.first().transferredBytes)
            assertEquals(content.toList(), Files.readAllBytes(receiveDirectory.resolve("source.txt")).toList())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            password.fill('\u0000')
        }
    }

    @Test
    fun `given a large file when it is received then progress is reported while it is written, not only at the ends`() = runBlocking {
        val directory = Files.createTempDirectory("zynk-receive-progress")
        val password = "test-only-secret".toCharArray()
        try {
            val source = directory.resolve("big.bin")
            val content = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
            Files.write(source, content)
            val manifest = JvmTransferManifestBuilder(256 * 1024).build("progress-transfer", source)
            val receiveDirectory = Files.createDirectories(directory.resolve("received"))
            val senderIdentity = JvmPeerIdentityStore(directory.resolve("sender.p12")).create(password)
            val receiverIdentity = JvmPeerIdentityStore(directory.resolve("receiver.p12")).create(password)
            val senderContext = JvmTrustedTlsContext.create(senderIdentity, password, listOf(receiverIdentity.certificate))
            val receiverContext = JvmTrustedTlsContext.create(receiverIdentity, password, listOf(senderIdentity.certificate))
            val listener = JvmTlsTransport(receiverContext).listen(InetAddress.getLoopbackAddress())
            val receiverProgress = java.util.Collections.synchronizedList(mutableListOf<TransferProgress>())
            try {
                coroutineScope {
                    val receiver = async {
                        listener.accept().use { connection ->
                            JvmResumableManifestTransfer(receiverIdentity.asPeer("receiver", "Receiver"))
                                .receive(connection, JvmPartialTransferStore(receiveDirectory), { _, _ -> true }, onProgress = { receiverProgress += it })
                        }
                    }
                    JvmTlsTransport(senderContext).connect(PeerEndpoint("127.0.0.1", listener.endpoint.port)).use { connection ->
                        JvmResumableManifestTransfer(senderIdentity.asPeer("sender", "Sender")).send(connection, manifest, listOf(source))
                    }
                    assertIs<SingleFileTransferResult.Completed>(receiver.await())
                }
            } finally {
                listener.close()
            }
            val total = content.size.toLong()
            kotlin.test.assertTrue(
                receiverProgress.any { it.transferredBytes in 1 until total },
                "Expected progress between 0 and $total, got ${receiverProgress.map { it.transferredBytes }}",
            )
            assertEquals(total, receiverProgress.last().transferredBytes)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            password.fill('\u0000')
        }
    }
}

private fun JvmTlsIdentity.asPeer(deviceId: String, displayName: String) =
    PeerIdentity(deviceId, displayName, certificateFingerprint)
