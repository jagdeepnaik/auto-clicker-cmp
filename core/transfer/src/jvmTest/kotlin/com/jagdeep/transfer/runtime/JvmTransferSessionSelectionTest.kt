package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferCapability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmTransferSessionSelectionTest {
    @Test
    fun `given two resume-capable desktop peers when selecting a session then use resumable manifests`() {
        assertTrue(shouldUseResumableManifests(resumablePeer("local"), resumablePeer("remote")))
    }

    @Test
    fun `given peer without resume capability when selecting a session then retain compatible single-file transfer`() {
        assertFalse(shouldUseResumableManifests(resumablePeer("desktop"), peer("android")))
    }

    @Test
    fun `given desktop and android batch peers when selecting a session then use one batch manifest without resume`() {
        val desktop = peer("desktop", setOf(TransferCapability.RESUMABLE_MANIFESTS, TransferCapability.BATCH_MANIFESTS))
        val android = peer("android", setOf(TransferCapability.BATCH_MANIFESTS))

        assertTrue(shouldUseBatchManifests(desktop, android))
        assertFalse(shouldUseResumableManifests(desktop, android))
    }

    private fun resumablePeer(id: String) = peer(id, setOf(TransferCapability.RESUMABLE_MANIFESTS))

    private fun peer(id: String, capabilities: Set<TransferCapability> = emptySet()) = PeerIdentity(
        deviceId = id,
        displayName = "Zynk-$id",
        publicKeyFingerprint = "a".repeat(64),
        capabilities = capabilities,
    )
}
