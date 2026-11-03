package com.jagdeep.transfer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncManifestCodecTest {
    private val entry = TransferManifestEntry("a.txt", 3, "a".repeat(64))

    @Test
    fun `given an ordinary transfer when encoded then it carries no sync field, so older peers still accept it`() {
        val bytes = ControlMessageCodec.encode(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, "r1", TransferManifest("t1", listOf(entry))))
        assertFalse("sync" in bytes.decodeToString())
    }

    @Test
    fun `given a sync transfer when encoded and decoded then the target and removals round-trip`() {
        val manifest = TransferManifest("t1", listOf(entry), SyncTarget("pair-1", "Photos", listOf("old/b.jpg")))
        val decoded = ControlMessageCodec.decode(ControlMessageCodec.encode(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, "r1", manifest)))
        assertEquals(manifest, (decoded as ControlMessage.TransferRequest).manifest)
        assertTrue("sync" in ControlMessageCodec.encode(decoded).decodeToString())
    }

    @Test
    fun `given a Mirror sync that only removes files when built then an empty file list is allowed, but not for ordinary transfers`() {
        TransferManifest("t1", emptyList(), SyncTarget("pair-1", "Photos", listOf("gone.txt")))
        assertFailsWith<IllegalArgumentException> { TransferManifest("t1", emptyList()) }
    }

    @Test
    fun `given unsafe sync names or removal paths when built then they are rejected`() {
        assertFailsWith<IllegalArgumentException> { SyncTarget("p", "../escape") }
        assertFailsWith<IllegalArgumentException> { SyncTarget("p", "a/b") }
        assertFailsWith<IllegalArgumentException> { SyncTarget("p", ".zynk-trash") }
        assertFailsWith<IllegalArgumentException> { SyncTarget("p", "Photos", listOf("../../etc/passwd")) }
        assertFailsWith<IllegalArgumentException> { SyncTarget("p", "Photos", listOf("/abs")) }
    }
}
