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
import com.jagdeep.activity.domain.ActivityViewModel
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
    SideEffect {
        topBarManager.value = TopAppBarState(RouteTopLevel.Activity) {
            AppBarTop("Activity", RouteTopLevel.Activity, onBack, {}, {}, actions = {
                if (hasRecords) TextButton(onClick = viewModel::requestClear) { Text("Clear") }
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
