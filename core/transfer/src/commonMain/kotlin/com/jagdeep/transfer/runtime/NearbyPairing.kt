package com.jagdeep.transfer.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pairing from the Nearby devices list by numeric comparison (see private/plans/NEARBY_PAIRING_PLAN.md
 * and the threat model). Runs over mutual TLS without pinning: authenticity comes from both users
 * comparing a 6-digit code bound to the certificates each side actually observed.
 */

/** One pairing connection. Fingerprint and certificate are what TLS observed, never what the peer claims. */
interface PairingChannel {
    val observedPeerFingerprint: String
    val observedPeerCertificateDer: ByteArray
    suspend fun send(message: PairingWireMessage)
    suspend fun receive(): PairingWireMessage
}

/** This device's identity as it is claimed during pairing. */
data class LocalPairingIdentity(val deviceId: String, val displayName: String, val fingerprint: String)

/** Platform-supplied primitives keep this logic common and testable. */
class NearbyPairingCrypto(
    private val sha256: (ByteArray) -> ByteArray,
    private val randomBytes: (Int) -> ByteArray,
) {
    fun newSessionId(): String = randomBytes(16).toLowerHex()
    fun newNonce(): ByteArray = randomBytes(32)

    fun commitment(sessionId: String, nonce: ByteArray): String =
        sha256("zynk-sas-commit".encodeToByteArray() + sessionId.encodeToByteArray() + nonce).toLowerHex()

    /** Ordered as (initiator, responder) on both sides, so matching inputs give matching codes. */
    fun transcript(sessionId: String, initiatorFingerprint: String, responderFingerprint: String, initiatorNonce: ByteArray, responderNonce: ByteArray): String =
        sha256(
            "zynk-sas-v1".encodeToByteArray() + sessionId.encodeToByteArray() +
                initiatorFingerprint.encodeToByteArray() + responderFingerprint.encodeToByteArray() +
                initiatorNonce + responderNonce,
        ).toLowerHex()

    /** First four transcript bytes as an unsigned integer, modulo one million: a 6-digit code. */
    fun code(transcript: String): String {
        val value = transcript.take(8).toLong(16) % 1_000_000
        return value.toString().padStart(6, '0')
    }
}

/** Starts pairing with a nearby device and drives the comparison on this side. */
class NearbyPairingInitiator(
    private val crypto: NearbyPairingCrypto,
    private val local: LocalPairingIdentity,
    private val trustedPeers: TrustedPeerRepository,
) {
    /**
     * [confirmCode] shows the code and the other device's name and returns the user's decision.
     * Nothing is stored unless both users confirm and the reciprocal acknowledgement arrives.
     */
    suspend fun pair(channel: PairingChannel, confirmCode: suspend (peerName: String, code: String) -> Boolean): PairingConnectionResult {
        val observed = channel.observedPeerFingerprint
        if (observed == local.fingerprint) return PairingConnectionResult.Rejected("That is this device.")
        val sessionId = crypto.newSessionId()
        val nonce = crypto.newNonce()
        channel.send(PairingWireMessage.SasHello(VERSION, sessionId, local.deviceId, local.displayName, crypto.commitment(sessionId, nonce)))

        val reply = when (val message = channel.receive()) {
            is PairingWireMessage.SasNonce -> message.takeIf { it.sessionId == sessionId } ?: return invalid()
            is PairingWireMessage.Rejection -> return PairingConnectionResult.Rejected(message.reason)
            else -> return invalid()
        }
        if (!claimMatchesCertificate(reply.deviceId, observed)) return PairingConnectionResult.Rejected("The other device's identity doesn't match its certificate.")
        channel.send(PairingWireMessage.SasReveal(VERSION, sessionId, nonce.toLowerHex()))

        val transcript = crypto.transcript(sessionId, local.fingerprint, observed, nonce, reply.nonce.hexToBytes())
        val peer = TrustedPeer(reply.deviceId, reply.displayName, observed, channel.observedPeerCertificateDer)
        val accepted = confirmCode(peer.displayName, crypto.code(transcript))
        channel.send(PairingWireMessage.Confirmation(VERSION, sessionId, transcript, accepted))
        if (!accepted) return PairingConnectionResult.Rejected("Pairing cancelled.")

        when (val message = channel.receive()) {
            is PairingWireMessage.Confirmation ->
                if (message.sessionId != sessionId || message.transcriptHash != transcript) return invalid()
                else if (!message.accepted) return PairingConnectionResult.Rejected("${peer.displayName} declined, or the codes didn't match.")
            is PairingWireMessage.Rejection -> return PairingConnectionResult.Rejected(message.reason)
            else -> return invalid()
        }
        channel.send(PairingWireMessage.Acknowledgement(VERSION, sessionId, transcript))
        val final = channel.receive() as? PairingWireMessage.Acknowledgement
        if (final?.sessionId != sessionId || final.transcriptHash != transcript) {
            return PairingConnectionResult.Rejected("${peer.displayName} didn't finish pairing. Try again.")
        }
        trustedPeers.upsertVerified(peer)
        return PairingConnectionResult.Paired(peer)
    }

    private fun invalid() = PairingConnectionResult.Rejected("The other device sent an invalid pairing response.")
}

