package com.jagdeep.home.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.PointerButton
import com.jagdeep.home.domain.IntervalUnit
import com.jagdeep.home.domain.LimitMode
import com.jagdeep.home.domain.PointCapture
import com.jagdeep.home.domain.PointDraft
import com.jagdeep.home.domain.RoutineDraft
import com.jagdeep.home.domain.RoutineEditorUiState
import com.jagdeep.home.domain.RoutineEditorViewModel
import com.jagdeep.home.domain.TargetMode
import com.jagdeep.home.ui.navigation.RouteRoutineEditor
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState

/** Where and how often to click. The draft lives in the ViewModel until Save. */
@Composable
fun RoutineEditorScreen(viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val topBarManager = LocalTopAppBarManager.current
    val title = if (state.isNew) "New routine" else "Edit routine"
    SideEffect {
        topBarManager.value = TopAppBarState(null) { AppBarTop(title, RouteRoutineEditor(), onBack, {}, {}) }
    }
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val draft = state.draft
    val errors = state.errors
    val update = viewModel::updateDraft
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { name -> update { it.copy(name = name) } },
                label = { Text("Name") },
                singleLine = true,
                isError = errors.name != null,
                supportingText = errors.name?.let { message -> { Text(message) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Group("Where to click")
            Column(Modifier.selectableGroup()) {
                Choice("Wherever the pointer is", "You stay in control of the position.", draft.targetMode == TargetMode.AtPointer) {
                    update { it.copy(targetMode = TargetMode.AtPointer) }
                }
                Choice("Specific points", "Moves the pointer to each point in turn. Best when the target stays put, such as a button on a Chrome page.", draft.targetMode == TargetMode.AtPoints) {
                    update { it.copy(targetMode = TargetMode.AtPoints) }
                }
            }
            if (draft.targetMode == TargetMode.AtPoints) {
                PointsEditor(state, viewModel)
            }

            Group("What to click with")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PointerButton.entries.forEachIndexed { index, button ->
                        SegmentedButton(
                            selected = draft.button == button,
                            onClick = { update { it.copy(button = button) } },
                            shape = SegmentedButtonDefaults.itemShape(index, PointerButton.entries.size),
                            label = { Text(button.name) },
                        )
                    }
                }
            }
            ListItem(
                modifier = Modifier.toggleable(value = draft.doubleClick, role = Role.Switch, onValueChange = { value -> update { it.copy(doubleClick = value) } }),
                colors = transparent(),
                headlineContent = { Text("Double click") },
                trailingContent = { Switch(checked = draft.doubleClick, onCheckedChange = null) },
            )

            Group("How often")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                NumberField(
                    label = "Every",
                    value = draft.interval,
                    error = errors.interval,
                    onChange = { value -> update { it.copy(interval = value) } },
                    modifier = Modifier.weight(1f),
                )
                IntervalUnitMenu(draft.intervalUnit, { unit -> update { it.copy(intervalUnit = unit) } }, Modifier.weight(1f))
            }
            NumberField(
                label = "Random extra wait (ms)",
                value = draft.randomExtraMillis,
                error = errors.randomExtra,
                supporting = "Adds up to this much to each wait. 0 keeps the timing exact.",
                onChange = { value -> update { it.copy(randomExtraMillis = value) } },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )

            Group("When to stop")
            LimitEditor(draft, errors.limit, update)

            Group("Where it may run")
            AppMenu(draft.onlyInApp, state.runningApps, onOpen = viewModel::refreshApps, onSelect = { app -> update { it.copy(onlyInApp = app) } })
            NumberField(
                label = "Start delay (seconds)",
                value = draft.startDelaySeconds,
                error = errors.startDelay,
                supporting = "Time to get to the right window after pressing Start.",
                onChange = { value -> update { it.copy(startDelaySeconds = value) } },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )

            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save) { Text("Save") }
                TextButton(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PointsEditor(state: RoutineEditorUiState, viewModel: RoutineEditorViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.draft.points.forEachIndexed { index, point ->
            PointRow(
                index = index,
                point = point,
                onChange = { viewModel.updatePoint(index, it) },
                onRemove = { viewModel.removePoint(index) },
            )
        }
        state.errors.points?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val capture = state.capture
        if (capture != null) {
            CaptureStatus(capture, state.captureHotKey, viewModel::finishCapture)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = viewModel::startCapture) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Pick on screen", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = viewModel::addEmptyPoint) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Type a point", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun CaptureStatus(capture: PointCapture, hotKey: HotKey, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Go to the other app, hold the pointer over the target, and press ${hotKey.name}. Repeat for each point.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "On a Mac keyboard you may need to hold fn. " +
                (capture.pointer?.let { "Pointer is at ${it.x}, ${it.y}." } ?: "Pointer position is unavailable."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDone) { Text(if (capture.added == 0) "Stop picking" else "Done (${capture.added} added)") }
    }
}

@Composable
private fun PointRow(index: Int, point: PointDraft, onChange: (PointDraft) -> Unit, onRemove: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Fixed width, so the X and Y columns line up whatever the number's digits.
        Text("${index + 1}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
        NumberField("X", point.x, null, onChange = { onChange(point.copy(x = it)) }, modifier = Modifier.weight(1f))
        NumberField("Y", point.y, null, onChange = { onChange(point.copy(y = it)) }, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove point ${index + 1}") }
    }
}

@Composable
private fun LimitEditor(draft: RoutineDraft, error: String?, update: ((RoutineDraft) -> RoutineDraft) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Choice("When I stop it", null, draft.limitMode == LimitMode.UntilStopped) { update { it.copy(limitMode = LimitMode.UntilStopped) } }
        Choice("After a number of clicks", null, draft.limitMode == LimitMode.Clicks) { update { it.copy(limitMode = LimitMode.Clicks) } }
        if (draft.limitMode == LimitMode.Clicks) {
            NumberField(
                "Clicks", draft.limitClicks, error,
                onChange = { value -> update { it.copy(limitClicks = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Choice("After some time", null, draft.limitMode == LimitMode.Minutes) { update { it.copy(limitMode = LimitMode.Minutes) } }
        if (draft.limitMode == LimitMode.Minutes) {
            NumberField(
                "Minutes", draft.limitMinutes, error,
                onChange = { value -> update { it.copy(limitMinutes = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun IntervalUnitMenu(selected: IntervalUnit, onSelect: (IntervalUnit) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.label, Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            IntervalUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit.label) },
                    trailingIcon = if (unit == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The list is read when opened, so an app started after this screen appears too. */
@Composable
private fun AppMenu(selected: String?, apps: List<String>, onOpen: () -> Unit, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // A saved choice stays selectable even while that app isn't running.
    val choices = (listOfNotNull(selected) + apps).distinct()
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose app", role = Role.DropdownList) {
                onOpen()
                expanded = true
            },
            colors = transparent(),
            headlineContent = { Text("Only click while this app is in front") },
            supportingContent = { Text(selected ?: "Any app") },
            trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            DropdownMenuItem(
                text = { Text("Any app") },
                trailingIcon = if (selected == null) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            choices.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app) },
                    trailingIcon = if (app == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(app)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Choice(title: String, supporting: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = transparent(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { text -> { Text(text) } },
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    error: String?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
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
