package com.jagdeep.transfer.runtime

/** Android counterpart of the desktop pairing-only first-frame gate. */
class AndroidPairingSessionDispatcher(
    private val proofVerifier: PairingIdentityProofVerifier,
) {
    suspend fun receiveInitialProof(connection: AndroidTlsConnection): PairingDispatchResult {
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
        signer: AndroidPairingIdentityProof,
        sessionId: String,
        transcriptHash: String,
        displayName: String,
    ): PairingWireMessage.IdentityProof = PairingWireMessage.IdentityProof(
        version = PairingProtocol.CURRENT_VERSION,
        proof = signer.create(sessionId, transcriptHash, displayName),
    )
}
