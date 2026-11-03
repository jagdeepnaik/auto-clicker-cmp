package com.jagdeep.transfer.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TransferProtocolTest {
    private val fingerprint = "a".repeat(TransferProtocol.SHA_256_HEX_LENGTH)

    @Test
    fun `control messages round trip through JSON`() {
        val message: ControlMessage = ControlMessage.Hello(
            protocolVersion = TransferProtocol.CURRENT_VERSION,
            requestId = "request-1",
            peer = PeerIdentity("desktop-1", "Desktop", fingerprint),
        )

        val encoded = ControlMessageCodec.encode(message)
        val decoded = ControlMessageCodec.decode(encoded)

        assertEquals(message, decoded)
        assertNull(ProtocolValidation.validate(decoded))
    }

    @Test
    fun `completion control message round trips through canonical codec`() {
        val message: ControlMessage = ControlMessage.TransferComplete(
            protocolVersion = TransferProtocol.CURRENT_VERSION,
            requestId = "complete-1",
            transferId = "transfer-1",
            bytesTransferred = 42,
        )

        assertEquals(message, ControlMessageCodec.decode(ControlMessageCodec.encode(message)))
    }

    @Test
    fun `resume control message round trips through canonical codec`() {
        val message: ControlMessage = ControlMessage.ResumeRequest(
            protocolVersion = TransferProtocol.CURRENT_VERSION,
            requestId = "resume-1",
            transferId = "transfer-1",
            entries = listOf(ResumeEntry("nested/file.txt", 42, "b".repeat(64))),
        )

        assertEquals(message, ControlMessageCodec.decode(ControlMessageCodec.encode(message)))
    }

    @Test
    fun `unsafe relative paths are rejected`() {
        assertFalse(ProtocolValidation.isSafeRelativePath("../private.txt"))
        assertFalse(ProtocolValidation.isSafeRelativePath("C:/private.txt"))
        assertFalse(ProtocolValidation.isSafeRelativePath("folder\\private.txt"))
        assertTrue(ProtocolValidation.isSafeRelativePath("photos/2026/picture.jpg"))
    }

    @Test
    fun `manifest rejects an aggregate size that overflows`() {
        assertFailsWith<IllegalArgumentException> {
            TransferManifest(
                "transfer-1",
                listOf(
                    TransferManifestEntry("first.bin", Long.MAX_VALUE, fingerprint),
                    TransferManifestEntry("second.bin", 1, fingerprint),
                ),
            )
        }
    }
}
