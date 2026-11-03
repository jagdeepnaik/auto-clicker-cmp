package com.jagdeep.transfer.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingApprovalCoordinatorTest {
    @Test
    fun `given an incoming pairing request when user accepts then request resolves and is removed`() = runTest {
        val coordinator = PairingApprovalCoordinator()
        val request = request()
        val decision = async { coordinator.awaitDecision(request) }
        runCurrent()
        assertEquals(listOf(request), coordinator.pending.value)
        assertTrue(coordinator.decide("session-1", true))
        assertTrue(decision.await())
        assertEquals(emptyList(), coordinator.pending.value)
    }

    @Test
    fun `given no active pairing request when deciding then decision is rejected`() = runTest {
        assertFalse(PairingApprovalCoordinator().decide("missing", true))
    }

    private fun request() = IncomingPairingRequest("session-1", TrustedPeer("peer", "Peer", "a".repeat(64), byteArrayOf(1)), "482731")
}
