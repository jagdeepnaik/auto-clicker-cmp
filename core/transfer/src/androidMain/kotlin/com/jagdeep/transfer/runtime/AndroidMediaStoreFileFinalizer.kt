package com.jagdeep.transfer.runtime

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.jagdeep.transfer.protocol.ProtocolValidation
import com.jagdeep.transfer.protocol.TransferManifestEntry
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Finalizes verified files in the user-visible Downloads/Zynk directory on Android 10 and later. */
class AndroidMediaStoreFileFinalizer(context: Context) : AndroidFileFinalizer {
    private val appContext = context.applicationContext

    override fun writeVerified(entry: TransferManifestEntry, source: InputStream, bufferSize: Int, onBytesWritten: (Long) -> Unit): AndroidFileFinalizationResult {
        require(bufferSize in 256 * 1024..1024 * 1024) { "Buffer size is outside the supported range." }
        if (!ProtocolValidation.isSafeRelativePath(entry.relativePath)) return AndroidFileFinalizationResult.Rejected("Unsafe destination path.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return AndroidFileFinalizationResult.Rejected("Choose a receive folder on this Android version.")
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
                    if (count.toLong() > entry.sizeBytes - written) return AndroidFileFinalizationResult.Rejected("Source exceeds declared file size.")
                    output.write(buffer, 0, count); digest.update(buffer, 0, count); written += count; onBytesWritten(written)
                }
            }
            if (written != entry.sizeBytes) return AndroidFileFinalizationResult.Rejected("Source size does not match manifest.")
            if (digest.digest().toLowercaseHex() != entry.sha256) return AndroidFileFinalizationResult.Rejected("Source hash does not match manifest.")
            val segments = entry.relativePath.split('/')
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, segments.last())
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Zynk/${segments.dropLast(1).joinToString("/")}".trimEnd('/') + "/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return AndroidFileFinalizationResult.Rejected("Unable to create the destination file.")
            try {
                appContext.contentResolver.openOutputStream(uri, "w")?.use { temporary.inputStream().buffered(bufferSize).copyTo(it, bufferSize) }
                    ?: return AndroidFileFinalizationResult.Rejected("Unable to open the destination file.")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                appContext.contentResolver.update(uri, values, null, null)
                return AndroidFileFinalizationResult.Completed(uri, written)
            } catch (failure: Exception) {
                appContext.contentResolver.delete(uri, null, null)
                return AndroidFileFinalizationResult.Rejected(failure.message ?: "Unable to finalize the received file.")
            }
        } catch (failure: Exception) { return AndroidFileFinalizationResult.Rejected(failure.message ?: "Unable to write incoming file.") }
        finally { temporary.delete() }
    }
}

private fun ByteArray.toLowercaseHex(): String = joinToString(separator = "") { "%02x".format(it) }
