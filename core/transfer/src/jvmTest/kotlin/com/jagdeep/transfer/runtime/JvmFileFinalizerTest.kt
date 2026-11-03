package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.TransferManifestEntry
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmFileFinalizerTest {
    @Test
    fun `writes verified content below the configured receive directory`() {
        val directory = Files.createTempDirectory("zynk-finalizer-test")
        val content = "Zynk transfer".encodeToByteArray()
        val entry = TransferManifestEntry("documents/file.txt", content.size.toLong(), content.sha256())

        val result = JvmFileFinalizer(directory).writeVerified(entry, ByteArrayInputStream(content), bufferSize = 256 * 1024)

        val completed = assertIs<FileFinalizationResult.Completed>(result)
        assertEquals(content.toList(), Files.readAllBytes(completed.path).toList())
        assertTrue(completed.path.startsWith(directory))
    }

    @Test
    fun `rejects a mismatched hash without retaining a partial file`() {
        val directory = Files.createTempDirectory("zynk-finalizer-test")
        val content = "Zynk transfer".encodeToByteArray()
        val entry = TransferManifestEntry("file.txt", content.size.toLong(), "0".repeat(64))

        val result = JvmFileFinalizer(directory).writeVerified(entry, ByteArrayInputStream(content), bufferSize = 256 * 1024)

        assertIs<FileFinalizationResult.Rejected>(result)
        Files.list(directory).use { files -> assertEquals(0, files.count()) }
    }

    @Test
    fun `renames duplicate destinations when configured without overwriting`() {
        val directory = Files.createTempDirectory("zynk-finalizer-test")
        val content = "new Zynk transfer".encodeToByteArray()
        Files.write(directory.resolve("file.txt"), "existing".encodeToByteArray())
        val entry = TransferManifestEntry("file.txt", content.size.toLong(), content.sha256())

        val result = JvmFileFinalizer(directory, FileConflictPolicy.RENAME)
            .writeVerified(entry, ByteArrayInputStream(content), bufferSize = 256 * 1024)

        val completed = assertIs<FileFinalizationResult.Completed>(result)
        assertEquals("file (1).txt", completed.path.fileName.toString())
        assertEquals("existing", Files.readString(directory.resolve("file.txt")))
        assertEquals(content.toList(), Files.readAllBytes(completed.path).toList())
    }

    @Test
    fun `preserves manifest timestamp after verified atomic finalization when supported`() {
        val directory = Files.createTempDirectory("zynk-finalizer-test")
        val content = "timestamped Zynk transfer".encodeToByteArray()
        val timestamp = 1_700_000_000_000L
        val entry = TransferManifestEntry("file.txt", content.size.toLong(), content.sha256(), timestamp)

        val completed = assertIs<FileFinalizationResult.Completed>(
            JvmFileFinalizer(directory).writeVerified(entry, ByteArrayInputStream(content), bufferSize = 256 * 1024),
        )

        assertEquals(timestamp, Files.getLastModifiedTime(completed.path).toMillis())
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
