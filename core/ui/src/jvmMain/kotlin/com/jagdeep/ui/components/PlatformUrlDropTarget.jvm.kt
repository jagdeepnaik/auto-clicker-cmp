package com.jagdeep.ui.components

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.io.InputStream
import java.io.Reader
import java.net.URL

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformUrlDropTarget(
    onUrlDropped: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var isDraggingOverApp by remember { mutableStateOf(false) }
    val target = remember(onUrlDropped) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val url = event.extractUrl() ?: return false
                onUrlDropped(url)
                return true
            }

            override fun onEntered(event: DragAndDropEvent) {
                isDraggingOverApp = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDraggingOverApp = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDraggingOverApp = false
            }
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().dragAndDropTarget(
            // Browser transfer data is only reliably readable after the user drops it.
            shouldStartDragAndDrop = { true },
            target = target,
        ),
    ) {
        content()
        if (isDraggingOverApp) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.72f)
                    .height(220.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Drop link to add",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private val UrlPattern = Regex("""https?://[^\\s<>\"']+""", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.extractUrl(): String? {
    val transferable = runCatching { awtTransferable }.getOrNull() ?: return null
    return transferable.transferDataFlavors.asSequence()
        .flatMap { flavor ->
            val priority = flavor.urlPriority()
            runCatching { transferable.readText(flavor) }.getOrNull()
                ?.let { text -> UrlPattern.findAll(text).map { match -> DroppedUrl(match.value, priority) } }
                ?: emptySequence()
        }
        .maxWithOrNull(compareBy<DroppedUrl>({ it.completeness }, { it.priority }, { it.value.length }))
        ?.value
}

private data class DroppedUrl(val value: String, val priority: Int) {
    /** Prefer a complete YouTube video URL over shortened text/plain preview data. */
    val completeness: Int
        get() {
            val videoId = value.substringAfter("v=", missingDelimiterValue = "")
                .substringBefore('&')
                .substringBefore('#')
            return if ("youtube.com" in value && videoId.length == 11) 1 else 0
        }
}

private fun DataFlavor.urlPriority(): Int = when {
    URL::class.java.isAssignableFrom(representationClass) -> 3
    mimeType.startsWith("text/uri-list", ignoreCase = true) -> 3
    mimeType.startsWith("text/html", ignoreCase = true) -> 2
    else -> 1
}

private fun java.awt.datatransfer.Transferable.readText(flavor: DataFlavor): String? = when {
    flavor.representationClass == String::class.java -> getTransferData(flavor) as? String
    Reader::class.java.isAssignableFrom(flavor.representationClass) ->
        (getTransferData(flavor) as? Reader)?.use { it.readText() }
    InputStream::class.java.isAssignableFrom(flavor.representationClass) ->
        (getTransferData(flavor) as? InputStream)?.use { it.readBytes().decodeToString() }
    URL::class.java.isAssignableFrom(flavor.representationClass) -> getTransferData(flavor)?.toString()
    else -> null
}
