package com.jagdeep.ui.components

import com.jagdeep.ui.themes.AppColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow

/** Trust/reachability shown beside a device. Never derive [Ready] from an advertisement alone. */
enum class DeviceStatus { Ready, PairedUnavailable, NotPaired }

/** Standard device row shared by Home, Share, and Settings. */
@Composable
fun DeviceListItem(
    name: String,
    platform: String,
    status: DeviceStatus?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
) {
    val label = status?.label()
    ListItem(
        modifier = modifier.semantics { if (label != null) stateDescription = label },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        leadingContent = leading ?: { Icon(platformIcon(platform), contentDescription = null) },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = listOfNotNull(platform.takeIf(String::isNotBlank), label).takeIf { it.isNotEmpty() }?.let { parts ->
            {
                Text(
                    parts.joinToString(" · "),
                    color = if (status == DeviceStatus.Ready) AppColors.live else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        trailingContent = trailing,
    )
}

fun platformIcon(platform: String) = when {
    platform.contains("android", ignoreCase = true) || platform.contains("ios", ignoreCase = true) ||
        platform.contains("phone", ignoreCase = true) -> Icons.Default.Smartphone
    else -> Icons.Default.Computer
}

private fun DeviceStatus.label() = when (this) {
    DeviceStatus.Ready -> "Paired"
    DeviceStatus.PairedUnavailable -> "Paired · not receiving"
    DeviceStatus.NotPaired -> "Not paired"
}
