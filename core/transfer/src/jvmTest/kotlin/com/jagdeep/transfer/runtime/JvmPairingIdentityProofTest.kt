package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs

class JvmPairingIdentityProofTest {
    @Test
    fun `given a fresh signed proof when verified then peer identity is accepted`() {
        withIdentity { identity ->
            val proof = JvmPairingIdentityProof(identity).create("session-1", "a".repeat(64), "Mac")
            assertIs<PairingIdentityProofResult.Verified>(JvmPairingIdentityProof(identity).verify(proof))
        }
    }

    @Test
    fun `given a changed transcript when verified then proof is rejected`() {
        withIdentity { identity ->
            val original = JvmPairingIdentityProof(identity).create("session-1", "a".repeat(64), "Mac")
            val altered = original.copy(transcriptHash = "b".repeat(64))
            assertIs<PairingIdentityProofResult.Rejected>(JvmPairingIdentityProof(identity).verify(altered))
        }
    }

    private fun withIdentity(block: (JvmTlsIdentity) -> Unit) {
        val directory = Files.createTempDirectory("zynk-pairing-proof")
        try { block(JvmPeerIdentityStore(directory.resolve("identity.p12")).create("test-password".toCharArray())) }
        finally { directory.toFile().deleteRecursively() }
    }
}
