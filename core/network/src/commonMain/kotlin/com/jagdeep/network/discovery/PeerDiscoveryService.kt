package com.jagdeep.network.discovery

import kotlinx.coroutines.flow.StateFlow

/** mDNS service type for compatible peers on a local network. The value is a shipped wire identifier; keep it stable. */
const val DISCOVERY_SERVICE_TYPE = "_zynk._tcp.local."

data class LocalPeerAnnouncement(
    val displayName: String,
    val platform: String,
    val protocolVersion: Int = 1,
    val publicKeyFingerprint: String? = null,
    /** Secure listener port; null means this device is discoverable but cannot receive transfers. */
    val transferPort: Int? = null,
    /** Nearby-pairing listener port; null when this device can't be paired from the Nearby list. */
    val pairingPort: Int? = null,
) {
    init {
        require(displayName.isNotBlank())
        require(platform.isNotBlank())
        require(protocolVersion > 0)
        require(transferPort == null || transferPort in 1..65_535)
        require(pairingPort == null || pairingPort in 1..65_535)
    }
}

data class DiscoveredPeer(
    val id: String,
    val displayName: String,
    val platform: String,
    val protocolVersion: Int,
    /** Null until a verified transfer listener is available. Discovery never implies connectivity. */
    val endpoint: DiscoveryEndpoint?,
    val publicKeyFingerprint: String?,
    /** Where to start nearby pairing. An untrusted hint: trust comes only from the code comparison. */
    val pairingEndpoint: DiscoveryEndpoint? = null,
)

/**
 * Adds a discovery observation without showing the same cryptographic device more than once.
 *
 * mDNS and the UDP presence fallback share an ephemeral node ID, while the advertised public-key
 * fingerprint is the stronger identity once it is available. Match either value to keep an
 * already selected device selected when another transport reports it, while refreshing the route
 * and other advertised details.
 */
internal fun List<DiscoveredPeer>.record(peer: DiscoveredPeer): List<DiscoveredPeer> {
    val matchingPeers = filter { known -> known.isSameDeviceAs(peer) }
    val canonicalId = matchingPeers.firstOrNull()?.id ?: peer.id
    val previous = matchingPeers.firstOrNull()
    val mergedPeer = peer.copy(
        id = canonicalId,
        endpoint = peer.endpoint ?: previous?.endpoint,
        publicKeyFingerprint = peer.publicKeyFingerprint ?: previous?.publicKeyFingerprint,
        pairingEndpoint = peer.pairingEndpoint ?: previous?.pairingEndpoint,
    )
    return (filterNot { known -> known.isSameDeviceAs(peer) } + mergedPeer)
        .sortedBy { it.displayName.lowercase() }
}

/**
 * True for a sighting of this device. The node ID changes with every discovery-service instance
 * (for example after an Android activity is recreated) while an earlier advertisement can still
 * be on the network, so the advertised certificate fingerprint is the durable check.
 */
internal fun DiscoveredPeer.isLocalDevice(localNodeId: String, localFingerprint: String?): Boolean =
    id == localNodeId || (publicKeyFingerprint != null && publicKeyFingerprint == localFingerprint)

private fun DiscoveredPeer.isSameDeviceAs(other: DiscoveredPeer): Boolean =
    id == other.id || (
        publicKeyFingerprint != null &&
            other.publicKeyFingerprint != null &&
            publicKeyFingerprint == other.publicKeyFingerprint
        )

data class DiscoveryEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank())
        require(port in 1..65_535)
    }
}

sealed interface PeerDiscoveryStatus {
    data object Stopped : PeerDiscoveryStatus
    data object Starting : PeerDiscoveryStatus
    data object Active : PeerDiscoveryStatus
    data object Unsupported : PeerDiscoveryStatus
    data class Failed(val userMessage: String) : PeerDiscoveryStatus
}

/**
 * Local-network presence discovery only. A caller must authenticate and obtain receiver approval
 * before using a discovered peer for a transfer.
 */
interface PeerDiscoveryService : AutoCloseable {
    val peers: StateFlow<List<DiscoveredPeer>>
    val status: StateFlow<PeerDiscoveryStatus>

    suspend fun start(announcement: LocalPeerAnnouncement)
    suspend fun stop()
    override fun close()
}
