package com.jagdeep.ui.components

import com.jagdeep.ui.themes.AppColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jagdeep.ui.util.toReadableBytes
import com.jagdeep.ui.util.toReadableRate

/** Live progress for one send or receive; shared by Home and Share. */
@Composable
fun TransferProgressListItem(
    sending: Boolean,
    itemLabel: String,
    peerName: String,
    transferredBytes: Long,
    totalBytes: Long,
    remainingMillis: Long?,
    bytesPerSecond: Double,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fraction = if (totalBytes <= 0) 0f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(if (sending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, contentDescription = if (sending) "Sending" else "Receiving")
        },
        headlineContent = {
            Text(
                if (sending) "$itemLabel → $peerName" else "$itemLabel ← $peerName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = AppColors.live,
                    // A plain continuous bar: no gap between filled and empty track, no end dot.
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$percent percent ${if (sending) "sent" else "received"}" },
                )
                Text(
                    "${transferredBytes.toReadableBytes()} of ${totalBytes.toReadableBytes()} · ${bytesPerSecond.toReadableRate()} · ${remainingMillis.toRemainingTime()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = onCancel?.let { cancel -> { TextButton(onClick = cancel) { Text("Cancel") } } },
    )
}

private fun Long?.toRemainingTime(): String = when {
    this == null -> "estimating time"
    this == 0L -> "finishing"
    this < 60_000L -> "${(this / 1_000).coerceAtLeast(1)}s left"
    else -> "${this / 60_000}m left"
}
