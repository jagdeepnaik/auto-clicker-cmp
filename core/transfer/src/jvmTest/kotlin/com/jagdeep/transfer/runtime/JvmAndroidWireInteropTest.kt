package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferCapability
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferProtocol
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Drives the desktop runtime against a peer that replays the exact Android control sequence
 * (AndroidOutgoingTransfer / AndroidIncomingTransfer): one Hello each way, then the manifest.
 * JVM ↔ JVM tests cannot catch a desktop-only extra Hello because both sides repeat it.
 */
class JvmAndroidWireInteropTest {
    @Test
    fun `given android batch receiver when desktop sends two files then one hello and one approval complete the batch`() = runBlocking {
        withFixture { fixture ->
            val first = Files.write(fixture.directory.resolve("first.txt"), "first".encodeToByteArray())
            val second = Files.write(fixture.directory.resolve("second.txt"), "second-file".encodeToByteArray())
            val desktop = fixture.desktopRuntime(receiveDirectory = { null })
            assertIs<JvmTransferRuntimeState.Listening>(desktop.start())
            val androidListener = JvmTlsTransport(fixture.androidContext()).listen(InetAddress.getLoopbackAddress())
            try {
                coroutineScope {
                    val android = async(Dispatchers.IO) {
                        androidListener.accept().use { connection -> fixture.receiveLikeAndroid(connection) }
                    }
                    val result = desktop.sendFiles(
                        PeerEndpoint("127.0.0.1", androidListener.endpoint.port),
                        listOf(
                            JvmOutgoingTransferFile(first, "first.txt", "first.txt"),
                            JvmOutgoingTransferFile(second, "second.txt", "second.txt"),
                        ),
                    )

                    assertTrue(result.completed, result.rejection?.message)
                    assertEquals(2, result.completedCount)
                    assertEquals(mapOf("first.txt" to "first", "second.txt" to "second-file"), android.await())
                }
            } finally {
                androidListener.close()
                desktop.close()
            }
        }
    }

    @Test
    fun `given android batch sender when desktop accepts then files are written and history callback fires`() = runBlocking {
        withFixture { fixture ->
            val receiveDirectory = Files.createDirectories(fixture.directory.resolve("received"))
            Files.write(receiveDirectory.resolve("photo.jpg"), "existing".encodeToByteArray())
            val finished = mutableListOf<SingleFileTransferResult>()
            val progress = java.util.concurrent.CopyOnWriteArrayList<IncomingTransferActivity>()
            val ended = java.util.concurrent.CopyOnWriteArrayList<String>()
            val desktop = fixture.desktopRuntime(
                receiveDirectory = { receiveDirectory },
                onIncomingProgress = { progress += it },
                onIncomingEnded = { ended += it },
            ) { result, _ -> finished += result }
            val listening = assertIs<JvmTransferRuntimeState.Listening>(desktop.start())
            val approver = fixture.scope.launch {
                val request = fixture.approvals.pending.first { it.isNotEmpty() }.single()
                fixture.approvals.decide(request.requestId, accepted = true)
            }
            try {
                val completed = withTimeout(30_000) {
                    JvmTlsTransport(fixture.androidContext())
                        .connect(PeerEndpoint("127.0.0.1", listening.endpoint.port))
                        .use { connection ->
                            fixture.sendLikeAndroid(connection, mapOf("photo.jpg" to "new-photo", "notes/a.txt" to "note"))
                        }
                }

                assertTrue(completed)
                // A repeated name is renamed rather than failing the whole batch.
                assertEquals("existing", Files.readString(receiveDirectory.resolve("photo.jpg")))
                assertEquals("note", Files.readString(receiveDirectory.resolve("notes/a.txt")))
                val renamed = Files.list(receiveDirectory).use { paths ->
                    paths.filter { it.fileName.toString() != "photo.jpg" && Files.isRegularFile(it) }.toList()
                }
                assertEquals(listOf("new-photo"), renamed.map(Files::readString))
                withTimeout(5_000) { while (finished.isEmpty() || ended.isEmpty()) kotlinx.coroutines.yield() }
                assertIs<SingleFileTransferResult.Completed>(finished.single())
                // The receiver reports its own progress, under one activity id, then always ends it.
                val last = progress.last()
                assertEquals("2 files", last.label)
                assertEquals("Pixel", last.peer.displayName)
                assertEquals(last.progress.totalBytes, last.progress.transferredBytes)
                assertEquals(setOf(last.id), progress.map { it.id }.toSet())
                assertEquals(listOf(last.id), ended.toList())
            } finally {
                approver.cancel()
                desktop.close()
            }
        }
    }

