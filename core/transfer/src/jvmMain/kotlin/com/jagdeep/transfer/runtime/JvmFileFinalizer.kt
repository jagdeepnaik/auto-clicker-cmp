package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation
import com.jagdeep.transfer.protocol.TransferManifestEntry
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID

sealed interface FileFinalizationResult {
    data class Completed(val path: Path, val bytesWritten: Long) : FileFinalizationResult
    data class Rejected(val message: String) : FileFinalizationResult
}

enum class FileConflictPolicy { REJECT, RENAME }

/** JVM-only filesystem writer. Its caller supplies an authenticated stream. */
class JvmFileFinalizer(
    private val receiveDirectory: Path,
    private val conflictPolicy: FileConflictPolicy = FileConflictPolicy.REJECT,
) {
    init { require(Files.isDirectory(receiveDirectory)) { "Receive directory must exist." } }

    fun writeVerified(
        entry: TransferManifestEntry,
        source: InputStream,
        bufferSize: Int = DefaultBufferSize,
        onBytesWritten: (Long) -> Unit = {},
    ): FileFinalizationResult {
        require(bufferSize in MinBufferSize..MaxBufferSize) { "Buffer size is outside the supported range." }
        if (!ProtocolValidation.isSafeRelativePath(entry.relativePath)) return FileFinalizationResult.Rejected("Unsafe destination path.")

        val root = receiveDirectory.toAbsolutePath().normalize()
        val requestedDestination = root.resolve(LocalFileNames.forLocalFilesystem(entry.relativePath, isWindowsHost)).normalize()
        if (!requestedDestination.startsWith(root)) return FileFinalizationResult.Rejected("Destination escapes the receive directory.")
        val destination = when {
            !Files.exists(requestedDestination) -> requestedDestination
            conflictPolicy == FileConflictPolicy.REJECT -> return FileFinalizationResult.Rejected("A file already exists at the destination.")
            else -> nextAvailableDestination(requestedDestination)
        }

        Files.createDirectories(requireNotNull(destination.parent))
        val partial = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val remaining = entry.sizeBytes - written
                    if (count.toLong() > remaining) return FileFinalizationResult.Rejected("Source exceeds declared file size.")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    written += count
                    onBytesWritten(written)
                }
                output.flush()
            }
            if (written != entry.sizeBytes) return FileFinalizationResult.Rejected("Source size does not match manifest.")
            if (digest.digest().toLowercaseHex() != entry.sha256) return FileFinalizationResult.Rejected("Source hash does not match manifest.")
            moveWithoutOverwrite(partial, destination)
            entry.lastModifiedEpochMillis?.let { timestamp ->
                runCatching { Files.setLastModifiedTime(destination, FileTime.fromMillis(timestamp)) }
            }
            return FileFinalizationResult.Completed(destination, written)
        } catch (failure: Exception) {
            return FileFinalizationResult.Rejected(failure.toReceiverSaveMessage())
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun moveWithoutOverwrite(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun nextAvailableDestination(destination: Path): Path {
        val name = destination.fileName.toString()
        val extensionIndex = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val baseName = name.substring(0, extensionIndex)
        val extension = name.substring(extensionIndex)
        var ordinal = 1
        while (true) {
            val candidate = destination.resolveSibling("$baseName ($ordinal)$extension")
            if (!Files.exists(candidate)) return candidate
            ordinal++
        }
    }

    private companion object {
        const val MinBufferSize = 256 * 1024
        const val DefaultBufferSize = 512 * 1024
        const val MaxBufferSize = 1024 * 1024
    }
}

private fun ByteArray.toLowercaseHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

internal val isWindowsHost: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
