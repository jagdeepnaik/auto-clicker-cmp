package com.jagdeep.transfer.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A pairing request is not an authorization; it needs explicit local confirmation. */
data class IncomingPairingRequest(
    val sessionId: String,
    val peer: TrustedPeer,
    val verificationCode: String,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(verificationCode.length in 4..32)
    }
}

/** App-scoped bridge between a verified pairing listener and the pairing confirmation UI. */
class PairingApprovalCoordinator {
    private val lock = Mutex()
    private val decisions = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val mutablePending = MutableStateFlow<List<IncomingPairingRequest>>(emptyList())

    val pending: StateFlow<List<IncomingPairingRequest>> = mutablePending.asStateFlow()

    suspend fun awaitDecision(
        request: IncomingPairingRequest,
        timeoutMillis: Long = TransferTimeouts.APPROVAL_MILLIS,
    ): Boolean {
        require(timeoutMillis > 0)
        val decision = lock.withLock {
            require(decisions[request.sessionId] == null) { "This pairing session is already pending." }
            CompletableDeferred<Boolean>().also {
                decisions[request.sessionId] = it
                mutablePending.update { requests -> requests + request }
            }
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { decision.await() } ?: false
        } finally {
            lock.withLock {
                decisions.remove(request.sessionId)
                mutablePending.update { requests -> requests.filterNot { it.sessionId == request.sessionId } }
            }
        }
    }

    suspend fun decide(sessionId: String, accepted: Boolean): Boolean = lock.withLock {
        decisions[sessionId]?.complete(accepted) == true
    }
}
