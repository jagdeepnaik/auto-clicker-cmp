package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.TransferManifestEntry
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Duration
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class JvmPartialTransferStoreTest {
    @Test
    fun `preserves verified partial bytes and finalizes after a resumed append`() {
        val directory = Files.createTempDirectory("zynk-partial-test")
        val content = "resumable transfer content".encodeToByteArray()
        val timestamp = 1_700_000_000_000L
        val entry = TransferManifestEntry("nested/file.txt", content.size.toLong(), content.sha256(), timestamp)
        try {
            val store = JvmPartialTransferStore(directory)
            val first = store.open("transfer-1", entry)
            assertIs<FileFinalizationResult.Rejected>(first.appendAndFinalize(ByteArrayInputStream(content.copyOfRange(0, 9)), 256 * 1024))

            val resumed = store.open("transfer-1", entry)
            assertEquals(9, assertNotNull(resumed.resumeEntry()).verifiedBytes)
            val result = resumed.appendAndFinalize(ByteArrayInputStream(content.copyOfRange(9, content.size)), 256 * 1024)

            val completed = assertIs<FileFinalizationResult.Completed>(result)
            assertEquals(content.toList(), Files.readAllBytes(completed.path).toList())
            assertEquals(timestamp, Files.getLastModifiedTime(completed.path).toMillis())
            assertEquals(null, resumed.resumeEntry())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `removes only stale partial transfer directories`() {
        val directory = Files.createTempDirectory("zynk-partial-test")
        try {
            val partialRoot = directory.resolve(".zynk-partials")
            val stale = Files.createDirectories(partialRoot.resolve("stale"))
            Files.writeString(stale.resolve("file.part"), "partial")
            Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(1_000L))
            val recent = Files.createDirectories(partialRoot.resolve("recent"))
            Files.writeString(recent.resolve("file.part"), "partial")

            val removed = JvmPartialTransferStore(directory).cleanupAbandoned(Duration.ofDays(1), nowMillis = 2 * 24 * 60 * 60 * 1_000L)

            assertEquals(1, removed)
            assertEquals(false, Files.exists(stale))
            assertEquals(true, Files.exists(recent))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `does not follow symbolic links during stale partial cleanup`() {
        val directory = Files.createTempDirectory("zynk-partial-test")
        val outside = Files.createTempDirectory("zynk-outside-test")
        try {
            val protectedFile = outside.resolve("keep.txt")
            Files.writeString(protectedFile, "must remain")
            val partialRoot = Files.createDirectories(directory.resolve(".zynk-partials"))
            val link = partialRoot.resolve("linked")
            runCatching { Files.createSymbolicLink(link, outside) }.getOrElse { return }

            JvmPartialTransferStore(directory).cleanupAbandoned(Duration.ofDays(1), nowMillis = Long.MAX_VALUE / 2)

            assertEquals(true, Files.exists(protectedFile))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            Files.walk(outside).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
