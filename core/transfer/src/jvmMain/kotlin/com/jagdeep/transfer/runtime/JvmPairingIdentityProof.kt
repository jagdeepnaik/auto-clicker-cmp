package com.jagdeep.transfer.runtime

import java.security.Signature

class JvmPairingIdentityProof(
    private val identity: JvmTlsIdentity,
) : PairingIdentityProofVerifier {
    fun create(sessionId: String, transcriptHash: String, displayName: String): PairingIdentityProof {
        val invitation = PairingInvitation(
            deviceId = "desktop-${identity.certificateFingerprint.take(24)}",
            displayName = displayName,
            certificateFingerprint = identity.certificateFingerprint,
            certificateDer = identity.certificate.encoded,
        )
        val bytes = PairingProofTranscript.bytes(sessionId, transcriptHash, invitation)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(identity.privateKey)
            update(bytes)
        }.sign()
        return PairingIdentityProof(invitation, sessionId, transcriptHash, signature)
    }

    override fun verify(proof: PairingIdentityProof): PairingIdentityProofResult = runCatching {
        val peer = when (val verified = JvmPairingVerifier().verify(proof.invitation)) {
            is PairingVerificationResult.Verified -> verified.peer
            is PairingVerificationResult.Rejected -> error(verified.message)
        }
        val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(proof.invitation.certificateDer.inputStream())
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(certificate.publicKey)
            update(PairingProofTranscript.bytes(proof.sessionId, proof.transcriptHash, proof.invitation))
        }
        require(signature.verify(proof.signature)) { "The device did not prove ownership of its pairing identity." }
        peer
    }.fold(
        onSuccess = PairingIdentityProofResult::Verified,
        onFailure = { PairingIdentityProofResult.Rejected("The pairing identity proof could not be verified.") },
    )

    private companion object { const val SIGNATURE_ALGORITHM = "SHA256withECDSA" }
}
