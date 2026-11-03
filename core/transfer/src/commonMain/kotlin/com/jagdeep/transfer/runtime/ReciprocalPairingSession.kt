package com.jagdeep.transfer.runtime

/**
 * Pure, transport-independent reciprocal-pairing state machine. TLS/certificate verification
 * and transcript hashing happen at the platform boundary before these inputs are accepted.
 */
enum class PairingRole { INITIATOR, RESPONDER }

sealed interface ReciprocalPairingState {
    data class AwaitingPeer(val sessionId: String, val role: PairingRole) : ReciprocalPairingState
    data class AwaitingLocalConfirmation(val sessionId: String, val peer: TrustedPeer, val verificationCode: String) : ReciprocalPairingState
    data class AwaitingRemoteConfirmation(val sessionId: String, val peer: TrustedPeer, val verificationCode: String) : ReciprocalPairingState
    data class AwaitingAcknowledgement(val sessionId: String, val peer: TrustedPeer) : ReciprocalPairingState
    data class Paired(val peer: TrustedPeer) : ReciprocalPairingState
    data class Incomplete(val message: String) : ReciprocalPairingState
    data class Rejected(val message: String) : ReciprocalPairingState
}

data class ReciprocalPairingConfirmation(
    val sessionId: String,
    val transcriptHash: String,
    val accepted: Boolean,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(transcriptHash.length == 64 && transcriptHash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

data class ReciprocalPairingAcknowledgement(
    val sessionId: String,
    val transcriptHash: String,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(transcriptHash.length == 64 && transcriptHash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

/**
 * Enforces consent and acknowledgement ordering. It deliberately cannot create a transfer
 * authorization until a platform transport has authenticated the peer and both sides agree on
 * the same transcript hash.
 */
class ReciprocalPairingSession(
    private val sessionId: String,
    private val role: PairingRole,
    private val transcriptHash: String,
    private val trustedPeers: TrustedPeerRepository,
    private val expiresAtEpochMillis: Long,
    private val nowEpochMillis: () -> Long,
) {
    private var peer: TrustedPeer? = null
    private var localAccepted = false
    private var remoteAccepted = false
    private var persisted = false

    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(transcriptHash.length == 64 && transcriptHash.all { it in '0'..'9' || it in 'a'..'f' })
        require(expiresAtEpochMillis > 0)
    }

    var state: ReciprocalPairingState = ReciprocalPairingState.AwaitingPeer(sessionId, role)
        private set

    fun receiveVerifiedPeer(verifiedPeer: TrustedPeer, verificationCode: String): ReciprocalPairingState {
        if (hasExpired()) return incomplete("Pairing expired. Start a new connection.")
        if (state !is ReciprocalPairingState.AwaitingPeer) return reject("This pairing message was already processed.")
        if (verificationCode.length !in 4..32) return reject("The pairing verification code is invalid.")
        peer = verifiedPeer
        state = ReciprocalPairingState.AwaitingLocalConfirmation(sessionId, verifiedPeer, verificationCode)
        return state
    }

    suspend fun confirmLocally(accepted: Boolean): ReciprocalPairingState {
        if (hasExpired()) return incomplete("Pairing expired. Start a new connection.")
        val current = state as? ReciprocalPairingState.AwaitingLocalConfirmation
            ?: return reject("Pairing is not ready for confirmation.")
        if (!accepted) return reject("Pairing was declined on this device.")
        localAccepted = true
        state = ReciprocalPairingState.AwaitingRemoteConfirmation(sessionId, current.peer, current.verificationCode)
        return state
    }

    suspend fun receiveRemoteConfirmation(message: ReciprocalPairingConfirmation): ReciprocalPairingState {
        if (hasExpired()) return incomplete("Pairing expired. Start a new connection.")
        val current = state as? ReciprocalPairingState.AwaitingRemoteConfirmation
            ?: return reject("Unexpected pairing confirmation.")
        if (message.sessionId != sessionId || message.transcriptHash != transcriptHash) {
            return reject("The pairing confirmation does not match this session.")
        }
        if (!message.accepted) return reject("Pairing was declined on the other device.")
        remoteAccepted = true
        if (!localAccepted || !remoteAccepted) return reject("Pairing confirmation is incomplete.")
        return try {
            trustedPeers.upsertVerified(current.peer)
            persisted = true
            state = ReciprocalPairingState.AwaitingAcknowledgement(sessionId, current.peer)
            state
        } catch (_: Exception) {
            state = ReciprocalPairingState.Incomplete("Could not save this paired device. Retry pairing.")
            state
        }
    }

    fun receiveAcknowledgement(message: ReciprocalPairingAcknowledgement): ReciprocalPairingState {
        if (hasExpired()) return incomplete("Pairing expired before completion. Retry safely.")
        val current = state as? ReciprocalPairingState.AwaitingAcknowledgement
            ?: return reject("Unexpected pairing acknowledgement.")
        if (!persisted || message.sessionId != sessionId || message.transcriptHash != transcriptHash) {
            return reject("The pairing acknowledgement does not match this session.")
        }
        state = ReciprocalPairingState.Paired(current.peer)
        return state
    }

    private fun reject(message: String): ReciprocalPairingState {
        state = ReciprocalPairingState.Rejected(message)
        return state
    }

    private fun incomplete(message: String): ReciprocalPairingState {
        state = ReciprocalPairingState.Incomplete(message)
        return state
    }

    private fun hasExpired(): Boolean = nowEpochMillis() >= expiresAtEpochMillis
}
