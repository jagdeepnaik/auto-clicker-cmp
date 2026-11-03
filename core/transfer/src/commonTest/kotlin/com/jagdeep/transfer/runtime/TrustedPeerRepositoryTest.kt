package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustedPeerRepositoryTest {
    @Test
    fun `given a verified replacement when saved then the prior record is replaced`() = runTest {
        val repository = FakeTrustedPeerRepository()
        val first = peer("device-1", "a")
        val replacement = peer("device-1", "b")

        repository.upsertVerified(first)
        repository.upsertVerified(replacement)

        assertEquals(listOf(replacement), repository.peers.first())
        assertEquals(replacement, repository.findByFingerprint(replacement.certificateFingerprint))
    }

    @Test
    fun `given a removed peer when looked up then it is absent`() = runTest {
        val repository = FakeTrustedPeerRepository()
        repository.upsertVerified(peer("device-1", "a"))

        repository.remove("device-1")

        assertNull(repository.findByFingerprint("a".repeat(64)))
    }

    private fun peer(id: String, fingerprintCharacter: String) = TrustedPeer(
        deviceId = id,
        displayName = "Zynk-$id",
        certificateFingerprint = fingerprintCharacter.repeat(64),
        certificateDer = byteArrayOf(1, 2, 3),
    )
}

private class FakeTrustedPeerRepository : TrustedPeerRepository {
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
