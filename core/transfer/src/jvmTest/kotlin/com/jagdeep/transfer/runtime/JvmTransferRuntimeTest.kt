package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

class JvmTransferRuntimeTest {
    @Test
    fun `given trusted peer without receive directory when started then outgoing transport is available`() = runTest {
        val directory = Files.createTempDirectory("zynk-send-only")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var runtime: JvmTransferRuntime? = null
        try {
            val identity = JvmPeerIdentityStore(directory.resolve("identity.p12")).create("test-password".toCharArray())
            val peer = TrustedPeer("peer", "Peer", identity.certificateFingerprint, identity.certificate.encoded)
            runtime = JvmTransferRuntime(scope, identity,
                PeerIdentity("local", "Local", identity.certificateFingerprint),
                FakeTrustedPeerRepository(peer), TransferApprovalCoordinator(), receiveDirectory = { null })
            assertIs<JvmTransferRuntimeState.Listening>(runtime.start())
        } finally {
            runtime?.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given trusted peer and receive directory when started then runtime listens`() = runTest {
        val directory = Files.createTempDirectory("zynk-runtime")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val localIdentity = JvmPeerIdentityStore(directory.resolve("local.p12")).create("local-password".toCharArray())
            val remoteIdentity = JvmPeerIdentityStore(directory.resolve("remote.p12")).create("remote-password".toCharArray())
            val peer = TrustedPeer("desktop-remote", "Remote Mac", remoteIdentity.certificateFingerprint, remoteIdentity.certificate.encoded)
            val runtime = JvmTransferRuntime(
                scope = scope,
                identity = localIdentity,
                localPeer = PeerIdentity("desktop-local", "Local Mac", localIdentity.certificateFingerprint),
                trustedPeers = FakeTrustedPeerRepository(peer),
                approvals = TransferApprovalCoordinator(),
                receiveDirectory = { directory },
            )

            assertIs<JvmTransferRuntimeState.Listening>(runtime.start())
            runtime.close()
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    private class FakeTrustedPeerRepository(peer: TrustedPeer) : TrustedPeerRepository {
        override val peers: StateFlow<List<TrustedPeer>> = MutableStateFlow(listOf(peer))
        override suspend fun upsertVerified(peer: TrustedPeer) = Unit
        override suspend fun remove(deviceId: String) = Unit
        override suspend fun findByFingerprint(fingerprint: String): TrustedPeer? = peers.value.firstOrNull {
            it.certificateFingerprint == fingerprint
        }
    }
}
