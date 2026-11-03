package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferManifestEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransferApprovalCoordinatorTest {
    @Test
    fun `given receiver takes thirty seconds when accepting then default approval remains pending`() = runTest {
        val coordinator = TransferApprovalCoordinator()
        val request = request()
        val waiting = async { coordinator.awaitDecision(request) }
        testScheduler.runCurrent()
        advanceTimeBy(30_000)
        assertEquals(listOf(request), coordinator.pending.value)
        assertTrue(coordinator.decide(request.requestId, true))
        assertTrue(waiting.await())
        assertTrue(TransferTimeouts.SESSION_READ_MILLIS > TransferTimeouts.APPROVAL_MILLIS)
    }

    @Test
    fun `given a pending request when receiver accepts then listener proceeds`() = runTest {
        val coordinator = TransferApprovalCoordinator()
        val request = request()
        val waiting = async { coordinator.awaitDecision(request) }
        testScheduler.runCurrent()

        assertEquals(listOf(request), coordinator.pending.value)
        assertTrue(coordinator.decide(request.requestId, accepted = true))
        assertTrue(waiting.await())
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun `given a pending request when receiver declines then listener does not proceed`() = runTest {
        val coordinator = TransferApprovalCoordinator()
        val request = request()
        val waiting = async { coordinator.awaitDecision(request) }
        testScheduler.runCurrent()

        assertTrue(coordinator.decide(request.requestId, accepted = false))
        assertFalse(waiting.await())
    }

    @Test
    fun `given an unanswered request when approval times out then listener rejects and clears it`() = runTest {
        val coordinator = TransferApprovalCoordinator()
        val waiting = async { coordinator.awaitDecision(request(), timeoutMillis = 1_000) }
        testScheduler.runCurrent()

        advanceTimeBy(1_000)

        assertFalse(waiting.await())
        assertTrue(coordinator.pending.value.isEmpty())
    }

    private fun request() = IncomingTransferRequest(
        requestId = "request-1",
        sender = PeerIdentity("sender", "Zynk-Mac", "a".repeat(64)),
        manifest = TransferManifest("transfer-1", listOf(TransferManifestEntry("photo.jpg", 42, "b".repeat(64)))),
    )
}
