package com.jagdeep.activity.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jagdeep.activity.domain.ActivityUiState
import com.jagdeep.activity.domain.ActivityView
import com.jagdeep.activity.domain.ActivityViewModel
import com.jagdeep.activity.ui.util.liveLogText
import com.jagdeep.activity.ui.util.liveTimeLabel
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.jagdeep.activity.ui.util.clickCountLabel
import com.jagdeep.activity.ui.util.durationLabel
import com.jagdeep.activity.ui.util.runTimeLabel
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlinx.datetime.TimeZone

/** What ran, how long for, and why it stopped: most useful after leaving a routine unattended. */
@Composable
fun ActivityScreen(viewModel: ActivityViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val topBarManager = LocalTopAppBarManager.current
    val hasRecords = state.records.isNotEmpty()
    // The top bar's Clear empties the list of runs, so it's only offered while that list shows.
    val offerClear = hasRecords && !state.showsLive
    SideEffect {
        topBarManager.value = TopAppBarState(RouteTopLevel.Activity) {
            AppBarTop("Activity", RouteTopLevel.Activity, onBack, {}, {}, actions = {
                if (offerClear) TextButton(onClick = viewModel::requestClear) { Text("Clear") }
            })
        }
    }
    if (state.isConfirmingClear) {
        AlertDialog(
            onDismissRequest = viewModel::cancelClear,
            title = { Text("Clear activity?") },
            text = { Text("This removes the list of past runs. Your routines stay.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClear) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelClear) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (state.developerMode) ViewChooser(state.view, viewModel::show)
        if (state.showsLive) LiveLogView(state, viewModel) else RunList(state)
    }
}

@Composable
private fun ViewChooser(selected: ActivityView, onSelect: (ActivityView) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ActivityView.entries.forEachIndexed { index, view ->
            SegmentedButton(
                selected = view == selected,
                onClick = { onSelect(view) },
                shape = SegmentedButtonDefaults.itemShape(index, ActivityView.entries.size),
                label = { Text(if (view == ActivityView.Live) "Live log" else "Runs") },
            )
        }
    }
}

@Composable
private fun LiveLogView(state: ActivityUiState, viewModel: ActivityViewModel) {
    val timeZone = remember { TimeZone.currentSystemDefault() }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val entries = state.liveEntries
    // Follows the newest entry, unless paused for reading.
    LaunchedEffect(entries.lastOrNull()?.id) {
        if (!state.isLivePaused && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::toggleLivePaused) { Text(if (state.isLivePaused) "Resume" else "Pause") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(liveLogText(entries, timeZone))) }, enabled = entries.isNotEmpty()) { Text("Copy") }
            TextButton(onClick = viewModel::clearLive, enabled = entries.isNotEmpty()) { Text("Clear") }
            Text(
                "${entries.size} entries · in memory only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entries.isEmpty()) {
            Text(
                "Nothing yet. Start a routine, or press a hot key, and each step appears here as it happens.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(entries, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(liveTimeLabel(entry.atMillis, timeZone), style = MONO, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entry.source.label, style = MONO, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp))
                    Text(entry.text, style = MONO, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
private fun RunList(state: ActivityUiState) {
    val hasRecords = state.records.isNotEmpty()
    when {
        state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !hasRecords -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Text("No runs yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    "Each routine you run appears here with how many clicks it made and why it stopped.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        else -> {
            val timeZone = remember { TimeZone.currentSystemDefault() }
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 720.dp)) {
                items(state.records, key = { it.id }) { record ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(record.routineName) },
                        supportingContent = {
                            Column {
                                Text("${clickCountLabel(record.clicks)} in ${durationLabel(record.endedAtMillis - record.startedAtMillis)}")
                                Text(record.outcome.label)
                            }
                        },
                        trailingContent = { Text(runTimeLabel(record.startedAtMillis, state.nowMillis, timeZone), style = MaterialTheme.typography.bodySmall) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
