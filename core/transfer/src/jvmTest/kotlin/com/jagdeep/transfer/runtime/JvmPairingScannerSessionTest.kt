package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JvmPairingScannerSessionTest {
    @Test
    fun `given receiver acceptance when scanner confirms code then scanner acknowledges pairing`() = runTest {
        val directory = Files.createTempDirectory("zynk-pairing-scanner")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val inviter = JvmPeerIdentityStore(directory.resolve("inviter.p12")).create("inviter-password".toCharArray())
            val scanner = JvmPeerIdentityStore(directory.resolve("scanner.p12")).create("scanner-password".toCharArray())
            val approvals = PairingApprovalCoordinator()
            val inviterPeers = ScannerRecordingPeers()
            val scannerPeers = ScannerRecordingPeers()
            val listener = JvmPairingInvitationListener(scope, inviter, JvmPairingIdentityProof(inviter), approvals, inviterPeers)
            val invitation = listener.start("127.0.0.1", "Inviter")
            val result = async(Dispatchers.IO) {
                JvmPairingScannerSession(scanner, scannerPeers).connectAndConfirm(invitation, "Scanner", localCodeConfirmed = true)
            }
            approvals.pending.first { it.isNotEmpty() }
            approvals.decide(invitation.sessionId, true)
            assertIs<PairingScannerResult.Acknowledged>(result.await())
            assertEquals(1, inviterPeers.peers.value.size)
            assertEquals(1, scannerPeers.peers.value.size)
            // The stored record carries the certificate verified by TLS, not data from the QR.
            assertEquals(inviter.certificate.encoded.toList(), scannerPeers.peers.value.single().certificateDer.toList())
            listener.close()
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given a code whose fingerprint names another certificate when connecting then pinning rejects it and nothing is saved`() = runTest {
        val directory = Files.createTempDirectory("zynk-pairing-pin")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val inviter = JvmPeerIdentityStore(directory.resolve("inviter.p12")).create("inviter-password".toCharArray())
            val impostor = JvmPeerIdentityStore(directory.resolve("impostor.p12")).create("impostor-password".toCharArray())
            val scanner = JvmPeerIdentityStore(directory.resolve("scanner.p12")).create("scanner-password".toCharArray())
            val approvals = PairingApprovalCoordinator()
            val inviterPeers = ScannerRecordingPeers()
            val scannerPeers = ScannerRecordingPeers()
            // The impostor listens; the QR the scanner holds pins the real inviter's certificate.
            val listener = JvmPairingInvitationListener(scope, impostor, JvmPairingIdentityProof(impostor), approvals, inviterPeers)
            val served = listener.start("127.0.0.1", "Inviter")
            val tampered = served.copy(initiatorFingerprint = inviter.certificateFingerprint)

            val result = JvmPairingScannerSession(scanner, scannerPeers).connectAndConfirm(tampered, "Scanner", localCodeConfirmed = true)

            val rejected = assertIs<PairingScannerResult.Rejected>(result)
            assertEquals(true, rejected.message.contains("didn't match"))
            assertEquals(emptyList(), scannerPeers.peers.value)
            assertEquals(emptyList(), approvals.pending.value)
            listener.close()
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }
}

private class ScannerRecordingPeers : TrustedPeerRepository, PairingRecoveryStore {
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
