package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReciprocalPairingSessionTest {
    @Test
    fun `given matching bilateral confirmations and acknowledgement when processed then peer becomes paired`() = runTest {
        val peers = RecordingPeers()
        val session = session(peers)
        val peer = peer()
        assertIs<ReciprocalPairingState.AwaitingLocalConfirmation>(session.receiveVerifiedPeer(peer, "482731"))
        assertIs<ReciprocalPairingState.AwaitingRemoteConfirmation>(session.confirmLocally(true))
        assertIs<ReciprocalPairingState.AwaitingAcknowledgement>(session.receiveRemoteConfirmation(confirm(true)))
        assertEquals(listOf(peer), peers.peers.value)
        assertEquals(ReciprocalPairingState.Paired(peer), session.receiveAcknowledgement(ack()))
    }

    @Test
    fun `given mismatched transcript when remote confirms then no trust is saved`() = runTest {
        val peers = RecordingPeers()
        val session = session(peers)
        session.receiveVerifiedPeer(peer(), "482731")
        session.confirmLocally(true)
        assertIs<ReciprocalPairingState.Rejected>(session.receiveRemoteConfirmation(confirm(true, "b".repeat(64))))
        assertEquals(emptyList(), peers.peers.value)
    }

    @Test
    fun `given acknowledgement before bilateral consent when processed then no trust is saved`() = runTest {
        val peers = RecordingPeers()
        val session = session(peers)
        assertIs<ReciprocalPairingState.Rejected>(session.receiveAcknowledgement(ack()))
        assertEquals(emptyList(), peers.peers.value)
    }

    @Test
    fun `given remote rejection when processed then no trust is saved`() = runTest {
        val peers = RecordingPeers()
        val session = session(peers)
        session.receiveVerifiedPeer(peer(), "482731")
        session.confirmLocally(true)
        assertIs<ReciprocalPairingState.Rejected>(session.receiveRemoteConfirmation(confirm(false)))
        assertEquals(emptyList(), peers.peers.value)
    }

    @Test
    fun `given an expired session when a peer arrives then no trust is saved`() = runTest {
        val peers = RecordingPeers()
        val session = ReciprocalPairingSession(
            sessionId = "session-1",
            role = PairingRole.INITIATOR,
            transcriptHash = "a".repeat(64),
            trustedPeers = peers,
            expiresAtEpochMillis = 100,
            nowEpochMillis = { 100 },
        )
        assertIs<ReciprocalPairingState.Incomplete>(session.receiveVerifiedPeer(peer(), "482731"))
        assertEquals(emptyList(), peers.peers.value)
    }

    private fun session(peers: RecordingPeers) = ReciprocalPairingSession(
        sessionId = "session-1",
        role = PairingRole.INITIATOR,
        transcriptHash = "a".repeat(64),
        trustedPeers = peers,
        expiresAtEpochMillis = 200,
        nowEpochMillis = { 100 },
    )
    private fun confirm(accepted: Boolean, hash: String = "a".repeat(64)) = ReciprocalPairingConfirmation("session-1", hash, accepted)
    private fun ack() = ReciprocalPairingAcknowledgement("session-1", "a".repeat(64))
    private fun peer() = TrustedPeer("peer", "Peer", "a".repeat(64), byteArrayOf(1))

    private class RecordingPeers : TrustedPeerRepository {
        private val mutablePeers = MutableStateFlow<List<TrustedPeer>>(emptyList())
        override val peers: StateFlow<List<TrustedPeer>> = mutablePeers
        override suspend fun upsertVerified(peer: TrustedPeer) { mutablePeers.value = mutablePeers.value + peer }
        override suspend fun remove(deviceId: String) = Unit
        override suspend fun findByFingerprint(fingerprint: String): TrustedPeer? = mutablePeers.value.firstOrNull { it.certificateFingerprint == fingerprint }
    }
}
