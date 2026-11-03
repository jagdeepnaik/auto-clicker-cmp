package com.jagdeep.transfer.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairingWireMessageCodecTest {
    @Test
    fun `given a signed proof message when encoded and decoded then values round trip`() {
        val message = PairingWireMessage.IdentityProof(
            PairingProtocol.CURRENT_VERSION,
            PairingIdentityProof(
                PairingInvitation("device", "Zynk", "a".repeat(64), byteArrayOf(1, 2)),
                "session-1", "b".repeat(64), ByteArray(64) { 1 },
            ),
        )
        val decoded = PairingWireMessageCodec.decode(PairingWireMessageCodec.encode(message)) as PairingWireMessage.IdentityProof
        assertEquals(message.version, decoded.version)
        assertEquals(message.proof.sessionId, decoded.proof.sessionId)
        assertEquals(message.proof.transcriptHash, decoded.proof.transcriptHash)
        assertEquals(message.proof.signature.toList(), decoded.proof.signature.toList())
    }

    @Test
    fun `given an old protocol message when encoded then it is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PairingWireMessageCodec.encode(PairingWireMessage.Rejection(1, "session-1", "no"))
        }
    }
}
