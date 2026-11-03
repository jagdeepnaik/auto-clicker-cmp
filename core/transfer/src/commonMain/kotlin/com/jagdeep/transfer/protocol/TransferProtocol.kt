@file:OptIn(ExperimentalSerializationApi::class)

package com.jagdeep.transfer.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Protocol constants shared by every Zynk implementation. */
object TransferProtocol {
    const val CURRENT_VERSION = 1
    const val MAX_CONTROL_MESSAGE_BYTES = 64 * 1024
    const val MAX_MANIFEST_ENTRIES = 10_000
    const val MAX_RELATIVE_PATH_LENGTH = 1_024
    const val SHA_256_HEX_LENGTH = 64
}

@Serializable
data class PeerIdentity(
    val deviceId: String,
    val displayName: String,
    val publicKeyFingerprint: String,
    val capabilities: Set<TransferCapability> = emptySet(),
) {
    init {
        require(deviceId.isNotBlank() && deviceId.length <= 128)
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(ProtocolValidation.isSha256(publicKeyFingerprint))
        require(capabilities.size <= TransferCapability.entries.size)
    }
}

/** Features explicitly negotiated after TLS peer authentication; absent means unsupported. */
@Serializable
enum class TransferCapability {
    RESUMABLE_MANIFESTS,
    BATCH_MANIFESTS,
}

object TransferCapabilityNegotiator {
    fun mutuallySupported(local: PeerIdentity, remote: PeerIdentity): Set<TransferCapability> =
        local.capabilities.intersect(remote.capabilities)

    fun supports(local: PeerIdentity, remote: PeerIdentity, capability: TransferCapability): Boolean =
        capability in mutuallySupported(local, remote)
}

