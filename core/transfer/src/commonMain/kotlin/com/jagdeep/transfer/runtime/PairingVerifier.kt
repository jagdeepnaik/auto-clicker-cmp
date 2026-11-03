package com.jagdeep.transfer.runtime

sealed interface PairingVerificationResult {
    data class Verified(val peer: TrustedPeer) : PairingVerificationResult
    data class Rejected(val message: String) : PairingVerificationResult
}

/** Platform crypto boundary; callers must also obtain explicit user confirmation before saving trust. */
interface PairingVerifier {
    fun verify(invitation: PairingInvitation): PairingVerificationResult
}
