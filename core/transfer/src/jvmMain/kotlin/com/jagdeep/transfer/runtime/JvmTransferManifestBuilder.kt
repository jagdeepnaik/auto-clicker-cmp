package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferManifestEntry
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Builds a one-file manifest by streaming a desktop file once; it never retains file bytes. */
class JvmTransferManifestBuilder(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    init { require(bufferSize in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) { "Buffer size is outside the supported range." } }

    fun build(transferId: String, file: Path, relativePath: String = requireNotNull(file.fileName).toString()): TransferManifest {
        require(Files.isRegularFile(file)) { "Only regular files can be transferred." }
        return TransferManifest(transferId, listOf(entryFor(file, relativePath)))
    }

    /** Builds a deterministic manifest for all regular files beneath a selected directory. */
    fun buildDirectory(transferId: String, directory: Path): TransferManifest {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Only directories can be transferred as folders." }
        val root = directory.toAbsolutePath().normalize()
        val files = Files.walk(root).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path, NOFOLLOW_LINKS) }
                .sorted()
                .toList()
        }
        require(files.isNotEmpty()) { "The selected folder does not contain any regular files." }
        require(files.size <= com.jagdeep.transfer.protocol.TransferProtocol.MAX_MANIFEST_ENTRIES) {
            "The selected folder contains too many files."
        }
        val entries = files.map { file ->
            val relativePath = root.relativize(file).joinToString("/") { segment -> segment.toString() }
            entryFor(file, relativePath)
        }
        return TransferManifest(transferId, entries)
    }

    private fun entryFor(file: Path, relativePath: String): TransferManifestEntry {
        require(ProtocolValidation.isSafeRelativePath(relativePath)) { "The selected file name cannot be transferred safely." }
        return TransferManifestEntry(
            relativePath = relativePath,
            sizeBytes = Files.size(file),
            sha256 = hash(file),
            lastModifiedEpochMillis = Files.getLastModifiedTime(file).toMillis().takeIf { it >= 0 },
        )
    }

    private fun hash(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        Files.newInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val MIN_BUFFER_SIZE = 256 * 1024
        const val DEFAULT_BUFFER_SIZE = 512 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024
    }
}
