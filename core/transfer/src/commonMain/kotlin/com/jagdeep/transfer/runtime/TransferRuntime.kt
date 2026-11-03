package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.ResumeEntry
import kotlin.time.TimeSource

enum class TransferState {
    DRAFT,
    REQUESTED,
    ACCEPTED,
    REJECTED,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

object TransferStateMachine {
    fun canTransition(from: TransferState, to: TransferState): Boolean = when (from) {
        TransferState.DRAFT -> to == TransferState.REQUESTED || to == TransferState.CANCELLED
        TransferState.REQUESTED -> to in setOf(TransferState.ACCEPTED, TransferState.REJECTED, TransferState.CANCELLED, TransferState.FAILED)
        TransferState.ACCEPTED -> to in setOf(TransferState.TRANSFERRING, TransferState.CANCELLED, TransferState.FAILED)
        TransferState.TRANSFERRING -> to in setOf(TransferState.PAUSED, TransferState.VERIFYING, TransferState.CANCELLED, TransferState.FAILED)
        TransferState.PAUSED -> to in setOf(TransferState.TRANSFERRING, TransferState.CANCELLED, TransferState.FAILED)
        TransferState.VERIFYING -> to in setOf(TransferState.COMPLETED, TransferState.FAILED, TransferState.CANCELLED)
        TransferState.REJECTED, TransferState.COMPLETED, TransferState.CANCELLED, TransferState.FAILED -> false
    }
}

data class TransferSession(
    val transferId: String,
    val peer: PeerIdentity,
    val manifest: TransferManifest,
    val state: TransferState = TransferState.DRAFT,
)

/**
 * Progress arrives once per TLS record (about every 16 KB), thousands of times a second on a fast
 * link. UI layers publish at most one update per [intervalMillis] of transfer time per activity;
 * the first and final updates always pass so bars start and finish exactly.
 */
fun shouldPublishProgress(lastPublishedElapsedMillis: Long?, progress: TransferProgress, intervalMillis: Long = 100): Boolean =
    lastPublishedElapsedMillis == null ||
        progress.transferredBytes >= progress.totalBytes ||
        progress.elapsedMillis - lastPublishedElapsedMillis >= intervalMillis

data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long,
    val elapsedMillis: Long,
) {
    init {
        require(transferredBytes in 0..totalBytes)
        require(totalBytes >= 0)
        require(elapsedMillis >= 0)
    }

    val fraction: Float get() = if (totalBytes == 0L) 1f else transferredBytes.toFloat() / totalBytes
    val bytesPerSecond: Double get() = if (elapsedMillis == 0L) 0.0 else transferredBytes * 1_000.0 / elapsedMillis
    val estimatedRemainingMillis: Long? get() = when {
        transferredBytes >= totalBytes -> 0
        bytesPerSecond <= 0.0 -> null
        else -> ((totalBytes - transferredBytes) * 1_000.0 / bytesPerSecond).toLong()
    }
}

/** Validates resume candidates against the immutable manifest before any platform file is opened. */
object ResumeNegotiator {
    fun acceptedEntries(manifest: TransferManifest, candidates: List<ResumeEntry>): List<ResumeEntry> {
        if (candidates.size > manifest.entries.size) return emptyList()
        val manifestByPath = manifest.entries.associateBy { it.relativePath }
        if (candidates.map(ResumeEntry::relativePath).distinct().size != candidates.size) return emptyList()
        return candidates.filter { candidate ->
            manifestByPath[candidate.relativePath]?.sizeBytes?.let { size -> candidate.verifiedBytes <= size } == true
        }
    }
}

/** Platform boundary for connection and encryption; concrete transports arrive in desktop Phase 1. */
interface PeerTransport {
    suspend fun startListening(): ListeningEndpoint
    suspend fun connect(endpoint: PeerEndpoint): PeerConnection
}

data class ListeningEndpoint(val port: Int) {
    init { require(port in 1..MAX_NETWORK_PORT) }
}