    private class Fixture(val directory: Path, val scope: CoroutineScope) {
        private val password = "test-only-secret".toCharArray()
        val approvals = TransferApprovalCoordinator()
        val desktopIdentity = JvmPeerIdentityStore(directory.resolve("desktop.p12")).create(password)
        val androidIdentity = JvmPeerIdentityStore(directory.resolve("android.p12")).create(password)
        val androidPeer = PeerIdentity(
            deviceId = "android-${androidIdentity.certificateFingerprint.take(24)}",
            displayName = "Pixel",
            publicKeyFingerprint = androidIdentity.certificateFingerprint,
            capabilities = setOf(TransferCapability.BATCH_MANIFESTS),
        )
        private val desktopPeer = PeerIdentity(
            deviceId = "desktop-${desktopIdentity.certificateFingerprint.take(24)}",
            displayName = "Mac",
            publicKeyFingerprint = desktopIdentity.certificateFingerprint,
            capabilities = setOf(TransferCapability.RESUMABLE_MANIFESTS, TransferCapability.BATCH_MANIFESTS),
        )
        private val desktopTrust = InMemoryTrustedPeers(
            TrustedPeer(androidPeer.deviceId, androidPeer.displayName, androidPeer.publicKeyFingerprint, androidIdentity.certificate.encoded),
        )

        fun androidContext() = JvmTrustedTlsContext.create(androidIdentity, password, listOf(desktopIdentity.certificate))

        fun desktopRuntime(
            receiveDirectory: () -> Path?,
            onIncomingProgress: (IncomingTransferActivity) -> Unit = {},
            onIncomingEnded: (String) -> Unit = {},
            onIncomingFinished: (SingleFileTransferResult, TransferManifest) -> Unit = { _, _ -> },
        ) = JvmTransferRuntime(
            scope, desktopIdentity, desktopPeer, desktopTrust, approvals, receiveDirectory, onIncomingFinished,
            onIncomingProgress = onIncomingProgress,
            onIncomingEnded = onIncomingEnded,
        )

        /** Mirrors AndroidIncomingTransfer.receive. */
        suspend fun receiveLikeAndroid(connection: JvmTlsConnection): Map<String, String> = withContext(Dispatchers.IO) {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-receiver", androidPeer))
            assertIs<ControlMessage.Hello>(connection.receiveMessage())
            val request = assertIs<ControlMessage.TransferRequest>(connection.receiveMessage())
            connection.sendMessage(
                ControlMessage.TransferDecision(TransferProtocol.CURRENT_VERSION, request.requestId, request.manifest.transferId, true),
            )
            val received = request.manifest.entries.associate { entry ->
                val body = ByteArrayOutputStream()
                LimitedInputStream(connection.dataInput(), entry.sizeBytes).copyTo(body)
                entry.relativePath to body.toByteArray().decodeToString()
            }
            val complete = assertIs<ControlMessage.TransferComplete>(connection.receiveMessage())
            connection.sendMessage(
                ControlMessage.TransferResult(TransferProtocol.CURRENT_VERSION, "result", complete.transferId, completed = true),
            )
            received
        }

        /** Mirrors AndroidOutgoingTransfer.sendFiles. */
        suspend fun sendLikeAndroid(connection: JvmTlsConnection, files: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
            val transferId = "android-batch"
            val manifest = TransferManifest(transferId, files.map { (path, content) ->
                val file = Files.write(directory.resolve("source-${path.replace('/', '_')}"), content.encodeToByteArray())
                JvmTransferManifestBuilder().build(transferId, file, path).entries.single()
            })
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-$transferId", androidPeer))
            assertIs<ControlMessage.Hello>(connection.receiveMessage())
            connection.sendMessage(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, "request-$transferId", manifest))
            val decision = assertIs<ControlMessage.TransferDecision>(connection.receiveMessage())
            if (!decision.accepted) return@withContext false
            files.values.forEach { connection.dataOutput().write(it.encodeToByteArray()) }
            connection.dataOutput().flush()
            val total = manifest.entries.sumOf { it.sizeBytes }
            connection.sendMessage(ControlMessage.TransferComplete(TransferProtocol.CURRENT_VERSION, "complete-$transferId", transferId, total))
            assertIs<ControlMessage.TransferResult>(connection.receiveMessage()).completed
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("zynk-android-interop")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(Fixture(directory, scope))
        } finally {
            scope.cancel()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private class InMemoryTrustedPeers(peer: TrustedPeer) : TrustedPeerRepository {
        override val peers: StateFlow<List<TrustedPeer>> = MutableStateFlow(listOf(peer))
        override suspend fun upsertVerified(peer: TrustedPeer) = Unit
        override suspend fun remove(deviceId: String) = Unit
        override suspend fun findByFingerprint(fingerprint: String): TrustedPeer? =
            peers.value.firstOrNull { it.certificateFingerprint == fingerprint }
    }
}
