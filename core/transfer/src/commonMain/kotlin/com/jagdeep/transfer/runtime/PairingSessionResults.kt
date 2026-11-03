package com.jagdeep.transfer.runtime

/** Platform-neutral results from the verified pairing protocol stages. */
sealed interface PairingDispatchResult {
    data class ProofVerified(
        val peer: TrustedPeer,
        val sessionId: String,
        val transcriptHash: String,
    ) : PairingDispatchResult

    data class Rejected(val message: String) : PairingDispatchResult
}

sealed interface PairingScannerResult {
    data class Acknowledged(val inviter: TrustedPeer, val transcriptHash: String) : PairingScannerResult
    data class Rejected(val message: String) : PairingScannerResult
}
