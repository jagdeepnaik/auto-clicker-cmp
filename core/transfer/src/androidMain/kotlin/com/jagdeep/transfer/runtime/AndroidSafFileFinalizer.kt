package com.jagdeep.transfer.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jagdeep.transfer.protocol.ProtocolValidation
import com.jagdeep.transfer.protocol.TransferManifestEntry
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

sealed interface AndroidFileFinalizationResult {
    data class Completed(val destination: Uri, val bytesWritten: Long) : AndroidFileFinalizationResult
    data class Rejected(val message: String) : AndroidFileFinalizationResult
}

interface AndroidFileFinalizer {
    fun writeVerified(
        entry: TransferManifestEntry,
        source: InputStream,
        bufferSize: Int = DEFAULT_ANDROID_FILE_BUFFER_SIZE,
        onBytesWritten: (Long) -> Unit = {},
    ): AndroidFileFinalizationResult
}

const val DEFAULT_ANDROID_FILE_BUFFER_SIZE = 512 * 1024

/**
 * Writes received bytes to app-private storage first. Only a size- and hash-verified file is
 * copied into the SAF tree selected by the receiver. SAF does not guarantee atomic moves, so a
 * failed final copy is deleted rather than left in the user's destination.
 */
class AndroidSafFileFinalizer(
    context: Context,
    destinationTree: Uri,
) : AndroidFileFinalizer {
    private val appContext = context.applicationContext
    private val destinationTree = destinationTree

    override fun writeVerified(
        entry: TransferManifestEntry,
        source: InputStream,
        bufferSize: Int,
        onBytesWritten: (Long) -> Unit,
    ): AndroidFileFinalizationResult {
        require(bufferSize in MIN_BUFFER_SIZE..MAX_BUFFER_SIZE) { "Buffer size is outside the supported range." }
        if (!ProtocolValidation.isSafeRelativePath(entry.relativePath)) return AndroidFileFinalizationResult.Rejected("Unsafe destination path.")

        val temporary = File(appContext.cacheDir, "zynk-incoming-${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            temporary.outputStream().buffered(bufferSize).use { output ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val remaining = entry.sizeBytes - written
                    if (count.toLong() > remaining) return AndroidFileFinalizationResult.Rejected("Source exceeds declared file size.")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    written += count
                    onBytesWritten(written)
                }
            }
            if (written != entry.sizeBytes) return AndroidFileFinalizationResult.Rejected("Source size does not match manifest.")
            if (digest.digest().toLowercaseHex() != entry.sha256) return AndroidFileFinalizationResult.Rejected("Source hash does not match manifest.")

            val destination = createDestination(entry.relativePath)
                ?: return AndroidFileFinalizationResult.Rejected("A file already exists or the receive folder cannot create this path.")
            return try {
                appContext.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    temporary.inputStream().buffered(bufferSize).copyTo(output, bufferSize)
                } ?: return AndroidFileFinalizationResult.Rejected("Unable to open the destination file.")
                AndroidFileFinalizationResult.Completed(destination, written)
            } catch (failure: Exception) {
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, destination) }
                AndroidFileFinalizationResult.Rejected(failure.message ?: "Unable to finalize the received file.")
            }
        } catch (failure: Exception) {
            return AndroidFileFinalizationResult.Rejected(failure.message ?: "Unable to write incoming file.")
        } finally {
            temporary.delete()
        }
    }

    private fun createDestination(relativePath: String): Uri? {
        var parent = DocumentsContract.buildDocumentUriUsingTree(
            destinationTree,
            DocumentsContract.getTreeDocumentId(destinationTree),
        )
        val segments = relativePath.split('/')
        segments.dropLast(1).forEach { name ->
            parent = findChild(parent, name) ?: DocumentsContract.createDocument(
                appContext.contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: return null
        }
        val fileName = segments.last()
        if (findChild(parent, fileName) != null) return null
        return DocumentsContract.createDocument(appContext.contentResolver, parent, "application/octet-stream", fileName)
    }

    private fun findChild(parent: Uri, displayName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return appContext.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == displayName) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(destinationTree, cursor.getString(idIndex))
                }
            }
            null
        }
    }

    private companion object { const val MIN_BUFFER_SIZE = 256 * 1024; const val MAX_BUFFER_SIZE = 1024 * 1024 }
}

private fun ByteArray.toLowercaseHex(): String = joinToString(separator = "") { "%02x".format(it) }
