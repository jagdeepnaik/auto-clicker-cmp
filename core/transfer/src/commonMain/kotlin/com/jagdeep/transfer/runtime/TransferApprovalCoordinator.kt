package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferManifest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An incoming transfer must remain pending until the receiving user explicitly decides. */
data class IncomingTransferRequest(
    val requestId: String,
    val sender: PeerIdentity,
    val manifest: TransferManifest,
    /** Local-only destination captured by the receiver; never supplied by the remote peer. */
    val destination: String? = null,
) {
    init { require(requestId.isNotBlank() && requestId.length <= 128) }

    val totalBytes: Long get() = manifest.entries.sumOf { it.sizeBytes }
}

/** Shared sender-facing copy: a receiver cannot tell "declined" from "nobody answered in time". */
object TransferApprovalMessages {
    val NOT_ACCEPTED = "The other device didn't accept the transfer. It was declined, or nobody responded within " +
        "${TransferTimeouts.APPROVAL_MILLIS / 60_000} minutes. Make sure Zynk is open there, then try again."
    /**
     * A reason received from the other device, made safe to show: path-like text is removed
     * (older versions sent raw system errors with file paths) and the length is capped.
     */
    fun forDisplay(reason: String?): String {
        val cleaned = reason.orEmpty()
            .replace(Regex("""([A-Za-z]:)?[\\/][^\s:]*[\\/][^\s:]*"""), "a file")
            .replace(Regex("\\s+"), " ")
            .trim()
        val fallback = "The other device couldn't finish the transfer. Try again."
        if (cleaned.isBlank()) return fallback
        return if (cleaned.length <= 160) cleaned else cleaned.take(157).trimEnd() + "…"
    }

    const val SYNC_UNSUPPORTED = "The other device can't receive folder syncs. Sync to a computer, or update Zynk there."
}

/**
 * App-scoped bridge between a verified listener and the receiver-approval UI.
 *
 * [autoAccept] may accept a request without asking. It is only ever called with a sender that
 * the listener has already authenticated (its TLS certificate is a saved pairing), and must
 * return true only for devices the user explicitly allowed. Every automatic acceptance is
 * announced on [autoAccepted] so the user is always told.
 */
class TransferApprovalCoordinator(
    private val autoAccept: suspend (IncomingTransferRequest) -> Boolean = { false },
) {
    private val mutableAutoAccepted = MutableSharedFlow<IncomingTransferRequest>(extraBufferCapacity = 16)
    val autoAccepted: SharedFlow<IncomingTransferRequest> = mutableAutoAccepted.asSharedFlow()

    private val lock = Mutex()
    private val decisions = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val mutablePending = MutableStateFlow<List<IncomingTransferRequest>>(emptyList())

    val pending: StateFlow<List<IncomingTransferRequest>> = mutablePending.asStateFlow()

    /** Suspends until the receiver decides, then safely rejects an unanswered request after the timeout. */
    suspend fun awaitDecision(request: IncomingTransferRequest, timeoutMillis: Long = TransferTimeouts.APPROVAL_MILLIS): Boolean {
        require(timeoutMillis > 0) { "Approval timeout must be positive." }
        // A failing policy never accepts: it falls back to asking.
        if (runCatching { autoAccept(request) }.getOrDefault(false)) {
            mutableAutoAccepted.tryEmit(request)
            return true
        }
        val decision = lock.withLock {
            require(decisions[request.requestId] == null) { "A request with this id is already pending." }
            CompletableDeferred<Boolean>().also { deferred ->
                decisions[request.requestId] = deferred
                mutablePending.update { it + request }
            }
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { decision.await() } ?: false
        } finally {
            lock.withLock {
                decisions.remove(request.requestId)
                mutablePending.update { requests -> requests.filterNot { it.requestId == request.requestId } }
            }
        }
    }

    suspend fun decide(requestId: String, accepted: Boolean): Boolean = lock.withLock {
        decisions[requestId]?.complete(accepted) == true
    }

}
