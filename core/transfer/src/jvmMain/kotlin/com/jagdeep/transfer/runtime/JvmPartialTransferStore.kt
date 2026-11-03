package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation
import com.jagdeep.transfer.protocol.ResumeEntry
import com.jagdeep.transfer.protocol.TransferManifestEntry
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.time.Duration
import java.security.MessageDigest
import java.util.Properties

/**
 * JVM partial-file storage for resumable inbound transfers. Metadata is intentionally local and
 * contains only validated transfer identifiers and integrity values, never file contents.
 */
class JvmPartialTransferStore(private val receiveDirectory: Path) {
    init { require(Files.isDirectory(receiveDirectory)) { "Receive directory must exist." } }

    fun open(transferId: String, entry: TransferManifestEntry): JvmPartialTransfer {
        require(transferId.isNotBlank() && transferId.length <= 128) { "Invalid transfer ID." }
        require(ProtocolValidation.isSafeRelativePath(entry.relativePath)) { "Unsafe destination path." }
        val root = receiveDirectory.toAbsolutePath().normalize()
        val destination = root.resolve(LocalFileNames.forLocalFilesystem(entry.relativePath, isWindowsHost)).normalize()
        require(destination.startsWith(root)) { "Destination escapes the receive directory." }
        val partialDirectory = root.resolve(PARTIAL_DIRECTORY).resolve(transferId.sha256())
        Files.createDirectories(partialDirectory)
        val partialBaseName = entry.relativePath.sha256()
        val partialPath = partialDirectory.resolve("$partialBaseName.part")
        val metadataPath = partialDirectory.resolve("$partialBaseName.properties")
        val verified = loadAndVerify(metadataPath, partialPath, transferId, entry)
        return JvmPartialTransfer(destination, partialPath, metadataPath, transferId, entry, verified)
    }

    fun discard(transferId: String, entry: TransferManifestEntry) {
        val transfer = open(transferId, entry)
        Files.deleteIfExists(transfer.partialPath)
        Files.deleteIfExists(transfer.metadataPath)
    }

    /** Removes only complete partial-transfer directories that have been untouched past [maxAge]. */
    fun cleanupAbandoned(maxAge: Duration = DEFAULT_MAX_PARTIAL_AGE, nowMillis: Long = System.currentTimeMillis()): Int {
        require(!maxAge.isNegative && !maxAge.isZero) { "Partial transfer age must be positive." }
        val root = receiveDirectory.resolve(PARTIAL_DIRECTORY)
        if (!Files.isDirectory(root)) return 0
        val cutoff = nowMillis - maxAge.toMillis()
        return Files.list(root).use { directories ->
            directories
                .filter { candidate -> Files.isDirectory(candidate, NOFOLLOW_LINKS) && Files.getLastModifiedTime(candidate, NOFOLLOW_LINKS).toMillis() < cutoff }
                .map { candidate ->
                    Files.walk(candidate).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                    1
                }
                .reduce(0, Int::plus)
        }
    }

    private fun loadAndVerify(
        metadataPath: Path,
        partialPath: Path,
        transferId: String,
        entry: TransferManifestEntry,
    ): ResumeEntry? {
        if (!Files.exists(metadataPath) || !Files.exists(partialPath)) {
            Files.deleteIfExists(metadataPath)
            Files.deleteIfExists(partialPath)
            return null
        }
        val properties = Properties().apply { Files.newInputStream(metadataPath).use(::load) }
        if (properties.getProperty("transferId") != transferId ||
            properties.getProperty("relativePath") != entry.relativePath ||
            properties.getProperty("sizeBytes") != entry.sizeBytes.toString() ||
            properties.getProperty("sha256") != entry.sha256
        ) {
            Files.deleteIfExists(metadataPath)
            Files.deleteIfExists(partialPath)
            return null
        }
        val bytes = properties.getProperty("verifiedBytes")?.toLongOrNull() ?: return discardInvalid(metadataPath, partialPath)
        val declaredPrefix = properties.getProperty("prefixSha256") ?: return discardInvalid(metadataPath, partialPath)
        if (bytes !in 0..entry.sizeBytes || !ProtocolValidation.isSha256(declaredPrefix)) return discardInvalid(metadataPath, partialPath)
        val actualPrefix = partialPath.sha256OfPrefix(bytes)
        return if (actualPrefix == declaredPrefix) ResumeEntry(entry.relativePath, bytes, actualPrefix) else {
            Files.deleteIfExists(metadataPath)
            Files.deleteIfExists(partialPath)
            null
        }
    }

    private fun discardInvalid(metadataPath: Path, partialPath: Path): Nothing? {
        Files.deleteIfExists(metadataPath)
        Files.deleteIfExists(partialPath)
        return null
    }

    private companion object {
        const val PARTIAL_DIRECTORY = ".zynk-partials"
        val DEFAULT_MAX_PARTIAL_AGE: Duration = Duration.ofDays(7)
    }
}