data class PeerEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank() && host.length <= MAX_HOST_LENGTH && host.none(Char::isWhitespace))
        require(port in 1..MAX_NETWORK_PORT)
    }
}

sealed interface ManualEndpointParseResult {
    data class Valid(val endpoint: PeerEndpoint) : ManualEndpointParseResult
    data class Invalid(val message: String) : ManualEndpointParseResult
}

/** Parses the manually entered `host:port` form without resolving or scanning the network. */
object ManualPeerEndpointParser {
    fun parse(value: String): ManualEndpointParseResult {
        val input = value.trim()
        if (input.isEmpty()) return ManualEndpointParseResult.Invalid("Enter an IP address or hostname and port.")
        val hostAndPort = when {
            input.startsWith('[') -> parseBracketedIpv6(input)
            else -> parseHostAndPort(input)
        } ?: return ManualEndpointParseResult.Invalid("Use host:port or [IPv6-address]:port.")
        val (host, portText) = hostAndPort
        if (!isValidHost(host)) return ManualEndpointParseResult.Invalid("The host is not valid.")
        val port = portText.toIntOrNull()
            ?.takeIf { it in 1..MAX_NETWORK_PORT }
            ?: return ManualEndpointParseResult.Invalid("Port must be between 1 and $MAX_NETWORK_PORT.")
        return ManualEndpointParseResult.Valid(PeerEndpoint(host, port))
    }

    private fun parseBracketedIpv6(input: String): Pair<String, String>? {
        val closingBracket = input.indexOf(']')
        if (closingBracket <= 1 || input.getOrNull(closingBracket + 1) != ':') return null
        val host = input.substring(1, closingBracket)
        val port = input.substring(closingBracket + 2)
        return host to port
    }

    private fun parseHostAndPort(input: String): Pair<String, String>? {
        val separator = input.lastIndexOf(':')
        if (separator <= 0 || separator == input.lastIndex) return null
        val host = input.substring(0, separator)
        if (':' in host) return null // IPv6 must be bracketed so the port is unambiguous.
        return host to input.substring(separator + 1)
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.length > MAX_HOST_LENGTH || host.any(Char::isWhitespace)) return false
        if (':' in host) return host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '%' || it == '.' || it == '-' }
        if (host.all { it.isDigit() || it == '.' }) return host.split('.').size == 4 && host.split('.').all { it.toIntOrNull() in 0..255 }
        return host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}

private const val MAX_NETWORK_PORT = 65_535
private const val MAX_HOST_LENGTH = 253

interface PeerConnection : AutoCloseable {
    val peer: PeerIdentity
    override fun close()
}

/** Deterministic, allocation-bounded source for benchmark harnesses. */
class DeterministicByteSource(private val totalBytes: Long) {
    init { require(totalBytes >= 0) }

    private var position = 0L

    fun read(destination: ByteArray): Int {
        if (position >= totalBytes) return -1
        val count = minOf(destination.size.toLong(), totalBytes - position).toInt()
        repeat(count) { index -> destination[index] = ((position + index) and 0xff).toByte() }
        position += count
        return count
    }
}

data class StreamBenchmarkResult(val bytesProcessed: Long, val elapsedMillis: Long) {
    val bytesPerSecond: Double get() = if (elapsedMillis == 0L) 0.0 else bytesProcessed * 1_000.0 / elapsedMillis
}

object StreamBenchmark {
    fun copyToBlackHole(totalBytes: Long, bufferSize: Int): StreamBenchmarkResult {
        require(bufferSize in 1..(1024 * 1024))
        val source = DeterministicByteSource(totalBytes)
        val buffer = ByteArray(bufferSize)
        val mark = TimeSource.Monotonic.markNow()
        var copied = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            copied += count
        }
        return StreamBenchmarkResult(copied, mark.elapsedNow().inWholeMilliseconds)
    }
}
