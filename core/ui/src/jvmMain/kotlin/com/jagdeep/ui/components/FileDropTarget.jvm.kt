package com.jagdeep.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<DroppedFile>) -> Unit): Modifier = composed {
    val hover = rememberUpdatedState(onHover)
    val files = rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover.value(true)
            override fun onExited(event: DragAndDropEvent) = hover.value(false)
            override fun onEnded(event: DragAndDropEvent) = hover.value(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover.value(false)
                val dropped = runCatching {
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
                        .filterIsInstance<File>()
                        .filter(File::isFile)
                        .map { DroppedFile(it.name, it.length(), it.absolutePath) }
                }.getOrDefault(emptyList())
                if (dropped.isEmpty()) return false
                files.value(dropped)
                return true
            }
        }
    }
    dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target,
    )
}
