package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class TrustedPeerAuthorizerTest {
    @Test
    fun `given paired certificate and matching device when authorized then peer is accepted`() = runTest {
        val repository = InMemoryTrustedPeers(peer("device-1", "a"))

        val result = TrustedPeerAuthorizer(repository).authorize("a".repeat(64), identity("device-1", "a"))

        assertIs<TrustedPeerAuthorization.Authorized>(result)
    }

    @Test
    fun `given a certificate not saved during pairing when authorized then peer is rejected`() = runTest {
        val result = TrustedPeerAuthorizer(InMemoryTrustedPeers()).authorize("b".repeat(64), identity("device-1", "b"))

        assertIs<TrustedPeerAuthorization.Rejected>(result)
    }

    @Test
    fun `given a claimed device id that differs from pairing when authorized then peer is rejected`() = runTest {
        val result = TrustedPeerAuthorizer(InMemoryTrustedPeers(peer("device-1", "a")))
            .authorize("a".repeat(64), identity("device-2", "a"))

        assertIs<TrustedPeerAuthorization.Rejected>(result)
    }

    @Test
    fun `given a control identity that differs from its tls certificate when authorized then peer is rejected`() = runTest {
        val result = TrustedPeerAuthorizer(InMemoryTrustedPeers(peer("device-1", "a")))
            .authorize("a".repeat(64), identity("device-1", "b"))

        assertIs<TrustedPeerAuthorization.Rejected>(result)
    }

    private fun peer(deviceId: String, fingerprintCharacter: String) = TrustedPeer(
        deviceId = deviceId,
        displayName = "Zynk device",
        certificateFingerprint = fingerprintCharacter.repeat(64),
        certificateDer = byteArrayOf(1),
    )

    private fun identity(deviceId: String, fingerprintCharacter: String) = PeerIdentity(
        deviceId = deviceId,
        displayName = "Zynk device",
        publicKeyFingerprint = fingerprintCharacter.repeat(64),
    )
}

private class InMemoryTrustedPeers(vararg initial: TrustedPeer) : TrustedPeerRepository {
    private val state = MutableStateFlow(initial.toList())
    override val peers = state

    override suspend fun upsertVerified(peer: TrustedPeer) {
        state.value = state.value.filterNot { it.deviceId == peer.deviceId } + peer
    }

    override suspend fun remove(deviceId: String) {
        state.value = state.value.filterNot { it.deviceId == deviceId }
    }

    override suspend fun findByFingerprint(fingerprint: String): TrustedPeer? =
        state.value.firstOrNull { it.certificateFingerprint == fingerprint }
}
