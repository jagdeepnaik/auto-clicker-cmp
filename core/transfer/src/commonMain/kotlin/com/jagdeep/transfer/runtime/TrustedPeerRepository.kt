package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation
import kotlinx.coroutines.flow.StateFlow

/**
 * A certificate explicitly verified during pairing. The certificate is public material, but its
 * association with a trusted device is security-sensitive and must use platform secure storage.
 */
data class TrustedPeer(
    val deviceId: String,
    val displayName: String,
    val certificateFingerprint: String,
    val certificateDer: ByteArray,
) {
    init {
        require(deviceId.isNotBlank() && deviceId.length <= 128)
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(ProtocolValidation.isSha256(certificateFingerprint))
        require(certificateDer.size in 1..16_384)
    }
}

/**
 * Secure persistence boundary for pairing. Implementations must atomically replace a device's
 * prior trust record only after a new pairing has been explicitly verified by the user.
 */
/**
 * Paired devices whose transfers are accepted without asking. Keyed by certificate fingerprint,
 * so it applies only to the exact certificate verified on the connection; stored in the same
 * secure storage as the pairing records. Only currently paired devices can be enabled, and
 * removing a pairing removes its entry.
 */
interface AutoAcceptStore {
    val autoAcceptFingerprints: StateFlow<Set<String>>

    /** Returns false when [fingerprint] is not a currently paired device. */
    suspend fun setAutoAccept(fingerprint: String, enabled: Boolean): Boolean
}

/**
 * Whether a transfer from [senderFingerprint] may skip the receiver prompt: the master switch is
 * on, this exact certificate is allowed, and it is still a saved pairing. Callers pass only a
 * fingerprint already verified on the TLS connection.
 */
suspend fun TrustedPeerRepository.allowsAutomaticAccept(masterEnabled: Boolean, senderFingerprint: String): Boolean =
    masterEnabled &&
        this is AutoAcceptStore && senderFingerprint in autoAcceptFingerprints.value &&
        findByFingerprint(senderFingerprint) != null

interface TrustedPeerRepository {
    val peers: StateFlow<List<TrustedPeer>>

    suspend fun upsertVerified(peer: TrustedPeer)
    suspend fun remove(deviceId: String)
    suspend fun findByFingerprint(fingerprint: String): TrustedPeer?
}
