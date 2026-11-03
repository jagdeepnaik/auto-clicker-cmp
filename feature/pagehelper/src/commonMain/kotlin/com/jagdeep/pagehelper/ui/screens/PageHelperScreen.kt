package com.jagdeep.pagehelper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jagdeep.pagehelper.domain.MAX_PIXELS_PER_SECOND
import com.jagdeep.pagehelper.domain.MIN_PIXELS_PER_SECOND
import com.jagdeep.pagehelper.domain.PageHelperUiState
import com.jagdeep.pagehelper.domain.PageHelperViewModel
import com.jagdeep.pagehelper.domain.ScrollClickPhase
import com.jagdeep.pagehelper.domain.ScrollClickProgress
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalToastManager
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState
import com.jagdeep.ui.navigation.RouteTopLevel

/** Controls for the Chrome companion: it scrolls and highlights, and the extension clicks only on your key press. */
@Composable
fun PageHelperScreen(viewModel: PageHelperViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val topBarManager = LocalTopAppBarManager.current
    val toast = LocalToastManager.current
    SideEffect {
        topBarManager.value = TopAppBarState(RouteTopLevel.Sync) { AppBarTop("Page helper", RouteTopLevel.Sync, onBack, {}, {}) }
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { toast.show(it) } }
    val helper = state.helper
    if (!helper.isSupported) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("The page helper works with Chrome on a computer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 8.dp)) {
            StatusPanel(state, viewModel)
            ListItem(
                modifier = Modifier.toggleable(value = helper.settings.enabled, role = Role.Switch, onValueChange = viewModel::setEnabled),
                colors = transparent(),
                headlineContent = { Text("Turn on page helper") },
                supportingContent = { Text("Works in the Chrome tab you have open.") },
                trailingContent = { Switch(checked = helper.settings.enabled, onCheckedChange = null) },
            )

            Group("Auto-scroll")
            SpeedSlider(helper.settings.pixelsPerSecond, viewModel::setSpeed)

            Group("Scroll and click")
            ScrollClickSection(state, viewModel)

            Group("Buttons to look for")
            OutlinedTextField(
                value = state.wordsDraft,
                onValueChange = viewModel::updateWords,
                label = { Text("Words, most important first") },
                isError = state.wordsError != null,
                supportingText = { Text(state.wordsError ?: "Separate with commas. Press ${state.clickKey.name} to click the highlighted one.") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Group("Video alerts")
            ListItem(
                modifier = Modifier.toggleable(value = helper.settings.soundOnVideoEnd, role = Role.Switch, onValueChange = viewModel::setSoundOnVideoEnd),
                colors = transparent(),
                headlineContent = { Text("Play a sound when a video ends") },
                trailingContent = { Switch(checked = helper.settings.soundOnVideoEnd, onCheckedChange = null) },
            )

            Group("Set up in Chrome (once)")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Press Show extension folder below.")
                Text("2. In Chrome, open chrome://extensions and turn on Developer mode.")
                Text("3. Choose Load unpacked, then select the folder that opened.")
                FilledTonalButton(onClick = viewModel::showExtension) {
                    Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Show extension folder", Modifier.padding(start = 8.dp))
                }
                Text(
                    "The extension talks only to this app on this computer. It reads button labels to find the next step, and it clicks only when you press the key. Scroll and click is separate: once you start it, the app clicks the spot you picked with no key press.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPanel(state: PageHelperUiState, viewModel: PageHelperViewModel) {
    val colors = MaterialTheme.colorScheme
    val helper = state.helper
    val run = helper.scrollClick
    val (title, detail) = when {
        run != null -> "Round ${run.round} of ${run.rounds}" to scrollClickDetail(run)
        helper.problem != null -> "Can't reach Chrome" to helper.problem
        !helper.isConnected -> "Waiting for Chrome" to "Add the extension below and keep Chrome open."
        !helper.settings.enabled -> "Connected" to "Turn on the page helper to use it."
        helper.foundLabel != null -> "Next step: ${helper.foundLabel}" to "Press ${state.clickKey.name} to click it."
        else -> "Looking for a button" to "Nothing on this page matches your words yet."
    }
    val icon: ImageVector = if (helper.problem != null) Icons.Default.ErrorOutline else Icons.Default.TravelExplore
    val tint = if (helper.problem != null) colors.error else colors.primary
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            // A run may pause its scrolling between rounds, and stays stoppable throughout.
            val active = helper.isScrolling || run != null
            Button(onClick = viewModel::toggleScrolling, enabled = run != null || (helper.settings.enabled && helper.isConnected)) {
                Icon(if (active) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    when {
                        run != null -> "Stop"
                        helper.isScrolling -> "Stop scrolling"
                        else -> "Start scrolling"
                    },
                    Modifier.padding(start = 8.dp),
                )
            }
            Text(if (run != null) "Or press ${state.stopKey.name} in any app" else "Or press ${state.scrollKey.name} in Chrome", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun scrollClickDetail(run: ScrollClickProgress): String = when (run.phase) {
    ScrollClickPhase.WaitingForChrome -> "Bring Chrome to the front to continue. Clicks so far: ${run.clicks}."
    ScrollClickPhase.Scrolling -> "Scrolling to the bottom. Clicks so far: ${run.clicks}."
    ScrollClickPhase.AtBottom -> "Reached the bottom. Clicking soon."
    ScrollClickPhase.WaitingForPage -> "Clicked. Waiting for the page to change. Clicks so far: ${run.clicks}."
}

@Composable
private fun ScrollClickSection(state: PageHelperUiState, viewModel: PageHelperViewModel) {
    val helper = state.helper
    val colors = MaterialTheme.colorScheme
    val point = helper.settings.clickPoint
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Scrolls to the bottom of the page, clicks the spot you pick, then does it again. It clicks with no key press, and only while Chrome is in front.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (helper.isPickingPoint) {
            Text("Hold the pointer over the spot in Chrome and press ${state.captureKey.name}.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = viewModel::cancelPickingPoint) { Text("Cancel") }
        } else {
            Text(
                if (point == null) "No spot picked yet." else "Clicks at ${point.x}, ${point.y} on your screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = viewModel::startPickingPoint, enabled = helper.scrollClick == null) {
                Text(if (point == null) "Pick on screen" else "Pick again")
            }
        }
        OutlinedTextField(
            value = state.roundsDraft,
            onValueChange = viewModel::updateRounds,
            label = { Text("Rounds") },
            singleLine = true,
            isError = state.roundsError != null,
            enabled = helper.scrollClick == null,
            supportingText = { Text(state.roundsError ?: "Stops after this many clicks, or sooner if a click doesn't change the page.") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (helper.scrollClick == null) {
            Button(onClick = viewModel::startScrollClick, enabled = point != null && helper.settings.enabled && helper.isConnected) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Start scroll and click", Modifier.padding(start = 8.dp))
            }
        }
        Text(
            "To stop: press ${state.stopKey.name}, push the pointer into a screen corner, or move the pointer by hand.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        helper.scrollClickNote?.let { note ->
            Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun SpeedSlider(saved: Int, onSave: (Int) -> Unit) {
    // Follows the thumb while dragging, and saves on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: saved.toFloat()
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Speed: ${value.toInt()} pixels a second", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSave(it.toInt()) }
                dragging = null
            },
            valueRange = MIN_PIXELS_PER_SECOND.toFloat()..MAX_PIXELS_PER_SECOND.toFloat(),
        )
    }
}

@Composable
private fun Group(title: String) {
    // Inset so section dividers never meet the navigation divider.
    HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = Color.Transparent)
