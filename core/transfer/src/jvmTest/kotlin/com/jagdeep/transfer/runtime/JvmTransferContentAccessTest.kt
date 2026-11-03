package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmTransferContentAccessTest {
    @Test
    fun `opens an explicitly selected regular file as a sequential binary source`() = kotlinx.coroutines.runBlocking {
        val file = Files.createTempFile("zynk-content", ".txt")
        try {
            Files.write(file, "verified stream".encodeToByteArray())
            val content = requireNotNull(JvmTransferContentAccess().openForRead(file.toString()))
            content.source.use { source ->
                val bytes = ByteArray(64)
                val count = source.read(bytes)
                assertEquals("verified stream".length, count)
                assertContentEquals("verified stream".encodeToByteArray(), bytes.copyOf(count))
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `rejects a missing or non-file source`() = kotlinx.coroutines.runBlocking {
        assertNull(JvmTransferContentAccess().openForRead("/path/that/does/not/exist"))
    }
}
