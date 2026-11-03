package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.SyncTarget
import com.jagdeep.transfer.protocol.TransferManifest
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class JvmSyncTransferTest {
    private enum class Receiver { Resumable, Batch }

    @Test
    fun `given a mirror sync over the resumable receiver then changes land in the sync folder and old versions go to trash`() {
        mirrorSync(Receiver.Resumable)
    }

    @Test
    fun `given a mirror sync over the batch receiver then changes land in the sync folder and old versions go to trash`() {
        mirrorSync(Receiver.Batch)
    }

    private fun mirrorSync(receiver: Receiver): Unit = runBlocking {
        val dir = Files.createTempDirectory("zynk-sync")
        val password = "test-only-secret".toCharArray()
        try {
            val source = Files.createDirectories(dir.resolve("source"))
            val receive = Files.createDirectories(dir.resolve("received"))
            Files.writeString(receive.resolve("unrelated.txt"), "keep me")
            val sender = JvmPeerIdentityStore(dir.resolve("s.p12")).create(password)
            val receiverId = JvmPeerIdentityStore(dir.resolve("r.p12")).create(password)
            val senderCtx = JvmTrustedTlsContext.create(sender, password, listOf(receiverId.certificate))
            val receiverCtx = JvmTrustedTlsContext.create(receiverId, password, listOf(sender.certificate))

            suspend fun sync(files: List<String>, sync: SyncTarget): SingleFileTransferResult {
                val manifest = TransferManifest("t-${System.nanoTime()}", files.map { JvmTransferManifestBuilder().build("x", source.resolve(it), it).entries.single() }, sync)
                val listener = JvmTlsTransport(receiverCtx).listen(InetAddress.getLoopbackAddress())
                try {
                    return coroutineScope {
                        val received = async {
                            listener.accept().use { connection ->
                                val local = receiverId.asPeer("r")
                                when (receiver) {
                                    Receiver.Resumable -> JvmResumableManifestTransfer(local).receive(connection, JvmPartialTransferStore(receive), { _, _ -> true }, syncRoot = receive)
                                    Receiver.Batch -> JvmManifestTransfer(local).receive(connection, JvmFileFinalizer(receive, FileConflictPolicy.RENAME), { _, _ -> true }, syncRoot = receive)
                                }
                            }
                        }
                        JvmTlsTransport(senderCtx).connect(PeerEndpoint("127.0.0.1", listener.endpoint.port)).use { connection ->
                            when (receiver) {
                                Receiver.Resumable -> JvmResumableManifestTransfer(sender.asPeer("s")).send(connection, manifest, files.map(source::resolve))
                                Receiver.Batch -> JvmManifestTransfer(sender.asPeer("s")).send(connection, manifest, files.map(source::resolve))
                            }
                        }
                        received.await()
                    }
                } finally {
                    listener.close()
                }
            }

            Files.writeString(source.resolve("a.txt"), "version 1")
            Files.createDirectories(source.resolve("sub"))
            Files.writeString(source.resolve("sub/b.txt"), "bee")
            assertIs<SingleFileTransferResult.Completed>(sync(listOf("a.txt", "sub/b.txt"), SyncTarget("pair-1", "Photos")))

            val synced = receive.resolve("Zynk Sync/Photos")
            assertEquals("version 1", Files.readString(synced.resolve("a.txt")))
            assertEquals("bee", Files.readString(synced.resolve("sub/b.txt")))

            // Mirror: a.txt changed, sub/b.txt deleted at the source.
            Files.writeString(source.resolve("a.txt"), "version 2")
            assertIs<SingleFileTransferResult.Completed>(sync(listOf("a.txt"), SyncTarget("pair-1", "Photos", remove = listOf("sub/b.txt"))))

            assertEquals("version 2", Files.readString(synced.resolve("a.txt")), "Changed file replaced")
            assertFalse(Files.exists(synced.resolve("sub/b.txt")), "Mirror removal applied")
            val trashed = Files.walk(synced.resolve(".zynk-trash")).use { paths -> paths.filter(Files::isRegularFile).map { it.fileName.toString() }.toList() }.sorted()
            assertEquals(listOf("a.txt", "b.txt"), trashed, "Old versions are kept in trash")
            assertTrue(Files.walk(synced.resolve(".zynk-trash")).use { paths -> paths.anyMatch { Files.isRegularFile(it) && Files.readString(it) == "version 1" } })
            assertEquals("keep me", Files.readString(receive.resolve("unrelated.txt")), "Nothing outside the sync folder is touched")
        } finally {
            dir.toFile().deleteRecursively()
            password.fill('\u0000')
        }
    }

    @Test
    fun `given a receiver without sync support when a sync arrives then it is declined with a clear reason`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("zynk-sync-decline")
        val password = "test-only-secret".toCharArray()
        try {
            val source = Files.createDirectories(dir.resolve("source")).also { Files.writeString(it.resolve("a.txt"), "x") }
            val sender = JvmPeerIdentityStore(dir.resolve("s.p12")).create(password)
            val receiverId = JvmPeerIdentityStore(dir.resolve("r.p12")).create(password)
            val listener = JvmTlsTransport(JvmTrustedTlsContext.create(receiverId, password, listOf(sender.certificate))).listen(InetAddress.getLoopbackAddress())
            val manifest = TransferManifest("t1", listOf(JvmTransferManifestBuilder().build("x", source.resolve("a.txt"), "a.txt").entries.single()), SyncTarget("pair-1", "Photos"))
            try {
                coroutineScope {
                    val received = async {
                        listener.accept().use { JvmManifestTransfer(receiverId.asPeer("r")).receive(it, JvmFileFinalizer(dir, FileConflictPolicy.RENAME), { _, _ -> true }) }
                    }
                    val sent = JvmTlsTransport(JvmTrustedTlsContext.create(sender, password, listOf(receiverId.certificate)))
                        .connect(PeerEndpoint("127.0.0.1", listener.endpoint.port))
                        .use { JvmManifestTransfer(sender.asPeer("s")).send(it, manifest, listOf(source.resolve("a.txt"))) }
                    assertEquals(TransferApprovalMessages.SYNC_UNSUPPORTED, (sent as SingleFileTransferResult.Rejected).message)
                    assertIs<SingleFileTransferResult.Rejected>(received.await())
                }
            } finally {
                listener.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given a link inside the sync folder pointing outside when preparing then the sync is refused`() {
        val dir = Files.createTempDirectory("zynk-sync-link")
        try {
            val receive = Files.createDirectories(dir.resolve("received"))
            val outside = Files.createDirectories(dir.resolve("outside")).also { Files.writeString(it.resolve("secret.txt"), "s") }
            val synced = Files.createDirectories(receive.resolve("Zynk Sync/Photos"))
            Files.createSymbolicLink(synced.resolve("escape"), outside)
            val manifest = TransferManifest("t1", listOf(com.jagdeep.transfer.protocol.TransferManifestEntry("escape/secret.txt", 1, "a".repeat(64))), SyncTarget("p", "Photos"))
            assertFailsWith<IllegalArgumentException> { JvmSyncDestination.prepare(receive, manifest) }
            assertEquals("s", Files.readString(outside.resolve("secret.txt")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun JvmTlsIdentity.asPeer(id: String) = PeerIdentity(id, id, certificateFingerprint)

}
