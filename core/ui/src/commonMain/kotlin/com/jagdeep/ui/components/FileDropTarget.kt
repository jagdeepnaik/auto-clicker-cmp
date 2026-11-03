package com.jagdeep.ui.components

import androidx.compose.ui.Modifier

/** A regular file dropped onto the app, identified by its local path. */
data class DroppedFile(val name: String, val sizeBytes: Long, val path: String)

/**
 * Accepts files dragged in from the operating system (desktop). [onHover] reports whether files
 * are being dragged over the target, for highlighting; folders are ignored. A no-op on platforms
 * without external file drag and drop.
 */
expect fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<DroppedFile>) -> Unit): Modifier
