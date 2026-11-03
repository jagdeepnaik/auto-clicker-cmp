package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Platform-backed pairing boundary consumed by feature UI; it never exposes a private key. */
interface PairingService {
    val trustedPeers: StateFlow<List<TrustedPeer>>
    val pendingIncomingPairings: StateFlow<List<IncomingPairingRequest>>
    val pendingPairingRecoveries: StateFlow<List<PendingPairingRecovery>> get() = MutableStateFlow(emptyList())

    /** A best-effort, user-visible LAN address for the short-lived pairing listener. */
    fun suggestedLanHost(): String? = null

    fun createInvitation(displayName: String): String
    fun prepareInvitation(payload: String): PairingPreparation
    suspend fun confirm(prepared: PairingPreparation.ReadyForConfirmation, userConfirmed: Boolean): PairingCommitResult
    suspend fun decideIncomingPairing(sessionId: String, accepted: Boolean): Boolean
    suspend fun createConnectionInvitation(displayName: String, host: String): PairingConnectionInvitationResult =
        PairingConnectionInvitationResult.Unavailable("Reciprocal pairing is not available on this platform.")
    suspend fun connectToInvitation(payload: String, displayName: String, localCodeConfirmed: Boolean): PairingConnectionResult =
        PairingConnectionResult.Rejected("Reciprocal pairing is not available on this platform.")
    fun prepareConnectionInvitation(payload: String): PairingConnectionPreparation =
        PairingConnectionPreparation.Rejected("Reciprocal pairing is not available on this platform.")
    suspend fun revokeTrustedPeer(deviceId: String): Boolean = false

    /** Fingerprints of paired devices whose files are accepted without asking. */
    val autoAcceptFingerprints: StateFlow<Set<String>> get() = MutableStateFlow(emptySet())

    /** Allows or stops automatic acceptance for a paired device; false when unavailable or not paired. */
    suspend fun setAutoAccept(fingerprint: String, enabled: Boolean): Boolean = false

    /** Port of this device's nearby-pairing listener, advertised through discovery; null when unavailable. */
    val nearbyPairingPort: StateFlow<Int?> get() = MutableStateFlow(null)

    /** Name this device introduces itself with when another device pairs with it. */
    fun updateLocalDisplayName(name: String) = Unit

    /**
     * Pairs with a nearby device by numeric comparison. [confirmCode] shows the other device's
     * name and the 6-digit code and returns whether the user confirmed that both screens match.
     */
    suspend fun pairWithNearby(
        host: String,
        port: Int,
        displayName: String,
        confirmCode: suspend (peerName: String, code: String) -> Boolean,
    ): PairingConnectionResult = PairingConnectionResult.Rejected("Pairing from the nearby list is not available on this device.")
}

sealed interface PairingConnectionInvitationResult {
    data class Created(val invitation: PairingConnectionInvitation) : PairingConnectionInvitationResult
    data class Unavailable(val message: String) : PairingConnectionInvitationResult
}

sealed interface PairingConnectionResult {
    data class Paired(val peer: TrustedPeer) : PairingConnectionResult
    data class Rejected(val message: String) : PairingConnectionResult
}

sealed interface PairingConnectionPreparation {
    /**
     * The scanned code is well formed and fresh. [peerName] is a claim from the QR; the
     * inviter's certificate is verified against the pinned fingerprint only when connecting.
     */
    data class Ready(val peerName: String, val verificationCode: String) : PairingConnectionPreparation
    data class Rejected(val message: String) : PairingConnectionPreparation
}

class UnavailablePairingService(private val message: String) : PairingService {
    override val trustedPeers: StateFlow<List<TrustedPeer>> = MutableStateFlow(emptyList())
    override val pendingIncomingPairings: StateFlow<List<IncomingPairingRequest>> = MutableStateFlow(emptyList())
    override fun createInvitation(displayName: String): String = error(message)
    override fun prepareInvitation(payload: String): PairingPreparation = PairingPreparation.Rejected(message)
    override suspend fun confirm(prepared: PairingPreparation.ReadyForConfirmation, userConfirmed: Boolean): PairingCommitResult =
        PairingCommitResult.Cancelled
    override suspend fun decideIncomingPairing(sessionId: String, accepted: Boolean): Boolean = false
    override suspend fun pairWithNearby(
        host: String,
        port: Int,
        displayName: String,
        confirmCode: suspend (peerName: String, code: String) -> Boolean,
    ): PairingConnectionResult = PairingConnectionResult.Rejected(message)
    override suspend fun connectToInvitation(payload: String, displayName: String, localCodeConfirmed: Boolean): PairingConnectionResult =
        PairingConnectionResult.Rejected(message)
}
