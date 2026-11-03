package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmTransferBenchmarkTest {
    @Test
    fun `given benchmark directory when file IO is measured then each requested buffer reports exact bytes`() {
        val directory = Files.createTempDirectory("zynk-benchmark-test")
        try {
            val results = JvmTransferBenchmark.measureFileIo(directory, totalBytes = 1_025, bufferSizes = listOf(256 * 1024, 512 * 1024))

            assertEquals(listOf(256 * 1024, 512 * 1024), results.map(JvmFileBenchmarkResult::bufferSize))
            assertTrue(results.all { it.bytesWritten == 1_025L && it.bytesRead == 1_025L })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