class JvmPartialTransfer internal constructor(
    val destination: Path,
    internal val partialPath: Path,
    internal val metadataPath: Path,
    private val transferId: String,
    private val entry: TransferManifestEntry,
    private var verifiedResume: ResumeEntry?,
) {
    fun resumeEntry(): ResumeEntry? = verifiedResume

    /** Appends exactly the remaining bytes, verifies the completed hash, and atomically finalizes. */
    /** [onBytesWritten] receives the bytes appended in this call so far (excluding resumed bytes). */
    fun appendAndFinalize(
        source: InputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onBytesWritten: (Long) -> Unit = {},
    ): FileFinalizationResult {
        require(bufferSize in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) { "Buffer size is outside the supported range." }
        if (Files.exists(destination)) return FileFinalizationResult.Rejected("A file already exists at the destination.")
        val existingBytes = verifiedResume?.verifiedBytes ?: 0L
        val digest = MessageDigest.getInstance("SHA-256")
        if (existingBytes > 0) Files.newInputStream(partialPath).use { it.copyDigestTo(digest, existingBytes) }
        var written = existingBytes
        var savedBytes = existingBytes
        var savedAtNanos = System.nanoTime()
        try {
            Files.newOutputStream(partialPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { output ->
                val buffer = ByteArray(bufferSize)
                try {
                while (written < entry.sizeBytes) {
                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), entry.sizeBytes - written).toInt())
                    if (count < 0) return FileFinalizationResult.Rejected("Source ended before the remaining file bytes.")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    written += count
                    // The resume point is saved every few MB or once a second, not every chunk: each
                    // save replaces a file, which is slow and, on Windows, briefly lockable by antivirus.
                    if (written - savedBytes >= RESUME_SAVE_BYTES || System.nanoTime() - savedAtNanos >= RESUME_SAVE_NANOS) {
                        output.flush() // the resume point must never claim bytes that are not on disk yet
                        saveResumePoint(written, digest)
                        savedBytes = written
                        savedAtNanos = System.nanoTime()
                    }
                    onBytesWritten(written - existingBytes)
                }
                } finally {
                    // Stopped early (sender ended, connection dropped, or an error): record exactly
                    // how far this got, so the next attempt resumes from here.
                    if (written < entry.sizeBytes && written > savedBytes) {
                        runCatching { output.flush() }
                        saveResumePoint(written, digest)
                    }
                }
                output.flush()
            }
            if (source.read() >= 0) return FileFinalizationResult.Rejected("Source exceeds the remaining file size.")
            if (digest.digest().toHex() != entry.sha256) {
                discard()
                return FileFinalizationResult.Rejected("Source hash does not match manifest.")
            }
            retryWhileLocked { moveWithoutOverwrite(partialPath, destination) }
            entry.lastModifiedEpochMillis?.let { timestamp ->
                runCatching { Files.setLastModifiedTime(destination, FileTime.fromMillis(timestamp)) }
            }
            Files.deleteIfExists(metadataPath)
            verifiedResume = null
            return FileFinalizationResult.Completed(destination, written)
        } catch (failure: Exception) {
            return FileFinalizationResult.Rejected(failure.toReceiverSaveMessage())
        }
    }

    fun discard() {
        Files.deleteIfExists(partialPath)
        Files.deleteIfExists(metadataPath)
        verifiedResume = null
    }

    /**
     * Best effort: the resume point only lets an interrupted transfer continue, so failing to save
     * it (for example a file briefly locked by antivirus on Windows) never aborts the transfer; an
     * interruption would then resume from the last point that was saved.
     */
    private fun saveResumePoint(bytes: Long, digest: MessageDigest) {
        runCatching { retryWhileLocked { saveMetadata(bytes, digest.digestCloneOrRehash(partialPath, bytes)) } }
    }

    private fun saveMetadata(bytes: Long, prefixHash: String) {
        val properties = Properties().apply {
            setProperty("transferId", transferId)
            setProperty("relativePath", entry.relativePath)
            setProperty("sizeBytes", entry.sizeBytes.toString())
            setProperty("sha256", entry.sha256)
            setProperty("verifiedBytes", bytes.toString())
            setProperty("prefixSha256", prefixHash)
        }
        val temporary = Files.createTempFile(metadataPath.parent, ".${metadataPath.fileName}.", ".part")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, null) }
            try { Files.move(temporary, metadataPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, metadataPath, StandardCopyOption.REPLACE_EXISTING) }
            verifiedResume = ResumeEntry(entry.relativePath, bytes, prefixHash)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveWithoutOverwrite(source: Path, target: Path) {
        Files.createDirectories(requireNotNull(target.parent))
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source, target) }
    }

    private companion object {
        const val RESUME_SAVE_BYTES = 8L * 1024 * 1024
        const val RESUME_SAVE_NANOS = 1_000_000_000L
        const val MIN_BUFFER_SIZE = 256 * 1024
        const val DEFAULT_BUFFER_SIZE = 512 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024
    }
}

private fun Path.sha256OfPrefix(length: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input -> input.copyDigestTo(digest, length) }
    return digest.digest().toHex()
}

private fun InputStream.copyDigestTo(digest: MessageDigest, expectedLength: Long) {
    val buffer = ByteArray(256 * 1024)
    var copied = 0L
    while (copied < expectedLength) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), expectedLength - copied).toInt())
        require(count >= 0) { "Partial file is shorter than its metadata." }
        if (count > 0) { digest.update(buffer, 0, count); copied += count }
    }
}

private fun MessageDigest.digestCloneOrRehash(file: Path, length: Long): String = try {
    (clone() as MessageDigest).digest().toHex()
} catch (_: CloneNotSupportedException) {
    file.sha256OfPrefix(length)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toHex()

/**
 * Retries [action] while Windows reports a transient lock: antivirus, indexing or backup tools
 * often hold a newly written file open for a moment, making a replace or move fail with
 * "access denied". Other errors are rethrown immediately.
 */
internal fun <T> retryWhileLocked(attempts: Int = 6, initialDelayMillis: Long = 50, action: () -> T): T {
    var delay = initialDelayMillis
    repeat(attempts - 1) {
        try {
            return action()
        } catch (locked: java.nio.file.AccessDeniedException) {
            Thread.sleep(delay)
        } catch (locked: java.nio.file.FileSystemException) {
            if (locked is java.nio.file.NoSuchFileException || locked is java.nio.file.FileAlreadyExistsException) throw locked
            Thread.sleep(delay)
        }
        delay = (delay * 2).coerceAtMost(800)
    }
    return action()
}
