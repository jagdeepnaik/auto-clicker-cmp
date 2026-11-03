package com.jagdeep.transfer.runtime

import java.nio.file.Files
import java.nio.file.Path

/** Desktop implementation for local files selected through the native file chooser. */
class JvmTransferContentAccess : TransferContentAccess {
    override suspend fun openForRead(sourceReference: String): OpenTransferContent? = runCatching {
        val path = Path.of(sourceReference).toRealPath()
        require(Files.isRegularFile(path)) { "Selected content is not a regular file." }
        OpenTransferContent(
            displayName = path.fileName.toString(),
            sizeBytes = Files.size(path),
            source = JvmBinarySource(Files.newInputStream(path)),
        )
    }.getOrNull()
}

private class JvmBinarySource(
    private val input: java.io.InputStream,
) : TransferBinarySource {
    override fun read(destination: ByteArray): Int = input.read(destination)
    override fun close() = input.close()
}
