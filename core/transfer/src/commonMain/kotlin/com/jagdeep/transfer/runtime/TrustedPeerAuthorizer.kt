package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity

/**
 * Binds the mTLS certificate accepted by the transport to the identity claimed on the control
 * channel. A valid TLS handshake alone is insufficient: the certificate must be one explicitly
 * saved during pairing and must belong to the claimed device id.
 */
class TrustedPeerAuthorizer(private val trustedPeers: TrustedPeerRepository) {
    suspend fun authorize(
        presentedCertificateFingerprint: String,
        claimedPeer: PeerIdentity,
    ): TrustedPeerAuthorization = when {
        claimedPeer.publicKeyFingerprint != presentedCertificateFingerprint ->
            TrustedPeerAuthorization.Rejected("The device identity does not match its secure certificate.")

        else -> {
            val trustedPeer = trustedPeers.findByFingerprint(presentedCertificateFingerprint)
                ?: return TrustedPeerAuthorization.Rejected("This device has not been paired.")
            if (trustedPeer.deviceId != claimedPeer.deviceId) {
                TrustedPeerAuthorization.Rejected("The paired device identity does not match this connection.")
            } else {
                TrustedPeerAuthorization.Authorized(trustedPeer)
            }
        }
    }
}

sealed interface TrustedPeerAuthorization {
    data class Authorized(val peer: TrustedPeer) : TrustedPeerAuthorization
    data class Rejected(val message: String) : TrustedPeerAuthorization
}
