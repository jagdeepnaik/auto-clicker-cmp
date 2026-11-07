package com.jagdeep.home.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jagdeep.common.config.AppConfig
import com.jagdeep.home.domain.HomeUiState
import com.jagdeep.home.domain.HomeViewModel
import com.jagdeep.home.domain.Routine
import com.jagdeep.home.domain.RoutineKind
import com.jagdeep.home.ui.components.RunPanel
import com.jagdeep.home.ui.util.routineSummary
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState
import com.jagdeep.ui.navigation.RouteTopLevel

/** The start destination: what's running, the action to change that, and every kind of routine. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    appIcon: @Composable () -> Unit,
    onNewRoutine: (RoutineKind) -> Unit,
    onEditRoutine: (Routine) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    // Only which dialog is open: nothing typed or sensitive, so it can be ordinary UI state.
    var choosingKind by remember { mutableStateOf(false) }
    if (choosingKind) {
        KindChooser(
            onChoose = { kind ->
                choosingKind = false
                onNewRoutine(kind)
            },
            onDismiss = { choosingKind = false },
        )
    }
    val topBarManager = LocalTopAppBarManager.current
    SideEffect {
        topBarManager.value = TopAppBarState(RouteTopLevel.Home) {
            AppBarTop(AppConfig.APP_NAME, RouteTopLevel.Home, {}, {}, {}, titleIcon = appIcon)
        }
    }
    state.routinePendingDeletion?.let { routine ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDeletion,
            title = { Text("Delete ${routine.name}?") },
            text = { Text("This removes the routine. Past runs stay in Activity.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeletion) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDeletion) { Text("Cancel") } },
        )
    }
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Start-aligned under the title with a readable width, like Activity and Settings.
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f)) {
            RunPanel(state, viewModel::start, viewModel::stop, viewModel::requestAccess, viewModel::toggleScrolling)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            if (state.routines.isEmpty()) {
                EmptyRoutines { choosingKind = true }
            } else {
                RoutineList(state, viewModel, { choosingKind = true }, onEditRoutine)
            }
        }
    }
}

@Composable
private fun RoutineList(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onNewRoutine: () -> Unit,
    onEditRoutine: (Routine) -> Unit,
) {
    // A running routine stays what it is until stopped, so changes wait until then.
    val editable = !state.isActive
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Text(
                "Routines",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
            )
        }
        items(state.routines, key = { it.id }) { routine ->
            val selected = routine.id == state.selected?.id
            ListItem(
                modifier = Modifier.selectable(selected = selected, enabled = editable, role = Role.RadioButton) { viewModel.select(routine) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { RadioButton(selected = selected, onClick = null, enabled = editable) },
                headlineContent = { Text(routine.name) },
                supportingContent = { Text(routineSummary(routine)) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onEditRoutine(routine) }, enabled = editable) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit ${routine.name}")
                        }
                        IconButton(onClick = { viewModel.requestDeletion(routine) }, enabled = editable) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${routine.name}")
                        }
                    }
                },
            )
        }
        item {
            FilledTonalButton(onClick = onNewRoutine, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("New routine", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun EmptyRoutines(onNewRoutine: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.AdsClick, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("No routines yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "A routine clicks for you in any app, or works through Chrome pages. Only one runs at a time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onNewRoutine) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("New routine", Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun KindChooser(onChoose: (RoutineKind) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New routine") },
        text = {
            Column {
                RoutineKind.entries.forEach { kind ->
                    ListItem(
                        modifier = Modifier.selectable(selected = false, role = Role.Button) { onChoose(kind) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(kind.label) },
                        supportingContent = { Text(kind.description) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun RoutineKind.icon(): ImageVector = when (this) {
    RoutineKind.Click -> Icons.Default.AdsClick
    RoutineKind.ScrollClick -> Icons.Default.VerticalAlignBottom
    RoutineKind.PageHelper -> Icons.Default.TravelExplore
}
