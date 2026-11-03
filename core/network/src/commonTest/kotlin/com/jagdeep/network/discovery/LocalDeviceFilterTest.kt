package com.jagdeep.network.discovery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalDeviceFilterTest {
    private val ownFingerprint = "a".repeat(64)

    private fun sighting(id: String, fingerprint: String?) =
        DiscoveredPeer(id, "Phone", "Android", 1, DiscoveryEndpoint("192.168.1.10", 4040), fingerprint)

    @Test
    fun `given this session's node id when sighted then it is the local device`() {
        assertTrue(sighting("node-now", null).isLocalDevice("node-now", ownFingerprint))
    }

    @Test
    fun `given an earlier session's advertisement with our fingerprint when sighted then it is still the local device`() {
        assertTrue(sighting("node-before-recreate", ownFingerprint).isLocalDevice("node-now", ownFingerprint))
    }

    @Test
    fun `given another device or an unknown local fingerprint when sighted then it is not filtered`() {
        assertFalse(sighting("node-mac", "b".repeat(64)).isLocalDevice("node-now", ownFingerprint))
        assertFalse(sighting("node-mac", null).isLocalDevice("node-now", ownFingerprint))
        assertFalse(sighting("node-mac", "b".repeat(64)).isLocalDevice("node-now", null))
    }
}