@Serializable
data class TransferManifest(
    val transferId: String,
    val entries: List<TransferManifestEntry>,
    /**
     * Present only for folder-sync transfers. Never encoded when null, so ordinary transfers are
     * byte-for-byte unchanged; a peer that predates sync rejects the unknown field instead of
     * mis-handling a sync.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sync: SyncTarget? = null,
) {
    init {
        require(transferId.isNotBlank() && transferId.length <= 128)
        // A Mirror sync may only remove files, so a sync manifest may have no entries.
        require((entries.isNotEmpty() || sync?.remove?.isNotEmpty() == true) && entries.size <= TransferProtocol.MAX_MANIFEST_ENTRIES)
        require(entries.map(TransferManifestEntry::relativePath).distinct().size == entries.size)
        var totalBytes = 0L
        entries.forEach { entry ->
            require(entry.sizeBytes <= Long.MAX_VALUE - totalBytes) { "Manifest total size is too large." }
            totalBytes += entry.sizeBytes
        }
    }
}

/**
 * Marks a transfer as part of a folder sync. The receiver keeps it in that sync's own folder,
 * may replace files there (moving old versions to trash first) and moves [remove] paths to trash.
 */
@Serializable
data class SyncTarget(
    val pairId: String,
    /** Folder name on the receiver, inside its sync area. A single safe path segment. */
    val name: String,
    /** Mirror mode: paths (relative to the sync folder) the source no longer has. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val remove: List<String> = emptyList(),
) {
    init {
        require(pairId.isNotBlank() && pairId.length <= 64 && pairId.all { it.isLetterOrDigit() || it == '-' })
        require(name.length in 1..64 && ProtocolValidation.isSafeRelativePath(name) && '/' !in name && !name.startsWith("."))
        require(remove.size <= MAX_REMOVALS && remove.all(ProtocolValidation::isSafeRelativePath))
    }

    companion object {
        const val MAX_REMOVALS = 1_000
    }
}

@Serializable
data class TransferManifestEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val lastModifiedEpochMillis: Long? = null,
) {
    init {
        require(ProtocolValidation.isSafeRelativePath(relativePath))
        require(sizeBytes >= 0)
        require(ProtocolValidation.isSha256(sha256))
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis >= 0)
    }
}

@Serializable
sealed interface ControlMessage {
    val protocolVersion: Int
    val requestId: String

    @Serializable
    @SerialName("hello")
    data class Hello(
        override val protocolVersion: Int,
        override val requestId: String,
        val peer: PeerIdentity,
    ) : ControlMessage

    @Serializable
    @SerialName("transfer_request")
    data class TransferRequest(
        override val protocolVersion: Int,
        override val requestId: String,
        val manifest: TransferManifest,
    ) : ControlMessage

    @Serializable
    @SerialName("transfer_decision")
    data class TransferDecision(
        override val protocolVersion: Int,
        override val requestId: String,
        val transferId: String,
        val accepted: Boolean,
        val reason: String? = null,
    ) : ControlMessage

    @Serializable
    @SerialName("cancel")
    data class Cancel(
        override val protocolVersion: Int,
        override val requestId: String,
        val transferId: String,
        val reason: String? = null,
    ) : ControlMessage

    @Serializable
    @SerialName("transfer_complete")
    data class TransferComplete(
        override val protocolVersion: Int,
        override val requestId: String,
        val transferId: String,
        val bytesTransferred: Long,
    ) : ControlMessage {
        init { require(bytesTransferred >= 0) }
    }

    @Serializable
    @SerialName("transfer_result")
    data class TransferResult(
        override val protocolVersion: Int,
        override val requestId: String,
        val transferId: String,
        val completed: Boolean,
        val errorCode: TransferErrorCode? = null,
        val message: String? = null,
    ) : ControlMessage

    @Serializable
    @SerialName("resume_request")
    data class ResumeRequest(
        override val protocolVersion: Int,
        override val requestId: String,
        val transferId: String,
        val entries: List<ResumeEntry>,
    ) : ControlMessage

    @Serializable
    @SerialName("resume_decision")
    data class ResumeDecision(
        override val protocolVersion: Int,
        override val requestId: String,
        val transferId: String,
        val acceptedEntries: List<ResumeEntry>,
    ) : ControlMessage

    @Serializable
    @SerialName("protocol_error")
    data class ProtocolError(
        override val protocolVersion: Int,
        override val requestId: String,
        val code: TransferErrorCode,
        val message: String,
    ) : ControlMessage
}

/** A resumable prefix must be bound to both its byte count and its SHA-256 digest. */
@Serializable
data class ResumeEntry(
    val relativePath: String,
    val verifiedBytes: Long,
    val prefixSha256: String,
) {
    init {
        require(ProtocolValidation.isSafeRelativePath(relativePath))
        require(verifiedBytes >= 0)
        require(ProtocolValidation.isSha256(prefixSha256))
    }
}

/** The one canonical JSON codec for control-plane messages; binary file contents never use it. */
object ControlMessageCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(message: ControlMessage): ByteArray {
        val bytes = json.encodeToString(ControlMessage.serializer(), message).encodeToByteArray()
        require(bytes.size <= TransferProtocol.MAX_CONTROL_MESSAGE_BYTES) { "Control message exceeds protocol limit." }
        return bytes
    }

    fun decode(bytes: ByteArray): ControlMessage {
        require(bytes.size <= TransferProtocol.MAX_CONTROL_MESSAGE_BYTES) { "Control message exceeds protocol limit." }
        return json.decodeFromString(ControlMessage.serializer(), bytes.decodeToString())
    }
}

@Serializable
enum class TransferErrorCode {
    INCOMPATIBLE_PROTOCOL,
    UNTRUSTED_PEER,
    RECEIVER_REJECTED,
    INVALID_METADATA,
    INVALID_PATH,
    INSUFFICIENT_STORAGE,
    INTEGRITY_MISMATCH,
    TRANSFER_CANCELLED,
    CONNECTION_LOST,
    INTERNAL_ERROR,
}

object ProtocolValidation {
    private val sha256Pattern = Regex("^[0-9a-f]{${TransferProtocol.SHA_256_HEX_LENGTH}}$")
    private val windowsDrivePrefix = Regex("^[A-Za-z]:")

    fun isSha256(value: String): Boolean = sha256Pattern.matches(value)

    /** Rejects absolute paths, traversal segments, control characters, and platform separators. */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > TransferProtocol.MAX_RELATIVE_PATH_LENGTH) return false
        if (path.startsWith('/') || path.startsWith('\\') || windowsDrivePrefix.containsMatchIn(path)) return false
        if ('\\' in path || path.any { it.code < 32 }) return false
        val segments = path.split('/')
        return segments.all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
    }

    fun validate(message: ControlMessage): TransferErrorCode? = when {
        message.protocolVersion != TransferProtocol.CURRENT_VERSION -> TransferErrorCode.INCOMPATIBLE_PROTOCOL
        message.requestId.isBlank() || message.requestId.length > 128 -> TransferErrorCode.INVALID_METADATA
        else -> null
    }
}
