package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ResumeEntry
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferManifestEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferRuntimeTest {
    @Test
    fun `state machine permits verified completion only after transfer`() {
        assertTrue(TransferStateMachine.canTransition(TransferState.DRAFT, TransferState.REQUESTED))
        assertTrue(TransferStateMachine.canTransition(TransferState.TRANSFERRING, TransferState.VERIFYING))
        assertTrue(TransferStateMachine.canTransition(TransferState.VERIFYING, TransferState.COMPLETED))
        assertFalse(TransferStateMachine.canTransition(TransferState.TRANSFERRING, TransferState.COMPLETED))
    }

    @Test
    fun `benchmark source processes an exact byte count`() {
        val result = StreamBenchmark.copyToBlackHole(totalBytes = 1_025, bufferSize = 256)
        assertEquals(1_025, result.bytesProcessed)
    }
}

class ManualPeerEndpointParserTest {
    @Test
    fun `parses hostname IPv4 and bracketed IPv6 endpoints`() {
        assertEquals(
            PeerEndpoint("zynk.local", 4444),
            assertIs<ManualEndpointParseResult.Valid>(ManualPeerEndpointParser.parse("zynk.local:4444")).endpoint,
        )
        assertEquals(
            PeerEndpoint("192.168.1.12", 65535),
            assertIs<ManualEndpointParseResult.Valid>(ManualPeerEndpointParser.parse("192.168.1.12:65535")).endpoint,
        )
        assertEquals(
            PeerEndpoint("fe80::1", 4040),
            assertIs<ManualEndpointParseResult.Valid>(ManualPeerEndpointParser.parse("[fe80::1]:4040")).endpoint,
        )
    }

    @Test
    fun `rejects malformed hosts ambiguous IPv6 and invalid ports`() {
        assertIs<ManualEndpointParseResult.Invalid>(ManualPeerEndpointParser.parse("host"))
        assertIs<ManualEndpointParseResult.Invalid>(ManualPeerEndpointParser.parse("2001:db8::1:4040"))
        assertIs<ManualEndpointParseResult.Invalid>(ManualPeerEndpointParser.parse("256.1.1.1:4040"))
        assertIs<ManualEndpointParseResult.Invalid>(ManualPeerEndpointParser.parse("host:0"))
    }
}

class TransferProgressTest {
    @Test
    fun `calculates speed and ETA from measured transfer data`() {
        val progress = TransferProgress(transferredBytes = 500, totalBytes = 1_000, elapsedMillis = 1_000)

        assertEquals(0.5f, progress.fraction)
        assertEquals(500.0, progress.bytesPerSecond)
        assertEquals(1_000, progress.estimatedRemainingMillis)
    }
}

class ResumeNegotiatorTest {
    @Test
    fun `accepts only unique offsets that fit their manifest entry`() {
        val manifest = TransferManifest("transfer-1", listOf(TransferManifestEntry("file.txt", 100, "a".repeat(64))))

        val accepted = ResumeNegotiator.acceptedEntries(
            manifest,
            listOf(ResumeEntry("file.txt", 50, "b".repeat(64))),
        )
        val rejected = ResumeNegotiator.acceptedEntries(
            manifest,
            listOf(
                ResumeEntry("file.txt", 101, "b".repeat(64)),
                ResumeEntry("file.txt", 50, "b".repeat(64)),
            ),
        )

        assertEquals(50, accepted.single().verifiedBytes)
        assertTrue(rejected.isEmpty())
    }
}

class ProgressPublishingTest {
    @Test
    fun `given rapid progress when publishing then only the first, interval-spaced and final updates pass`() {
        val total = 1_000L
        kotlin.test.assertTrue(shouldPublishProgress(null, TransferProgress(16, total, 0)))
        kotlin.test.assertFalse(shouldPublishProgress(0, TransferProgress(32, total, 40)))
        kotlin.test.assertTrue(shouldPublishProgress(0, TransferProgress(500, total, 100)))
        kotlin.test.assertTrue(shouldPublishProgress(100, TransferProgress(total, total, 120)))
    }
}
