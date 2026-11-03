package com.jagdeep.transfer.runtime

import kotlinx.serialization.Serializable

/**
 * Public identity material plus proof of possession for the pairing-only connection. The
 * signature covers the fresh session and transcript, preventing a certificate copied from an
 * old invitation from being replayed as a live device.
 */
@Serializable
data class PairingIdentityProof(
    val invitation: PairingInvitation,
    val sessionId: String,
    val transcriptHash: String,
    val signature: ByteArray,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(transcriptHash.length == 64 && transcriptHash.all { it in '0'..'9' || it in 'a'..'f' })
        require(signature.size in 8..512)
    }
}

/** Canonical, length-delimited bytes consumed by the platform signature implementations. */
object PairingProofTranscript {
    fun bytes(sessionId: String, transcriptHash: String, invitation: PairingInvitation): ByteArray {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(transcriptHash.length == 64 && transcriptHash.all { it in '0'..'9' || it in 'a'..'f' })
        return buildString {
            append("zynk-pair-proof-v2|")
            append(sessionId.length).append(':').append(sessionId)
            append('|').append(transcriptHash)
            append('|').append(invitation.deviceId.length).append(':').append(invitation.deviceId)
            append('|').append(invitation.certificateFingerprint)
            append('|').append(invitation.certificateDer.size).append(':')
        }.encodeToByteArray() + invitation.certificateDer
    }
}

sealed interface PairingIdentityProofResult {
    data class Verified(val peer: TrustedPeer) : PairingIdentityProofResult
    data class Rejected(val message: String) : PairingIdentityProofResult
}

/** Platform crypto boundary; the private identity key never crosses it. */
interface PairingIdentityProofVerifier {
    fun verify(proof: PairingIdentityProof): PairingIdentityProofResult
}
