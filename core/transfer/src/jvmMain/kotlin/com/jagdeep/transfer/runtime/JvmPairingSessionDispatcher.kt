package com.jagdeep.transfer.runtime

/**
 * First-stage dispatcher for the pairing-only listener. It intentionally accepts no transfer
 * messages and returns a verified, still-untrusted peer for the UI/session coordinator to
 * confirm bilaterally.
 */
class JvmPairingSessionDispatcher(
    private val proofVerifier: PairingIdentityProofVerifier,
) {
    suspend fun receiveInitialProof(connection: JvmTlsConnection): PairingDispatchResult {
        val message = runCatching { connection.receivePairingMessage() }.getOrElse {
            return PairingDispatchResult.Rejected("Could not read the pairing request.")
        }
        val proof = (message as? PairingWireMessage.IdentityProof)?.proof
            ?: return PairingDispatchResult.Rejected("A pairing identity proof is required first.")
        return when (val result = proofVerifier.verify(proof)) {
            is PairingIdentityProofResult.Verified -> PairingDispatchResult.ProofVerified(
                peer = result.peer,
                sessionId = proof.sessionId,
                transcriptHash = proof.transcriptHash,
            )
            is PairingIdentityProofResult.Rejected -> PairingDispatchResult.Rejected(result.message)
        }
    }

    fun initialProof(
        signer: JvmPairingIdentityProof,
        sessionId: String,
        transcriptHash: String,
        displayName: String,
    ): PairingWireMessage.IdentityProof = PairingWireMessage.IdentityProof(
        version = PairingProtocol.CURRENT_VERSION,
        proof = signer.create(sessionId, transcriptHash, displayName),
    )
}
