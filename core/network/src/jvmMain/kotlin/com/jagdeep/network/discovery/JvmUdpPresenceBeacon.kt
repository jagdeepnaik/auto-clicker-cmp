package com.jagdeep.network.discovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Small LAN-only fallback for hosts where the operating system DNS-SD service is unavailable.
 * It only exposes a port explicitly supplied by the secure transfer runtime.
 */
internal class JvmUdpPresenceBeacon(
    private val localNodeId: String,
    private val onPeerFound: (DiscoveredPeer) -> Unit,
) : AutoCloseable {
    private var receiver: DatagramSocket? = null
    private var active = false

    fun start(announcement: LocalPeerAnnouncement) {
        if (active) return
        active = true
        receiver = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(PRESENCE_PORT))
            soTimeout = RECEIVE_TIMEOUT_MILLIS
        }
        startReceiver()
        startAdvertiser(announcement)
    }

    override fun close() {
        active = false
        runCatching { receiver?.close() }
        receiver = null
    }

    private fun startReceiver() {
        val socket = requireNotNull(receiver)
        thread(name = "ZynkUdpPresenceReceiver", isDaemon = true) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            while (active && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    decode(packet.data, packet.length)?.takeIf { it.nodeId != localNodeId }?.let { presence ->
                        onPeerFound(
                            DiscoveredPeer(
                                id = presence.nodeId,
                                displayName = presence.displayName,
                                platform = presence.platform,
                                protocolVersion = presence.protocolVersion,
                                endpoint = presence.transferPort?.let { DiscoveryEndpoint(packet.address.hostAddress, it) },
                                publicKeyFingerprint = presence.fingerprint,
                                pairingEndpoint = presence.pairingPort?.let { DiscoveryEndpoint(packet.address.hostAddress, it) },
                            ),
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    // Check active state regularly without busy polling.
                } catch (_: SocketException) {
                    if (active) return@thread
                } catch (_: Throwable) {
                    // Ignore malformed or unrelated UDP traffic on the reserved presence port.
                }
            }
        }
    }

    private fun startAdvertiser(announcement: LocalPeerAnnouncement) {
        thread(name = "ZynkUdpPresenceAdvertiser", isDaemon = true) {
            while (active) {
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        val payload = encode(announcement)
                        socket.send(DatagramPacket(payload, payload.size, BROADCAST_ADDRESS, PRESENCE_PORT))
                    }
                }
                try {
                    Thread.sleep(ADVERTISEMENT_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun encode(announcement: LocalPeerAnnouncement): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeUTF(localNodeId)
            output.writeUTF(announcement.displayName)
            output.writeUTF(announcement.platform)
            output.writeInt(announcement.protocolVersion)
            output.writeUTF(announcement.publicKeyFingerprint.orEmpty())
            output.writeInt(announcement.transferPort ?: 0)
            output.writeInt(announcement.pairingPort ?: 0)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray, length: Int): Presence? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes, 0, length)).use { input ->
            if (input.readInt() != MAGIC) return null
            val nodeId = input.readUTF().takeIf { it.length in 1..128 } ?: return null
            val displayName = input.readUTF().takeIf { it.length in 1..128 } ?: return null
            val platform = input.readUTF().takeIf { it.length in 1..128 } ?: return null
            val protocol = input.readInt().takeIf { it > 0 } ?: return null
            val fingerprint = input.readUTF().takeIf { it.length <= 256 }?.ifBlank { null }
            val transferPort = input.readInt().takeIf { it in 1..65_535 }
            // Older beacons end here; they can't be paired from the Nearby list.
            val pairingPort = if (input.available() >= 4) input.readInt().takeIf { it in 1..65_535 } else null
            Presence(nodeId, displayName, platform, protocol, fingerprint, transferPort, pairingPort)
        }
    }.getOrNull()

    private data class Presence(
        val nodeId: String,
        val displayName: String,
        val platform: String,
        val protocolVersion: Int,
        val fingerprint: String?,
        val transferPort: Int?,
        val pairingPort: Int?,
    )

    private companion object {
        const val MAGIC = 0x5A594E4B
        const val PRESENCE_PORT = 45_555
        const val MAX_PACKET_SIZE = 1_024
        const val RECEIVE_TIMEOUT_MILLIS = 1_000
        const val ADVERTISEMENT_INTERVAL_MILLIS = 3_000L
        val BROADCAST_ADDRESS: InetAddress = InetAddress.getByName("255.255.255.255")
    }
}
