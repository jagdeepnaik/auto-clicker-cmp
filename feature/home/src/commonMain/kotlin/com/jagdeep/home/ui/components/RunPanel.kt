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
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.HomeUiState
import com.jagdeep.home.domain.PageActivity
import com.jagdeep.home.domain.RunState
import com.jagdeep.home.domain.needsInputAccess
import com.jagdeep.home.ui.util.clicksLabel
import com.jagdeep.home.ui.util.lastRunLabel
import com.jagdeep.home.ui.util.pauseLabel
import com.jagdeep.home.ui.util.scrollClickDetail
import com.jagdeep.ui.themes.AppColors

/** The current status and the one next action: start the selected routine, or stop what's running. */
@Composable
internal fun RunPanel(
    state: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestAccess: () -> Unit,
    onToggleScrolling: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val run = state.run
    val page = state.page
    val activity = page.activity
    // A page helper needs Chrome, not input permission, so only the others ask for access.
    val needsAccess = state.selected?.needsInputAccess() != false
    val access = if (needsAccess) state.access else InputAccess.Granted
    // Why a selected Chrome routine can't reach Chrome, such as another program holding its port.
    val chromeProblem = page.problem.takeIf { state.selected != null && state.selected !is ClickRoutine }
    val (icon, tint) = statusAppearance(state, access)
    val title = when {
        activity is PageActivity.Helper -> "Web page helper on"
        activity is PageActivity.Course -> "Course helper on"
        activity is PageActivity.ScrollClick -> "Round ${activity.progress.round} of ${activity.progress.rounds}"
        run is RunState.CountingDown -> "Starting in ${run.secondsLeft}…"
        run is RunState.Running -> if (run.pause != null) "Paused" else "Clicking"
        chromeProblem != null -> "Can't reach Chrome"
        access == InputAccess.Unsupported -> "Desktop only for now"
        access == InputAccess.NeedsPermission -> "Allow access to click"
        state.selected == null -> "No routine yet"
        else -> "Ready"
    }
    val detail = when {
        activity is PageActivity.Helper -> buildString {
            append(
                when {
                    !page.isConnected -> "Waiting for Chrome. The extension steps are in Settings."
                    page.foundLabel != null -> "Next step: ${page.foundLabel}. Press ${state.pageClickHotKey.name} to click it."
                    else -> "Looking for a button that matches your words."
                },
            )
            if (page.isScrolling) append(" Scrolling.")
        }
        activity is PageActivity.ScrollClick -> "${activity.routine.name} · ${scrollClickDetail(activity.progress)}"
        activity is PageActivity.Course -> when {
            !page.isConnected -> "Waiting for Chrome. The extension steps are in Settings."
            page.foundLabel != null -> "Next: ${page.foundLabel}. Press ${state.pageClickHotKey.name} to open it."
            else -> "Open sagarmeinyog.com in Chrome. The next course or part to watch is highlighted there."
        }
        run is RunState.CountingDown -> "Move to the target now. ${run.routine.name} starts soon."
        run is RunState.Running -> buildString {
            append(run.routine.name).append(" · ").append(clicksLabel(run.clicks))
            run.pause?.let { append(". ").append(pauseLabel(it)) }
        }
        chromeProblem != null -> chromeProblem
        access == InputAccess.Unsupported -> "Clicking in other apps works on Mac and Windows."
        access == InputAccess.NeedsPermission -> "macOS only lets an app click in other apps after you turn it on under Privacy & Security → Accessibility."
        state.selected == null -> "Create a routine to get started."
        else -> state.lastRun?.let(::lastRunLabel) ?: state.selected.name
    }
    val isActive = state.isActive
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
                access == InputAccess.NeedsPermission -> Button(onClick = onRequestAccess) { Text("Allow access") }
                else -> Button(onClick = onStart, enabled = state.selected != null && access == InputAccess.Granted) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Start", Modifier.padding(start = 8.dp))
                }
            }
            if (activity is PageActivity.Helper) {
                OutlinedButton(onClick = onToggleScrolling, enabled = page.isConnected) {
                    Text(if (page.isScrolling) "Stop scrolling" else "Start scrolling")
                }
            }
            if (access == InputAccess.Granted) {
                Text(
                    when {
                        activity is PageActivity.Helper -> "${state.pageScrollHotKey.name} scrolls, ${state.toggleHotKey.name} stops"
                        isActive -> "Press ${state.toggleHotKey.name} in any app to stop"
                        else -> "Press ${state.toggleHotKey.name} in any app to start or stop"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun statusAppearance(state: HomeUiState, access: InputAccess): Pair<ImageVector, Color> {
    val colors = MaterialTheme.colorScheme
    val run = state.run
    return when {
        state.page.activity != null -> Icons.Default.TravelExplore to AppColors.live
        run is RunState.CountingDown -> Icons.Default.Timer to colors.primary
        run is RunState.Running && run.pause != null -> Icons.Default.PauseCircleOutline to colors.onSurfaceVariant
        run is RunState.Running -> Icons.Default.AdsClick to AppColors.live
        access != InputAccess.Granted -> Icons.Default.ErrorOutline to colors.error
        else -> Icons.Default.AdsClick to colors.primary
    }
}
