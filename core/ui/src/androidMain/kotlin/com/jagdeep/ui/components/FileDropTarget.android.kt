package com.jagdeep.ui.components

import androidx.compose.ui.Modifier

actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<DroppedFile>) -> Unit): Modifier = this
