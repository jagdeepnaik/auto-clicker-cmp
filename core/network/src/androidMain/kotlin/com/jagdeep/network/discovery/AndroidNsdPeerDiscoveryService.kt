package com.jagdeep.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android NSD implementation for the Zynk DNS-SD service. */
class AndroidNsdPeerDiscoveryService(context: Context) : PeerDiscoveryService {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val lock = Mutex()
    private val mutablePeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    private val mutableStatus = MutableStateFlow<PeerDiscoveryStatus>(PeerDiscoveryStatus.Stopped)
    private var presenceSocket: ServerSocket? = null
    private var presenceBeacon: AndroidUdpPresenceBeacon? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val localNodeId = UUID.randomUUID().toString()
    private var localServiceName: String? = null
    private var activeAnnouncement: LocalPeerAnnouncement? = null
    /** Kept across stop/start so stale self-advertisements stay filtered. */
    @Volatile private var localFingerprint: String? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override val peers: StateFlow<List<DiscoveredPeer>> = mutablePeers
    override val status: StateFlow<PeerDiscoveryStatus> = mutableStatus

    override suspend fun start(announcement: LocalPeerAnnouncement) {
        rememberLocalFingerprint(announcement)
        lock.withLock {
            if (activeAnnouncement == announcement && discoveryListener != null) return
            stopLocked()
            mutableStatus.value = PeerDiscoveryStatus.Starting
            try {
                multicastLock = wifiManager.createMulticastLock("zynk-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                val socket = ServerSocket(0)
                presenceSocket = socket
                startPresenceDrain(socket)
                val listener = createDiscoveryListener()
                discoveryListener = listener
                nsdManager.discoverServices(ANDROID_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                val registration = createRegistrationListener()
                registrationListener = registration
                nsdManager.registerService(
                    NsdServiceInfo().apply {
                        serviceName = announcement.displayName
                        serviceType = ANDROID_SERVICE_TYPE
                        port = announcement.transferPort ?: socket.localPort
                        setAttribute("protocol", announcement.protocolVersion.toString())
                        setAttribute("platform", announcement.platform)
                        setAttribute("fingerprint", announcement.publicKeyFingerprint.orEmpty())
                        setAttribute("nodeId", localNodeId)
                        setAttribute("transferAvailable", (announcement.transferPort != null).toString())
                        setAttribute("pairPort", announcement.pairingPort?.toString().orEmpty())
                    },
                    NsdManager.PROTOCOL_DNS_SD,
                    registration,
                )
                presenceBeacon = AndroidUdpPresenceBeacon(localNodeId, ::recordPeer).also {
                    it.start(announcement)
                }
                activeAnnouncement = announcement
            } catch (_: Throwable) {
                stopLocked()
                mutableStatus.value = PeerDiscoveryStatus.Failed(
                    "Local network discovery is unavailable. Check Wi-Fi and local-network access.",
                )
            }
        }
    }

    override suspend fun stop() = lock.withLock { stopLocked() }

    override fun close() {
        runCatching { discoveryListener?.let(nsdManager::stopServiceDiscovery) }
        runCatching { registrationListener?.let(nsdManager::unregisterService) }
        runCatching { presenceBeacon?.close() }
        runCatching { presenceSocket?.close() }
        runCatching { multicastLock?.release() }
        presenceSocket = null
        presenceBeacon = null
        multicastLock = null
        discoveryListener = null
        registrationListener = null
        localServiceName = null
        activeAnnouncement = null
        mutablePeers.value = emptyList()
        mutableStatus.value = PeerDiscoveryStatus.Stopped
    }

    private fun createDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            mutableStatus.value = PeerDiscoveryStatus.Active
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            // Android normally returns "_zynk._tcp.", while a desktop DNS-SD implementation may
            // report the fully-qualified "_zynk._tcp.local." form. Discovery already filters by
            // type, so do not discard a compatible peer because of that representation detail.
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val nodeId = resolved.attributes["nodeId"]?.decodeToString() ?: return
                    if (nodeId == localNodeId) return
                    val protocol = resolved.attributes["protocol"]?.decodeToString()?.toIntOrNull() ?: return
                    val host = resolved.host?.hostAddress
                    val transferAvailable = resolved.attributes["transferAvailable"]?.decodeToString() == "true"
                    val endpoint = if (transferAvailable && host != null && resolved.port in 1..65_535) {
                        DiscoveryEndpoint(host, resolved.port)
                    } else {
                        null
                    }
                    val peer = DiscoveredPeer(
                        id = nodeId,
                        displayName = resolved.serviceName,
                        platform = resolved.attributes["platform"]?.decodeToString().orEmpty().ifBlank { "Android" },
                        protocolVersion = protocol,
                        endpoint = endpoint,
                        publicKeyFingerprint = resolved.attributes["fingerprint"]?.decodeToString().orEmpty().ifBlank { null },
                        pairingEndpoint = resolved.attributes["pairPort"]?.decodeToString()?.toIntOrNull()?.takeIf { it in 1..65_535 }
                            ?.let { port -> host?.let { DiscoveryEndpoint(it, port) } },
                    )
                    recordPeer(peer)
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            mutablePeers.value = mutablePeers.value.filterNot { it.displayName == serviceInfo.serviceName }
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            mutableStatus.value = PeerDiscoveryStatus.Failed("Local network discovery could not start.")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    private fun createRegistrationListener() = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            localServiceName = serviceInfo.serviceName
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            mutableStatus.value = PeerDiscoveryStatus.Failed("This device could not be advertised on the local network.")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }

    private fun startPresenceDrain(socket: ServerSocket) {
        thread(name = "ZynkPresenceSocket", isDaemon = true) {
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

    private fun stopLocked() {
        runCatching { discoveryListener?.let(nsdManager::stopServiceDiscovery) }
        runCatching { registrationListener?.let(nsdManager::unregisterService) }
        runCatching { presenceBeacon?.close() }
        runCatching { presenceSocket?.close() }
        runCatching { multicastLock?.release() }
        presenceSocket = null
        presenceBeacon = null
        multicastLock = null
        discoveryListener = null
        registrationListener = null
        localServiceName = null
        activeAnnouncement = null
        mutablePeers.value = emptyList()
        mutableStatus.value = PeerDiscoveryStatus.Stopped
    }

    private companion object {
        const val ANDROID_SERVICE_TYPE = "_zynk._tcp."
    }
}
