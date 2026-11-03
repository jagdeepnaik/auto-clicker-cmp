package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JvmPairingVerifierTest {
    @Test
    fun `given a valid invitation when verified then it produces a trusted peer`() {
        val directory = Files.createTempDirectory("zynk-pairing")
        val password = "test-only-secret".toCharArray()
        try {
            val identity = JvmPeerIdentityStore(directory.resolve("identity.p12")).create(password)
            val invitation = PairingInvitation(
                deviceId = "desktop-1",
                displayName = "Zynk-Mac",
                certificateFingerprint = identity.certificateFingerprint,
                certificateDer = identity.certificate.encoded,
            )

            val result = assertIs<PairingVerificationResult.Verified>(JvmPairingVerifier().verify(invitation))

            assertEquals(identity.certificateFingerprint, result.peer.certificateFingerprint)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            password.fill('\u0000')
        }
    }

    @Test
    fun `given a mismatched fingerprint when verified then it is rejected`() {
        val invitation = PairingInvitation("device", "Zynk", "a".repeat(64), byteArrayOf(1, 2, 3))
        assertIs<PairingVerificationResult.Rejected>(JvmPairingVerifier().verify(invitation))
    }
}
