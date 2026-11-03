package com.jagdeep.transfer.runtime

import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.Inet4Address
import java.net.NetworkInterface

/** Desktop pairing facade using an encrypted identity file and an OS-protected trusted-peer set. */
class JvmPairingService(
    private val identity: JvmTlsIdentity,
    private val repository: TrustedPeerRepository,
    onTrustChanged: () -> Unit = {},
) : PairingService {
    private val coordinator = PairingCoordinator(JvmPairingVerifier(), repository)
    val incomingApprovals = PairingApprovalCoordinator()
    private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionListener = JvmPairingInvitationListener(pairingScope, identity, JvmPairingIdentityProof(identity), incomingApprovals, repository, onTrustChanged)

    @Volatile private var localDisplayName = "Zynk desktop"
    private fun localPairingIdentity() = LocalPairingIdentity(
        "desktop-${identity.certificateFingerprint.take(24)}", localDisplayName, identity.certificateFingerprint,
    )
    private val nearbyListener = JvmNearbyPairingListener(pairingScope, identity, ::localPairingIdentity, repository, incomingApprovals, onTrustChanged)
    override val nearbyPairingPort: StateFlow<Int?> = nearbyListener.port

    init {
        pairingScope.launch { runCatching { nearbyListener.start() } }
    }

    override val autoAcceptFingerprints: StateFlow<Set<String>> =
        (repository as? AutoAcceptStore)?.autoAcceptFingerprints ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet())

    override suspend fun setAutoAccept(fingerprint: String, enabled: Boolean): Boolean =
        (repository as? AutoAcceptStore)?.setAutoAccept(fingerprint, enabled) ?: false

    override fun updateLocalDisplayName(name: String) {
        if (name.isNotBlank()) localDisplayName = name.trim().take(80)
    }

    override suspend fun pairWithNearby(
        host: String,
        port: Int,
        displayName: String,
        confirmCode: suspend (peerName: String, code: String) -> Boolean,
    ): PairingConnectionResult {
        updateLocalDisplayName(displayName)
        return jvmPairWithNearby(identity, localPairingIdentity(), repository, host, port, confirmCode)
    }

    override val trustedPeers: StateFlow<List<TrustedPeer>> = repository.peers
    override val pendingIncomingPairings: StateFlow<List<IncomingPairingRequest>> = incomingApprovals.pending
    override val pendingPairingRecoveries: StateFlow<List<PendingPairingRecovery>> =
        (repository as? PairingRecoveryStore)?.pendingPairingRecoveries ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    override fun suggestedLanHost(): String? = localLanAddress()

    override fun createInvitation(displayName: String): String = PairingInvitationCodec.encode(
        PairingInvitation(
            deviceId = "desktop-${identity.certificateFingerprint.take(24)}",
            displayName = displayName,
            certificateFingerprint = identity.certificateFingerprint,
            certificateDer = identity.certificate.encoded,
        ),
    )

    override fun prepareInvitation(payload: String): PairingPreparation = coordinator.prepare(payload)

    override suspend fun confirm(
        prepared: PairingPreparation.ReadyForConfirmation,
        userConfirmed: Boolean,
    ): PairingCommitResult = coordinator.commit(prepared, userConfirmed)

    override suspend fun decideIncomingPairing(sessionId: String, accepted: Boolean): Boolean = incomingApprovals.decide(sessionId, accepted)

    override suspend fun createConnectionInvitation(displayName: String, host: String): PairingConnectionInvitationResult = runCatching {
        PairingConnectionInvitationResult.Created(connectionListener.start(host, displayName))
    }.getOrElse { PairingConnectionInvitationResult.Unavailable("Could not start secure pairing. Check network access and try again.") }

    override suspend fun connectToInvitation(payload: String, displayName: String, localCodeConfirmed: Boolean): PairingConnectionResult {
        val invitation = runCatching { PairingConnectionInvitationCodec.decode(payload) }
            .getOrElse { return PairingConnectionResult.Rejected(it.message ?: "That isn't a Zynk pairing code.") }
        return when (val result = JvmPairingScannerSession(identity, repository).connectAndConfirm(invitation, displayName, localCodeConfirmed)) {
            is PairingScannerResult.Acknowledged -> PairingConnectionResult.Paired(result.inviter)
            is PairingScannerResult.Rejected -> PairingConnectionResult.Rejected(result.message)
        }
    }

    override fun prepareConnectionInvitation(payload: String): PairingConnectionPreparation = runCatching {
        val invitation = PairingConnectionInvitationCodec.decode(payload)
        if (System.currentTimeMillis() >= invitation.expiresAtEpochMillis) error("Pairing invitation expired. Ask the other device to create a new one.")
        val code = invitation.initiatorNonce.toHexSha256(invitation.sessionId, identity.certificateFingerprint).take(6)
        PairingConnectionPreparation.Ready(invitation.initiatorDisplayName, code)
    }.getOrElse { PairingConnectionPreparation.Rejected(it.message ?: "That isn't a Zynk pairing code.") }

    override suspend fun revokeTrustedPeer(deviceId: String): Boolean = runCatching {
        repository.remove(deviceId)
        true
    }.getOrDefault(false)
}

private fun localLanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        .flatMap { network -> network.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?.hostAddress
}.getOrNull()
