package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PairingCoordinatorTest {
    @Test
    fun `given verified invitation when user confirms then peer is saved`() = runTest {
        val peer = peer()
        val repository = RecordingTrustedPeerRepository()
        val coordinator = PairingCoordinator(verifiedVerifier(peer), repository)
        val payload = PairingInvitationCodec.encode(PairingInvitation(peer.deviceId, peer.displayName, peer.certificateFingerprint, peer.certificateDer))

        val prepared = assertIs<PairingPreparation.ReadyForConfirmation>(coordinator.prepare(payload))
        val result = coordinator.commit(prepared, userConfirmed = true)

        assertIs<PairingCommitResult.Paired>(result)
        assertEquals(listOf(peer), repository.peers.value)
    }

    @Test
    fun `given verified invitation when user declines then peer is not saved`() = runTest {
        val peer = peer()
        val repository = RecordingTrustedPeerRepository()
        val coordinator = PairingCoordinator(verifiedVerifier(peer), repository)
        val payload = PairingInvitationCodec.encode(PairingInvitation(peer.deviceId, peer.displayName, peer.certificateFingerprint, peer.certificateDer))

        val prepared = assertIs<PairingPreparation.ReadyForConfirmation>(coordinator.prepare(payload))
        assertIs<PairingCommitResult.Cancelled>(coordinator.commit(prepared, userConfirmed = false))
        assertEquals(emptyList(), repository.peers.value)
    }

    private fun peer() = TrustedPeer("device-1", "Zynk-Mac", "a".repeat(64), byteArrayOf(1))

    private fun verifiedVerifier(peer: TrustedPeer) = object : PairingVerifier {
        override fun verify(invitation: PairingInvitation): PairingVerificationResult = PairingVerificationResult.Verified(peer)
    }
}

private class RecordingTrustedPeerRepository : TrustedPeerRepository {
    private val mutablePeers = MutableStateFlow<List<TrustedPeer>>(emptyList())
    override val peers = mutablePeers

    override suspend fun upsertVerified(peer: TrustedPeer) {
        mutablePeers.value = mutablePeers.value.filterNot { it.deviceId == peer.deviceId } + peer
    }

    override suspend fun remove(deviceId: String) {
        mutablePeers.value = mutablePeers.value.filterNot { it.deviceId == deviceId }
    }

    override suspend fun findByFingerprint(fingerprint: String): TrustedPeer? =
        mutablePeers.value.firstOrNull { it.certificateFingerprint == fingerprint }
}
