package com.jagdeep.network.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeerDiscoveryServiceTest {
    @Test
    fun `rejects an invalid discovery endpoint`() {
        assertFailsWith<IllegalArgumentException> { DiscoveryEndpoint("192.168.1.8", 0) }
    }

    @Test
    fun `unsupported service never reports discovered peers`() {
        val service = UnsupportedPeerDiscoveryService()
        assertEquals(PeerDiscoveryStatus.Unsupported, service.status.value)
        assertEquals(emptyList(), service.peers.value)
    }

    @Test
    fun `merges transport sightings for the same fingerprint and retains the selected identity`() {
        val mdnsPeer = DiscoveredPeer(
            id = "Phone@192.168.1.8",
            displayName = "Phone",
            platform = "Android",
            protocolVersion = 2,
            endpoint = null,
            publicKeyFingerprint = "fingerprint-1",
        )
        val udpPeer = mdnsPeer.copy(
            id = "Phone@192.168.1.42",
            endpoint = DiscoveryEndpoint("192.168.1.42", 4040),
        )

        val peers = listOf(mdnsPeer).record(udpPeer)

        assertEquals(1, peers.size)
        assertEquals(mdnsPeer.id, peers.single().id)
        assertEquals(udpPeer.endpoint, peers.single().endpoint)
    }

    @Test
    fun `merges unpaired transport sightings by their shared node identity`() {
        val mdnsPeer = DiscoveredPeer(
            id = "node-123",
            displayName = "Phone",
            platform = "Android",
            protocolVersion = 2,
            endpoint = null,
            publicKeyFingerprint = null,
        )
        val udpPeer = mdnsPeer.copy(endpoint = DiscoveryEndpoint("192.168.1.42", 4040))

        val peers = listOf(mdnsPeer).record(udpPeer)

        assertEquals(listOf(udpPeer), peers)
    }

    @Test
    fun `does not merge different peers when fingerprints differ`() {
        val first = DiscoveredPeer("first", "First", "Android", 2, null, "fingerprint-1")
        val second = DiscoveredPeer("second", "Second", "Desktop", 2, null, "fingerprint-2")

        assertEquals(listOf(first, second), listOf(first).record(second))
    }
}
