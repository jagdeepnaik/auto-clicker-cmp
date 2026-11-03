package com.jagdeep.transfer.runtime

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal val jvmNearbyPairingCrypto = NearbyPairingCrypto(
    sha256 = { MessageDigest.getInstance("SHA-256").digest(it) },
    randomBytes = { ByteArray(it).also(SecureRandom()::nextBytes) },
)

/** Adapts a TLS connection to [PairingChannel]; blocking socket I/O runs on the IO dispatcher. */
internal class JvmPairingChannel(private val connection: JvmTlsConnection) : PairingChannel {
    override val observedPeerFingerprint: String = requireNotNull(connection.peerCertificateFingerprint) { "The device presented no certificate." }
    override val observedPeerCertificateDer: ByteArray = requireNotNull(connection.peerCertificateDer)
    override suspend fun send(message: PairingWireMessage) = withContext(Dispatchers.IO) { connection.sendPairingMessage(message) }
    override suspend fun receive(): PairingWireMessage = withContext(Dispatchers.IO) { connection.receivePairingMessage() }
}

/**
 * Always-on listener for pairing requests from the Nearby list (while the app runs). It only
 * speaks the pairing protocol, never accepts file data, and stores nothing without both users'
 * confirmation. Bounded: [MAX_SESSIONS] at once, [SESSION_MILLIS] per session, per-address cool-down.
 */
class JvmNearbyPairingListener(
    private val scope: CoroutineScope,
    private val identity: JvmTlsIdentity,
    private val localIdentity: () -> LocalPairingIdentity,
    private val trustedPeers: TrustedPeerRepository,
    private val approvals: PairingApprovalCoordinator,
    private val onTrustChanged: () -> Unit = {},
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val mutablePort = MutableStateFlow<Int?>(null)
    val port: StateFlow<Int?> = mutablePort
    private val sessions = Semaphore(MAX_SESSIONS)
    private val limiter = PairingRateLimiter(nowEpochMillis)
    private var listener: JvmTlsListener? = null
    private var acceptJob: Job? = null

    suspend fun start() {
        if (listener != null) return
        val active = JvmTlsTransport(JvmPairingTlsContext.nearby(identity)).listen(requireClientAuthentication = true)
        listener = active
        mutablePort.value = active.endpoint.port
        acceptJob = scope.launch {
            while (listener === active) {
                val connection = runCatching { active.accept() }.getOrNull() ?: continue
                val address = connection.remoteAddress
                if (!limiter.allows(address) || !sessions.tryAcquire()) {
                    runCatching {
                        connection.sendPairingMessage(
                            PairingWireMessage.Rejection(PairingProtocol.CURRENT_VERSION, "busy", "Too many pairing attempts. Try again later."),
                        )
                    }
                    connection.close()
                    continue
                }
                scope.launch {
                    try {
                        val paired = withTimeoutOrNull(SESSION_MILLIS) {
                            NearbyPairingResponder(jvmNearbyPairingCrypto, localIdentity(), trustedPeers, approvals, onTrustChanged, nowEpochMillis)
                                .handle(JvmPairingChannel(connection), approvalTimeoutMillis = SESSION_MILLIS)
                        } ?: false
                        limiter.record(address, paired)
                    } catch (_: Exception) {
                        limiter.record(address, succeeded = false)
                    } finally {
                        sessions.release()
                        connection.close()
                    }
                }
            }
        }
    }

    override fun close() {
        acceptJob?.cancel()
        listener?.close()
        listener = null
        mutablePort.value = null
    }

    private companion object {
        const val MAX_SESSIONS = 4
        const val SESSION_MILLIS = 120_000L
    }
}

/** Initiator side: connect to a nearby device's pairing port and run the comparison. */
internal suspend fun jvmPairWithNearby(
    identity: JvmTlsIdentity,
    local: LocalPairingIdentity,
    trustedPeers: TrustedPeerRepository,
    host: String,
    port: Int,
    confirmCode: suspend (String, String) -> Boolean,
): PairingConnectionResult {
    val connection = try {
        JvmTlsTransport(JvmPairingTlsContext.nearby(identity)).connect(PeerEndpoint(host, port))
    } catch (_: Exception) {
        return PairingConnectionResult.Rejected("Couldn't reach that device. Check that Zynk is open on it and both are on the same network.")
    }
    return try {
        withTimeoutOrNull(120_000L) {
            NearbyPairingInitiator(jvmNearbyPairingCrypto, local, trustedPeers).pair(JvmPairingChannel(connection), confirmCode)
        } ?: PairingConnectionResult.Rejected("Pairing timed out. Try again.")
    } catch (_: Exception) {
        PairingConnectionResult.Rejected("The pairing connection ended early. Try again.")
    } finally {
        withContext(Dispatchers.IO) { runCatching { connection.close() } }
    }
}
