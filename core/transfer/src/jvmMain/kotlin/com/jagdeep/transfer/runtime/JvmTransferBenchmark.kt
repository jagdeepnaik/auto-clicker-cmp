package com.jagdeep.transfer.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.TimeSource

data class JvmFileBenchmarkResult(
    val bufferSize: Int,
    val bytesWritten: Long,
    val writeElapsedMillis: Long,
    val bytesRead: Long,
    val readElapsedMillis: Long,
) {
    val writeBytesPerSecond: Double get() = rate(bytesWritten, writeElapsedMillis)
    val readBytesPerSecond: Double get() = rate(bytesRead, readElapsedMillis)

    private fun rate(bytes: Long, elapsedMillis: Long): Double =
        if (elapsedMillis <= 0) 0.0 else bytes * 1_000.0 / elapsedMillis
}

/**
 * Reproducible local disk benchmark for development machines. Results depend on host storage,
 * filesystem caching, and load; callers must report the measured values rather than targets.
 */
object JvmTransferBenchmark {
    val defaultBufferSizes = listOf(256 * 1024, 512 * 1024, 1024 * 1024)

    fun measureFileIo(directory: Path, totalBytes: Long, bufferSizes: List<Int> = defaultBufferSizes): List<JvmFileBenchmarkResult> {
        require(totalBytes >= 0) { "Benchmark size must not be negative." }
        require(Files.isDirectory(directory)) { "Benchmark directory must exist." }
        require(bufferSizes.isNotEmpty() && bufferSizes.distinct().size == bufferSizes.size)
        return bufferSizes.map { bufferSize ->
            require(bufferSize in defaultBufferSizes) { "Unsupported benchmark buffer size." }
            measureOne(directory, totalBytes, bufferSize)
        }
    }

    private fun measureOne(directory: Path, totalBytes: Long, bufferSize: Int): JvmFileBenchmarkResult {
        val path = Files.createTempFile(directory, "zynk-benchmark-", ".bin")
        try {
            val writeMark = TimeSource.Monotonic.markNow()
            val bytesWritten = writeDeterministic(path, totalBytes, bufferSize)
            val writeElapsed = writeMark.elapsedNow().inWholeMilliseconds
            val readMark = TimeSource.Monotonic.markNow()
            val bytesRead = readAll(path, bufferSize)
            return JvmFileBenchmarkResult(bufferSize, bytesWritten, writeElapsed, bytesRead, readMark.elapsedNow().inWholeMilliseconds)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun writeDeterministic(path: Path, totalBytes: Long, bufferSize: Int): Long {
        val source = DeterministicByteSource(totalBytes)
        val buffer = ByteArray(bufferSize)
        var written = 0L
        Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                written += count
            }
            output.flush()
        }
        return written
    }

    private fun readAll(path: Path, bufferSize: Int): Long {
        val buffer = ByteArray(bufferSize)
        var read = 0L
        Files.newInputStream(path).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return read
                if (count > 0) read += count
            }
        }
    }
}
