package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JvmPairingInvitationListenerTest {
    @Test
    fun `given one scanner has claimed an invitation when another scanner presents proof then it is rejected`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("zynk-pairing-single-use")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val inviter = JvmPeerIdentityStore(directory.resolve("inviter.p12")).create("inviter-password".toCharArray())
            val scanner = JvmPeerIdentityStore(directory.resolve("scanner.p12")).create("scanner-password".toCharArray())
            val approvals = PairingApprovalCoordinator()
            val listener = JvmPairingInvitationListener(scope, inviter, JvmPairingIdentityProof(inviter), approvals, RecordingPeers())
            val invitation = listener.start("127.0.0.1", "Inviter")
            val transport = JvmTlsTransport(JvmPairingTlsContext.client(scanner, inviter.certificate))
            val first = transport.connect(PeerEndpoint(invitation.host, invitation.port))
            val second = transport.connect(PeerEndpoint(invitation.host, invitation.port))
            try {
                val proof = JvmPairingSessionDispatcher(JvmPairingIdentityProof(scanner)).initialProof(
                    JvmPairingIdentityProof(scanner), invitation.sessionId, "b".repeat(64), "Scanner",
                )
                first.sendPairingMessage(proof)
                approvals.pending.first { it.isNotEmpty() }
                second.sendPairingMessage(proof)
                val rejection = assertIs<PairingWireMessage.Rejection>(withTimeout(5_000) { second.receivePairingMessage() })
                assertEquals("This pairing invitation is already being used on another device.", rejection.reason)
            } finally {
                first.close()
                second.close()
                listener.close()
            }
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given four stalled pairing sessions when a fifth connects then it receives a retry rejection`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("zynk-pairing-capacity")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val inviter = JvmPeerIdentityStore(directory.resolve("inviter.p12")).create("inviter-password".toCharArray())
            val scanner = JvmPeerIdentityStore(directory.resolve("scanner.p12")).create("scanner-password".toCharArray())
            val listener = JvmPairingInvitationListener(scope, inviter, JvmPairingIdentityProof(inviter), PairingApprovalCoordinator(), RecordingPeers())
            val invitation = listener.start("127.0.0.1", "Inviter")
            val transport = JvmTlsTransport(JvmPairingTlsContext.client(scanner, inviter.certificate))
            val stalled = List(4) { transport.connect(PeerEndpoint(invitation.host, invitation.port)) }
            val overload = transport.connect(PeerEndpoint(invitation.host, invitation.port))
            try {
                val rejection = assertIs<PairingWireMessage.Rejection>(withTimeout(5_000) { overload.receivePairingMessage() })
                assertEquals("This device is handling other pairing requests. Try again shortly.", rejection.reason)
            } finally {
                overload.close()
                stalled.forEach { it.close() }
                listener.close()
            }
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given fresh invitation when proof is accepted then receiver decision is sent back over pairing wire`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("zynk-pairing-listener")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val inviter = JvmPeerIdentityStore(directory.resolve("inviter.p12")).create("inviter-password".toCharArray())
            val scanner = JvmPeerIdentityStore(directory.resolve("scanner.p12")).create("scanner-password".toCharArray())
            val approvals = PairingApprovalCoordinator()
            val trustedPeers = RecordingPeers()
            val listener = JvmPairingInvitationListener(scope, inviter, JvmPairingIdentityProof(inviter), approvals, trustedPeers)
            val invitation = listener.start("127.0.0.1", "Inviter")
            val client = JvmTlsTransport(JvmPairingTlsContext.client(scanner, inviter.certificate))
                .connect(PeerEndpoint(invitation.host, invitation.port))
            try {
                client.sendPairingMessage(
                    JvmPairingSessionDispatcher(JvmPairingIdentityProof(scanner)).initialProof(
                        JvmPairingIdentityProof(scanner), invitation.sessionId, "a".repeat(64), "Scanner",
                    ),
                )
                val request = approvals.pending.first { it.isNotEmpty() }.single()
                assertEquals(scanner.certificateFingerprint, request.peer.certificateFingerprint)
                approvals.decide(invitation.sessionId, true)
                val response = client.receivePairingMessage()
                val confirmation = assertIs<PairingWireMessage.Confirmation>(response)
                assertEquals(true, confirmation.accepted)
                client.sendPairingMessage(
                    PairingWireMessage.Acknowledgement(
                        PairingProtocol.CURRENT_VERSION,
                        invitation.sessionId,
                        "a".repeat(64),
                    ),
                )
                val finalAcknowledgement = assertIs<PairingWireMessage.Acknowledgement>(client.receivePairingMessage())
                assertEquals(invitation.sessionId, finalAcknowledgement.sessionId)
                assertEquals(scanner.certificateFingerprint, trustedPeers.peers.value.single().certificateFingerprint)
            } finally {
                client.close()
                listener.close()
            }
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }
}

private class RecordingPeers : TrustedPeerRepository, PairingRecoveryStore {
    private val mutablePeers = MutableStateFlow<List<TrustedPeer>>(emptyList())
    private val mutableRecoveries = MutableStateFlow<List<PendingPairingRecovery>>(emptyList())
    override val peers: StateFlow<List<TrustedPeer>> = mutablePeers
    override val pendingPairingRecoveries: StateFlow<List<PendingPairingRecovery>> = mutableRecoveries
    override suspend fun upsertVerified(peer: TrustedPeer) { mutablePeers.value = mutablePeers.value + peer }
    override suspend fun remove(deviceId: String) = Unit
    override suspend fun findByFingerprint(fingerprint: String): TrustedPeer? = mutablePeers.value.firstOrNull { it.certificateFingerprint == fingerprint }
    override suspend fun recordPendingPairingRecovery(record: PendingPairingRecovery) { mutableRecoveries.value = mutableRecoveries.value + record }
    override suspend fun clearPendingPairingRecovery(peerFingerprint: String) { mutableRecoveries.value = mutableRecoveries.value.filterNot { it.peer.certificateFingerprint == peerFingerprint } }
}
