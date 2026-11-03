package com.jagdeep.transfer.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Android Storage Access Framework implementation; it only accepts user-selected content URIs. */
class AndroidTransferContentAccess(context: Context) : TransferContentAccess {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun openForRead(sourceReference: String): OpenTransferContent? = runCatching {
        val uri = Uri.parse(sourceReference)
        require(uri.scheme == "content") { "Only Storage Access Framework content is supported." }
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let(cursor::getString)
                        ?.takeIf(String::isNotBlank)
                        ?: "Selected file"
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                    name to size
                }
            }
        val input = requireNotNull(resolver.openInputStream(uri)) { "The selected file is no longer readable." }
        OpenTransferContent(
            displayName = metadata?.first ?: "Selected file",
            sizeBytes = metadata?.second,
            source = AndroidBinarySource(input),
        )
    }.getOrNull()
}

private class AndroidBinarySource(
    private val input: java.io.InputStream,
) : TransferBinarySource {
    override fun read(destination: ByteArray): Int = input.read(destination)
    override fun close() = input.close()
}
