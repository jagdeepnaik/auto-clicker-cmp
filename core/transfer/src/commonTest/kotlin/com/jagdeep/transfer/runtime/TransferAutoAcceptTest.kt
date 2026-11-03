package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferManifestEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class TransferAutoAcceptTest {
    private val request = IncomingTransferRequest(
        "r1",
        PeerIdentity("desktop-a", "Office PC", "a".repeat(64)),
        TransferManifest("t1", listOf(TransferManifestEntry("photo.jpg", 10, "b".repeat(64)))),
    )

    @Test
    fun `given an allowed device when a request arrives then it is accepted without a prompt and announced`() = runTest {
        val coordinator = TransferApprovalCoordinator(autoAccept = { true })
        val announced = async { coordinator.autoAccepted.first() }
        kotlinx.coroutines.yield()

        assertTrue(coordinator.awaitDecision(request))
        assertEquals(emptyList(), coordinator.pending.value)
        assertEquals("r1", announced.await().requestId)
    }

    @Test
    fun `given a device that is not allowed when a request arrives then the user is asked`() = runTest {
        val coordinator = TransferApprovalCoordinator(autoAccept = { false })
        val decision = async { coordinator.awaitDecision(request) }
        kotlinx.coroutines.yield()
        assertEquals(listOf("r1"), coordinator.pending.value.map { it.requestId })
        coordinator.decide("r1", accepted = false)
        assertFalse(decision.await())
    }

    @Test
    fun `given a failing policy when a request arrives then it falls back to asking, never accepting`() = runTest {
        val coordinator = TransferApprovalCoordinator(autoAccept = { error("storage unavailable") })
        val decision = async { coordinator.awaitDecision(request) }
        kotlinx.coroutines.yield()
        assertEquals(listOf("r1"), coordinator.pending.value.map { it.requestId })
        coordinator.decide("r1", accepted = true)
        assertTrue(decision.await())
    }
}
