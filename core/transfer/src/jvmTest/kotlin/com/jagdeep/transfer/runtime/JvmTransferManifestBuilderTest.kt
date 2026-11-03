package com.jagdeep.transfer.runtime

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmTransferManifestBuilderTest {
    @Test
    fun `creates single file manifest with streamed SHA-256 and timestamp`() {
        val file = Files.createTempFile("zynk-manifest", ".txt")
        val content = "streamed content".encodeToByteArray()
        try {
            Files.write(file, content)
            val manifest = JvmTransferManifestBuilder(bufferSize = 256 * 1024).build("transfer-1", file)

            val entry = manifest.entries.single()
            assertEquals(file.fileName.toString(), entry.relativePath)
            assertEquals(content.size.toLong(), entry.sizeBytes)
            assertEquals(content.sha256(), entry.sha256)
            assertEquals(Files.getLastModifiedTime(file).toMillis(), entry.lastModifiedEpochMillis)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects directories`() {
        val directory = Files.createTempDirectory("zynk-manifest")
        try {
            assertFailsWith<IllegalArgumentException> { JvmTransferManifestBuilder().build("transfer-1", directory) }
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `builds deterministic directory manifest preserving relative paths`() {
        val directory = Files.createTempDirectory("zynk-manifest")
        try {
            val nested = Files.createDirectories(directory.resolve("nested"))
            Files.write(directory.resolve("root.txt"), "root".encodeToByteArray())
            Files.write(nested.resolve("child.txt"), "child".encodeToByteArray())

            val manifest = JvmTransferManifestBuilder(bufferSize = 256 * 1024).buildDirectory("transfer-2", directory)

            assertEquals(listOf("nested/child.txt", "root.txt"), manifest.entries.map { it.relativePath })
            assertTrue(manifest.entries.all { it.sizeBytes > 0 && it.sha256.length == 64 })
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { "%02x".format(it) }
