package com.jagdeep.home.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jagdeep.automation.InputAccess
import com.jagdeep.home.domain.HomeUiState
import com.jagdeep.home.domain.RunState
import com.jagdeep.home.ui.util.clicksLabel
import com.jagdeep.home.ui.util.lastRunLabel
import com.jagdeep.home.ui.util.pauseLabel
import com.jagdeep.ui.themes.AppColors

/** The current status and the one next action: start the selected routine, or stop what's running. */
@Composable
internal fun RunPanel(
    state: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val run = state.run
    val (icon, tint) = statusAppearance(state)
    val title = when {
        run is RunState.CountingDown -> "Starting in ${run.secondsLeft}…"
        run is RunState.Running -> if (run.pause != null) "Paused" else "Clicking"
        state.access == InputAccess.Unsupported -> "Desktop only for now"
        state.access == InputAccess.NeedsPermission -> "Allow access to click"
        state.selected == null -> "No routine yet"
        else -> "Ready"
    }
    val detail = when {
        run is RunState.CountingDown -> "Move to the target now. ${run.routine.name} starts soon."
        run is RunState.Running -> buildString {
            append(run.routine.name).append(" · ").append(clicksLabel(run.clicks))
            run.pause?.let { append(". ").append(pauseLabel(it)) }
        }
        state.access == InputAccess.Unsupported -> "Clicking in other apps works on Mac and Windows."
        state.access == InputAccess.NeedsPermission -> "macOS only lets an app click in other apps after you turn it on under Privacy & Security → Accessibility."
        state.selected == null -> "Create a routine to get started."
        else -> state.lastRun?.let(::lastRunLabel) ?: state.selected.name
    }
    val isActive = run != RunState.Idle
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(Modifier.size(48.dp), shape = CircleShape, color = tint.copy(alpha = 0.14f), contentColor = tint) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                isActive -> Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Stop", Modifier.padding(start = 8.dp))
                }
                state.access == InputAccess.NeedsPermission -> Button(onClick = onRequestAccess) { Text("Allow access") }
                else -> Button(onClick = onStart, enabled = state.selected != null && state.access == InputAccess.Granted) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Start", Modifier.padding(start = 8.dp))
                }
            }
            if (state.access == InputAccess.Granted) {
                Text(
                    "Press ${state.toggleHotKey.name} in any app to ${if (isActive) "stop" else "start or stop"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun statusAppearance(state: HomeUiState): Pair<ImageVector, Color> {
    val colors = MaterialTheme.colorScheme
    val run = state.run
    return when {
        run is RunState.CountingDown -> Icons.Default.Timer to colors.primary
        run is RunState.Running && run.pause != null -> Icons.Default.PauseCircleOutline to colors.onSurfaceVariant
        run is RunState.Running -> Icons.Default.AdsClick to AppColors.live
        state.access != InputAccess.Granted -> Icons.Default.ErrorOutline to colors.error
        else -> Icons.Default.AdsClick to colors.primary
    }
}
