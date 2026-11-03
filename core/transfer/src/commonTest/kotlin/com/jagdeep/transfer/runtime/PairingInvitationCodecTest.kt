package com.jagdeep.transfer.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class PairingInvitationCodecTest {
    @Test
    fun `pairing invitation round trips without exposing a mutable endpoint`() {
        val invitation = PairingInvitation("device-1", "Zynk-Mac", "a".repeat(64), byteArrayOf(1, 2, 3, 4))

        val decoded = PairingInvitationCodec.decode(PairingInvitationCodec.encode(invitation))

        assertEquals(invitation.deviceId, decoded.deviceId)
        assertEquals(invitation.displayName, decoded.displayName)
        assertEquals(invitation.certificateFingerprint, decoded.certificateFingerprint)
        assertContentEquals(invitation.certificateDer, decoded.certificateDer)
    }

    @Test
    fun `invalid pairing payload is rejected`() {
        assertFailsWith<IllegalArgumentException> { PairingInvitationCodec.decode("zynk-pair:v1:bad") }
    }
}
