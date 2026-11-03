package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Secure journal for the distributed commit window after a receiver saves trust but before it
 * knows that the scanner received the final acknowledgement. A record means "repair may be
 * needed", never that the remote device is trusted.
 */
data class PendingPairingRecovery(
    val peer: TrustedPeer,
    val sessionId: String,
    val transcriptHash: String,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(transcriptHash.length == 64 && transcriptHash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        require(recordedAtEpochMillis > 0)
    }
}

interface PairingRecoveryStore {
    val pendingPairingRecoveries: StateFlow<List<PendingPairingRecovery>>

    suspend fun recordPendingPairingRecovery(record: PendingPairingRecovery)
    suspend fun clearPendingPairingRecovery(peerFingerprint: String)
}

/** Clears an uncertainty record only after a peer has completed a mutually authenticated TLS handshake. */
suspend fun TrustedPeerRepository.reconcilePairingRecovery(peerFingerprint: String) {
    (this as? PairingRecoveryStore)?.clearPendingPairingRecovery(peerFingerprint)
}
