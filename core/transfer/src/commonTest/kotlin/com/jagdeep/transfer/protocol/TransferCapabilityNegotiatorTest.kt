package com.jagdeep.transfer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferCapabilityNegotiatorTest {
    @Test
    fun `given both peers advertise resume when negotiated then resume is supported`() {
        val local = peer(setOf(TransferCapability.RESUMABLE_MANIFESTS))
        val remote = peer(setOf(TransferCapability.RESUMABLE_MANIFESTS), "remote")

        assertTrue(TransferCapabilityNegotiator.supports(local, remote, TransferCapability.RESUMABLE_MANIFESTS))
    }

    @Test
    fun `given only one peer advertises resume when negotiated then resume is unavailable`() {
        val local = peer(setOf(TransferCapability.RESUMABLE_MANIFESTS))
        val remote = peer(emptySet(), "remote")

        assertFalse(TransferCapabilityNegotiator.supports(local, remote, TransferCapability.RESUMABLE_MANIFESTS))
        assertEquals(emptySet(), TransferCapabilityNegotiator.mutuallySupported(local, remote))
    }

    private fun peer(capabilities: Set<TransferCapability>, id: String = "local") = PeerIdentity(
        deviceId = id,
        displayName = id,
        publicKeyFingerprint = "a".repeat(64),
        capabilities = capabilities,
    )
}
