package com.jagdeep.transfer.runtime

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class AndroidPairingVerifier : PairingVerifier {
    override fun verify(invitation: PairingInvitation): PairingVerificationResult = runCatching {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(invitation.certificateDer)) as X509Certificate
        certificate.checkValidity()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        require(fingerprint == invitation.certificateFingerprint) { "The pairing fingerprint does not match its certificate." }
        TrustedPeer(invitation.deviceId, invitation.displayName, fingerprint, certificate.encoded)
    }.fold(
        onSuccess = PairingVerificationResult::Verified,
        onFailure = { PairingVerificationResult.Rejected("The pairing invitation could not be verified.") },
    )
}
