package com.jagdeep.transfer.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** Android pairing-only counterpart. It never exposes this listener as a transfer endpoint. */
class AndroidPairingInvitationListener(
    private val scope: CoroutineScope,
    private val identity: AndroidTlsIdentity,
    private val proofVerifier: PairingIdentityProofVerifier,
    private val approvals: PairingApprovalCoordinator,
    private val trustedPeers: TrustedPeerRepository,
    private val onTrustChanged: () -> Unit = {},
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private var listener: AndroidTlsListener? = null
    private var acceptJob: Job? = null
    private val activeSessions = Semaphore(MAX_CONCURRENT_SESSIONS)
    private val claimedSessionIds = mutableSetOf<String>()
    private val sessionLock = Mutex()

    suspend fun start(host: String, displayName: String): PairingConnectionInvitation {
        close()
        val active = AndroidTlsTransport(AndroidPairingTlsContext.server(identity)).listen(requireClientAuthentication = false)
        listener = active
        val invitation = PairingConnectionInvitation(
            sessionId = PairingConnectionInvitationCodec.sessionIdFrom(ByteArray(16).also(SecureRandom()::nextBytes)),
            expiresAtEpochMillis = nowEpochMillis() + LIFETIME,
            host = host, port = active.endpoint.port,
            initiatorDeviceId = "android-${identity.certificateFingerprint.take(24)}",
            initiatorDisplayName = displayName,
            initiatorFingerprint = identity.certificateFingerprint,
            initiatorNonce = ByteArray(16).also(SecureRandom()::nextBytes),
        )
        acceptJob = scope.launch { acceptLoop(active, invitation) }
        return invitation
    }

    private suspend fun acceptLoop(active: AndroidTlsListener, invitation: PairingConnectionInvitation) {
        while (listener === active && nowEpochMillis() < invitation.expiresAtEpochMillis) {
            val connection = runCatching { active.accept() }.getOrNull() ?: continue
            if (!activeSessions.tryAcquire()) {
                runCatching {
                    connection.sendPairingMessage(
                        PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, invitation.sessionId, "This device is handling other pairing requests. Try again shortly."),
                    )
                }
                connection.close()
                continue
            }
            scope.launch {
                var claimedSessionId: String? = null
                try {
                    val finished = runCatching { withTimeoutOrNull(
                        (invitation.expiresAtEpochMillis - nowEpochMillis()).coerceAtLeast(1),
                    ) {
                    val result = AndroidPairingSessionDispatcher(proofVerifier).receiveInitialProof(connection)
                    val verified = result as? PairingDispatchResult.ProofVerified ?: run {
                        connection.sendPairingMessage(PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, invitation.sessionId, "Identity proof was rejected.")); return@withTimeoutOrNull true
                    }
                    if (verified.sessionId != invitation.sessionId || nowEpochMillis() >= invitation.expiresAtEpochMillis) {
                        connection.sendPairingMessage(PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, invitation.sessionId, "Pairing invitation expired or does not match.")); return@withTimeoutOrNull true
                    }
                    if (!claimSession(verified.sessionId)) {
                        connection.sendPairingMessage(PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, invitation.sessionId, "This pairing invitation is already being used on another device.")); return@withTimeoutOrNull true
                    }
                    claimedSessionId = verified.sessionId
                    val accepted = approvals.awaitDecision(IncomingPairingRequest(verified.sessionId, verified.peer, verified.transcriptHash.take(6)),
                        (invitation.expiresAtEpochMillis - nowEpochMillis()).coerceAtLeast(1))
                    connection.sendPairingMessage(PairingWireMessage.Confirmation(PairingProtocol.CURRENT_VERSION, invitation.sessionId, verified.transcriptHash, accepted))
                    if (accepted) {
                        val acknowledgement = connection.receivePairingMessage() as? PairingWireMessage.Acknowledgement
                        if (acknowledgement?.sessionId == invitation.sessionId && acknowledgement.transcriptHash == verified.transcriptHash) {
                            try {
                                val recoveryStore = trustedPeers as? PairingRecoveryStore
                                    ?: run {
                                        connection.sendPairingMessage(PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, invitation.sessionId, "Secure pairing recovery is unavailable on this device.")); return@withTimeoutOrNull true
                                    }
                                recoveryStore.recordPendingPairingRecovery(PendingPairingRecovery(verified.peer, invitation.sessionId, verified.transcriptHash, nowEpochMillis()))
                                trustedPeers.upsertVerified(verified.peer)
                                onTrustChanged()
                                connection.sendPairingMessage(PairingWireMessage.Acknowledgement(PairingProtocol.CURRENT_VERSION, invitation.sessionId, verified.transcriptHash))
                                completeInvitation(active)
                            } catch (_: Exception) {
                                connection.sendPairingMessage(PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, invitation.sessionId, "Could not save the paired device."))
                            }
                        }
                    }
                        true
                    } ?: false }.getOrDefault(true)
                    if (!finished) {
                        runCatching {
                            connection.sendPairingMessage(
                                PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, invitation.sessionId, "Pairing invitation expired before it was completed."),
                            )
                        }
                    }
                } finally { claimedSessionId?.let { releaseSession(it) }; activeSessions.release(); connection.close() }
            }
        }
    }

    override fun close() { acceptJob?.cancel(); acceptJob = null; listener?.close(); listener = null }
    private suspend fun claimSession(sessionId: String): Boolean = sessionLock.withLock { claimedSessionIds.add(sessionId) }
    private suspend fun releaseSession(sessionId: String) { sessionLock.withLock { claimedSessionIds.remove(sessionId) } }
    private fun completeInvitation(active: AndroidTlsListener) {
        if (listener === active) {
            listener = null
            active.close()
        }
    }
    private companion object {
        const val LIFETIME = 5 * 60 * 1_000L
        const val MAX_CONCURRENT_SESSIONS = 4
    }
}
