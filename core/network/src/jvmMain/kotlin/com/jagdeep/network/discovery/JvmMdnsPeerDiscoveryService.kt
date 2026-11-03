package com.jagdeep.network.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Desktop DNS-SD/mDNS implementation. It advertises presence but no transfer endpoint until the
 * authenticated listener supplies one, preventing discovery from exposing an insecure port. */
class JvmMdnsPeerDiscoveryService : PeerDiscoveryService {
    private val lock = Mutex()
    private val mutablePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    private val mutableStatus = MutableStateFlow<PeerDiscoveryStatus>(PeerDiscoveryStatus.Stopped)
    private var jmDns: JmDNS? = null
    private var presenceBeacon: JvmUdpPresenceBeacon? = null
    private var presenceSocket: ServerSocket? = null
    private val localNodeId = UUID.randomUUID().toString()
    private var localInstanceName: String? = null
    private var activeAnnouncement: LocalPeerAnnouncement? = null
    /** Kept across stop/start so stale self-advertisements stay filtered. */
    @Volatile private var localFingerprint: String? = null

    override val peers: StateFlow<List<DiscoveredPeer>> = mutablePeers
    override val status: StateFlow<PeerDiscoveryStatus> = mutableStatus

    override suspend fun start(announcement: LocalPeerAnnouncement) {
        rememberLocalFingerprint(announcement)
        lock.withLock {
            if (activeAnnouncement == announcement && (jmDns != null || presenceBeacon != null)) return
            stopLocked()
            mutableStatus.value = PeerDiscoveryStatus.Starting
            try {
                withContext(Dispatchers.IO) {
                    val socket = ServerSocket(0)
                    presenceSocket = socket
                    startPresenceDrain(socket)
                    presenceBeacon = JvmUdpPresenceBeacon(localNodeId, ::recordPeer).also {
                        it.start(announcement)
                    }
                    runCatching {
                        val localAddress = selectLanAddress()
                            ?: error("No active Wi-Fi or Ethernet IPv4 address is available for local discovery.")
                        val dns = JmDNS.create(localAddress, "zynk-${localNodeId.take(8)}")
                        val instanceName = announcement.displayName.toServiceInstanceName()
                        val info = ServiceInfo.create(
                            DISCOVERY_SERVICE_TYPE,
                            instanceName,
                            announcement.transferPort ?: socket.localPort,
                            0,
                            0,
                            mapOf(
                                "protocol" to announcement.protocolVersion.toString(),
                                "platform" to announcement.platform,
                                "fingerprint" to (announcement.publicKeyFingerprint ?: ""),
                                "nodeId" to localNodeId,
                                "transferAvailable" to (announcement.transferPort != null).toString(),
                                "pairPort" to (announcement.pairingPort?.toString() ?: ""),
                            ),
                        )
                        dns.addServiceListener(DISCOVERY_SERVICE_TYPE, listenerFor(dns))
                        dns.registerService(info)
                        jmDns = dns
                        localInstanceName = instanceName
                    }
                    activeAnnouncement = announcement
                }
                mutableStatus.value = PeerDiscoveryStatus.Active
            } catch (failure: Throwable) {
                stopLocked()
                mutableStatus.value = PeerDiscoveryStatus.Failed("Local network discovery is unavailable. Check your network and firewall.")
            }
        }
    }

    override suspend fun stop() = lock.withLock { stopLocked() }

    override fun close() {
        runCatching { jmDns?.close() }
        runCatching { presenceBeacon?.close() }
        runCatching { presenceSocket?.close() }
        jmDns = null
        presenceBeacon = null
        presenceSocket = null
        activeAnnouncement = null
        localInstanceName = null
        mutablePeers.value = emptyList()
        mutableStatus.value = PeerDiscoveryStatus.Stopped
    }

    private fun listenerFor(dns: JmDNS) = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            dns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val nodeId = info.getPropertyString("nodeId") ?: return
            if (nodeId == localNodeId) return
            val protocol = info.getPropertyString("protocol")?.toIntOrNull() ?: return
            val host = info.inet4Addresses.firstOrNull()?.hostAddress
                ?: info.inetAddresses.firstOrNull()?.hostAddress
            val transferAvailable = info.getPropertyString("transferAvailable") == "true"
            val endpoint = if (transferAvailable) {
                host?.let { address -> info.port.takeIf { it in 1..65_535 }?.let { DiscoveryEndpoint(address, it) } }
            } else {
                null
            }
            val peer = DiscoveredPeer(
                id = nodeId,
                displayName = info.name,
                platform = info.getPropertyString("platform").orEmpty().ifBlank { "Unknown" },
                protocolVersion = protocol,
                endpoint = endpoint,
                publicKeyFingerprint = info.getPropertyString("fingerprint").orEmpty().ifBlank { null },
                pairingEndpoint = info.getPropertyString("pairPort")?.toIntOrNull()?.takeIf { it in 1..65_535 }
                    ?.let { port -> host?.let { DiscoveryEndpoint(it, port) } },
            )
            recordPeer(peer)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            mutablePeers.value = mutablePeers.value.filterNot { it.displayName == event.name }
        }
    }

    private fun stopLocked() {
        runCatching { jmDns?.unregisterAllServices() }
        runCatching { jmDns?.close() }
        runCatching { presenceBeacon?.close() }
        runCatching { presenceSocket?.close() }
        jmDns = null
        presenceBeacon = null
        presenceSocket = null
        activeAnnouncement = null
        localInstanceName = null
        mutablePeers.value = emptyList()
        mutableStatus.value = PeerDiscoveryStatus.Stopped
    }

    private fun startPresenceDrain(socket: ServerSocket) {
        thread(name = "ZynkDesktopPresenceSocket", isDaemon = true) {
            while (!socket.isClosed) {
                runCatching { socket.accept().close() }.onFailure {
                    if (!socket.isClosed) return@thread
                }
            }
        }
    }

    private fun recordPeer(peer: DiscoveredPeer) {
        if (peer.isLocalDevice(localNodeId, localFingerprint)) return
        mutablePeers.value = mutablePeers.value.record(peer)
    }

    private fun rememberLocalFingerprint(announcement: LocalPeerAnnouncement) {
        val fingerprint = announcement.publicKeyFingerprint ?: return
        localFingerprint = fingerprint
        mutablePeers.value = mutablePeers.value.filterNot { it.isLocalDevice(localNodeId, fingerprint) }
    }
}

private fun String.toServiceInstanceName(): String = trim().take(63).ifBlank { "Zynk" }

/**
 * JmDNS's no-argument factory relies on the host-name resolver. On macOS that resolver can return
 * 127.0.0.1, which makes a service invisible to devices on Wi-Fi/Ethernet. Prefer a routable LAN
 * IPv4 address from an active multicast-capable interface instead.
 */
private fun selectLanAddress(): InetAddress? {
    val candidates = buildList {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback || !networkInterface.supportsMulticast()) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) {
                    add(networkInterface.name to address)
                }
            }
        }
    }
    return candidates
        .sortedBy { (name, _) -> if (name.startsWith("en") || name.startsWith("eth") || name.startsWith("wlan")) 0 else 1 }
        .firstOrNull()
        ?.second
}
