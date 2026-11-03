package com.jagdeep.transfer.runtime

import java.security.Signature
import java.security.cert.CertificateFactory

class AndroidPairingIdentityProof(
    private val identity: AndroidTlsIdentity,
) : PairingIdentityProofVerifier {
    fun create(sessionId: String, transcriptHash: String, displayName: String): PairingIdentityProof {
        val invitation = PairingInvitation(
            deviceId = "android-${identity.certificateFingerprint.take(24)}",
            displayName = displayName,
            certificateFingerprint = identity.certificateFingerprint,
            certificateDer = identity.certificate.encoded,
        )
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(identity.privateKey)
            update(PairingProofTranscript.bytes(sessionId, transcriptHash, invitation))
        }.sign()
        return PairingIdentityProof(invitation, sessionId, transcriptHash, signature)
    }

    override fun verify(proof: PairingIdentityProof): PairingIdentityProofResult = runCatching {
        val peer = when (val verified = AndroidPairingVerifier().verify(proof.invitation)) {
            is PairingVerificationResult.Verified -> verified.peer
            is PairingVerificationResult.Rejected -> error(verified.message)
        }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(proof.invitation.certificateDer.inputStream())
        val verified = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(certificate.publicKey)
            update(PairingProofTranscript.bytes(proof.sessionId, proof.transcriptHash, proof.invitation))
        }.verify(proof.signature)
        require(verified) { "The device did not prove ownership of its pairing identity." }
        peer
    }.fold(
        onSuccess = PairingIdentityProofResult::Verified,
        onFailure = { PairingIdentityProofResult.Rejected("The pairing identity proof could not be verified.") },
    )

    private companion object { const val SIGNATURE_ALGORITHM = "SHA256withECDSA" }
}
