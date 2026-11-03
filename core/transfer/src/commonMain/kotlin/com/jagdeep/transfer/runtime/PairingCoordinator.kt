package com.jagdeep.transfer.runtime

sealed interface PairingPreparation {
    data class ReadyForConfirmation(val peer: TrustedPeer) : PairingPreparation
    data class Rejected(val message: String) : PairingPreparation
}

sealed interface PairingCommitResult {
    data class Paired(val peer: TrustedPeer) : PairingCommitResult
    data object Cancelled : PairingCommitResult
}

/** Keeps certificate verification and the user's explicit trust decision as separate operations. */
class PairingCoordinator(
    private val verifier: PairingVerifier,
    private val trustedPeers: TrustedPeerRepository,
) {
    fun prepare(invitationPayload: String): PairingPreparation = runCatching {
        PairingInvitationCodec.decode(invitationPayload)
    }.fold(
        onSuccess = { invitation ->
            when (val verification = verifier.verify(invitation)) {
                is PairingVerificationResult.Verified -> PairingPreparation.ReadyForConfirmation(verification.peer)
                is PairingVerificationResult.Rejected -> PairingPreparation.Rejected(verification.message)
            }
        },
        onFailure = { PairingPreparation.Rejected("The pairing invitation is invalid.") },
    )

    suspend fun commit(prepared: PairingPreparation.ReadyForConfirmation, userConfirmed: Boolean): PairingCommitResult {
        if (!userConfirmed) return PairingCommitResult.Cancelled
        trustedPeers.upsertVerified(prepared.peer)
        return PairingCommitResult.Paired(prepared.peer)
    }
}
