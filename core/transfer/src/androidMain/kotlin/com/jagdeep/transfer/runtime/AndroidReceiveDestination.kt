package com.jagdeep.transfer.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Public user-visible default. Android 10+ writes it through MediaStore without broad storage access. */
const val ANDROID_DOWNLOADS_APP_DESTINATION = "Downloads/Zynk"

/** Read-only preflight. Providers may still revoke access later; finalization must handle failure. */
fun canReceiveIntoAndroidTree(context: Context, reference: String?): Boolean {
    if (reference == ANDROID_DOWNLOADS_APP_DESTINATION) return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    if (reference.isNullOrBlank()) return false
    return try {
        val tree = Uri.parse(reference)
        if (!DocumentsContract.isTreeUri(tree)) return false
        val resolver = context.contentResolver
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) return false
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(document, arrayOf(
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        ), null, null, null)?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR &&
                cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        } == true
    } catch (_: Exception) {
        false
    }
}