/** Answers a nearby pairing request; the user confirms the code in the incoming-pairing dialog. */
class NearbyPairingResponder(
    private val crypto: NearbyPairingCrypto,
    private val local: LocalPairingIdentity,
    private val trustedPeers: TrustedPeerRepository,
    private val approvals: PairingApprovalCoordinator,
    private val onTrustChanged: () -> Unit = {},
    private val nowEpochMillis: () -> Long,
) {
    /** True when this session ended paired; false for declines, mismatches and protocol errors. */
    suspend fun handle(channel: PairingChannel, approvalTimeoutMillis: Long = TransferTimeouts.APPROVAL_MILLIS): Boolean {
        val observed = channel.observedPeerFingerprint
        val hello = channel.receive() as? PairingWireMessage.SasHello ?: return reject(channel, "unknown", "Unexpected pairing message.")
        val sessionId = hello.sessionId
        if (observed == local.fingerprint || !claimMatchesCertificate(hello.deviceId, observed)) {
            return reject(channel, sessionId, "The device identity doesn't match its certificate.")
        }
        val nonce = crypto.newNonce()
        channel.send(PairingWireMessage.SasNonce(VERSION, sessionId, local.deviceId, local.displayName, nonce.toLowerHex()))

        val reveal = channel.receive() as? PairingWireMessage.SasReveal ?: return reject(channel, sessionId, "Unexpected pairing message.")
        val initiatorNonce = reveal.nonce.hexToBytes()
        if (reveal.sessionId != sessionId || crypto.commitment(sessionId, initiatorNonce) != hello.commitment) {
            return reject(channel, sessionId, "The pairing exchange didn't verify.")
        }
        val transcript = crypto.transcript(sessionId, observed, local.fingerprint, initiatorNonce, nonce)
        val peer = TrustedPeer(hello.deviceId, hello.displayName, observed, channel.observedPeerCertificateDer)
        val accepted = approvals.awaitDecision(IncomingPairingRequest(sessionId, peer, crypto.code(transcript)), approvalTimeoutMillis)
        channel.send(PairingWireMessage.Confirmation(VERSION, sessionId, transcript, accepted))
        if (!accepted) return false

        val remote = channel.receive() as? PairingWireMessage.Confirmation
        if (remote?.sessionId != sessionId || remote.transcriptHash != transcript || !remote.accepted) return false
        val ack = channel.receive() as? PairingWireMessage.Acknowledgement
        if (ack?.sessionId != sessionId || ack.transcriptHash != transcript) return false

        // The other side stores only after our final acknowledgement; journal the uncertain window first.
        (trustedPeers as? PairingRecoveryStore)?.recordPendingPairingRecovery(PendingPairingRecovery(peer, sessionId, transcript, nowEpochMillis()))
        trustedPeers.upsertVerified(peer)
        onTrustChanged()
        channel.send(PairingWireMessage.Acknowledgement(VERSION, sessionId, transcript))
        return true
    }

    private suspend fun reject(channel: PairingChannel, sessionId: String, reason: String): Boolean {
        runCatching { channel.send(PairingWireMessage.Rejection(VERSION, sessionId, reason)) }
        return false
    }
}

/**
 * Refuses an address for [cooldownMillis] after [maxFailures] consecutive failed attempts, so
 * repeated guessing at the 6-digit code is impractical. A success clears the address.
 */
class PairingRateLimiter(
    private val nowEpochMillis: () -> Long,
    private val maxFailures: Int = 3,
    private val cooldownMillis: Long = 10 * 60_000L,
) {
    private val lock = Mutex()
    private val failures = mutableMapOf<String, Pair<Int, Long>>()

    suspend fun allows(address: String): Boolean = lock.withLock {
        val (count, last) = failures[address] ?: return@withLock true
        if (nowEpochMillis() - last >= cooldownMillis) {
            failures.remove(address)
            true
        } else count < maxFailures
    }

    suspend fun record(address: String, succeeded: Boolean) = lock.withLock {
        if (succeeded) failures.remove(address)
        else failures[address] = ((failures[address]?.first ?: 0) + 1) to nowEpochMillis()
    }
}

/** A claimed device ID must end with the first 24 hex characters of the observed certificate fingerprint. */
internal fun claimMatchesCertificate(deviceId: String, observedFingerprint: String) = deviceId.endsWith(observedFingerprint.take(24))

private const val VERSION = PairingProtocol.CURRENT_VERSION

private fun ByteArray.toLowerHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
