package com.jagdeep.transfer.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingConnectionInvitationCodecTest {
    private val invitation = PairingConnectionInvitation(
        sessionId = PairingConnectionInvitationCodec.sessionIdFrom(ByteArray(16) { it.toByte() }),
        expiresAtEpochMillis = 1_761_700_000_000,
        host = "192.168.1.20",
        port = 41234,
        initiatorDeviceId = "desktop-0123456789abcdef01234567",
        initiatorDisplayName = "Jagdeep’s MacBook Pro",
        initiatorFingerprint = "0f".repeat(32),
        initiatorNonce = ByteArray(16) { 7 },
    )

    @Test
    fun `given a v3 invitation when encoded and decoded then every field round trips`() {
        val decoded = PairingConnectionInvitationCodec.decode(PairingConnectionInvitationCodec.encode(invitation))
        assertEquals(invitation.sessionId, decoded.sessionId)
        assertEquals(invitation.expiresAtEpochMillis, decoded.expiresAtEpochMillis)
        assertEquals(invitation.host, decoded.host)
        assertEquals(invitation.port, decoded.port)
        assertEquals(invitation.initiatorDeviceId, decoded.initiatorDeviceId)
        assertEquals(invitation.initiatorDisplayName, decoded.initiatorDisplayName)
        assertEquals(invitation.initiatorFingerprint, decoded.initiatorFingerprint)
        assertContentEquals(invitation.initiatorNonce, decoded.initiatorNonce)
    }

    @Test
    fun `given a typical invitation when encoded then it stays small enough for a low-density QR`() {
        val payload = PairingConnectionInvitationCodec.encode(invitation)
        assertTrue(payload.length <= 213, "v3 payload is ${payload.length} characters; QR version 10-M holds 213 bytes")
    }

    @Test
    fun `given surrounding whitespace from a scanner when decoded then the code is still accepted`() {
        val decoded = PairingConnectionInvitationCodec.decode("  ${PairingConnectionInvitationCodec.encode(invitation)}\n")
        assertEquals(invitation.sessionId, decoded.sessionId)
    }

    @Test
    fun `given an older pairing code when decoded then an update is required with no downgrade`() {
        val v2 = assertFailsWith<IllegalArgumentException> { PairingConnectionInvitationCodec.decode("zynk-pair:v2:abc.def") }
        assertTrue(v2.message!!.contains("Update Zynk"))
        assertFailsWith<IllegalArgumentException> { PairingConnectionInvitationCodec.decode("zynk-pair:v1:legacy") }
    }

    @Test
    fun `given a truncated or foreign code when decoded then it is rejected`() {
        val payload = PairingConnectionInvitationCodec.encode(invitation)
        assertFailsWith<IllegalArgumentException> { PairingConnectionInvitationCodec.decode(payload.substringBeforeLast('.')) }
        assertFailsWith<IllegalArgumentException> { PairingConnectionInvitationCodec.decode("4006381333931") }
    }
}
