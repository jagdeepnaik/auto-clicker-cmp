package com.jagdeep.network.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Explicit platform fallback; it does not pretend that local discovery is active. */
class UnsupportedPeerDiscoveryService : PeerDiscoveryService {
    private val mutablePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    private val mutableStatus = MutableStateFlow<PeerDiscoveryStatus>(PeerDiscoveryStatus.Unsupported)

    override val peers: StateFlow<List<DiscoveredPeer>> = mutablePeers
    override val status: StateFlow<PeerDiscoveryStatus> = mutableStatus

    override suspend fun start(announcement: LocalPeerAnnouncement) = Unit
    override suspend fun stop() = Unit
    override fun close() = Unit
}
