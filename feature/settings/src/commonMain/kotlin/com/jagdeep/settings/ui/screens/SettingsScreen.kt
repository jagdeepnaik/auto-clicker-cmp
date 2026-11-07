package com.jagdeep.settings.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.shared.preferences.ColorPalette
import com.jagdeep.shared.preferences.ThemeMode
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalToastManager
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState
import com.jagdeep.ui.navigation.RouteTopLevel
import com.jagdeep.ui.themes.Palette
import com.jagdeep.ui.themes.previewColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    appVersion: String,
) {
    val state by viewModel.state.collectAsState()
    val topBarManager = LocalTopAppBarManager.current
    val toast = LocalToastManager.current
    SideEffect {
        topBarManager.value = TopAppBarState(RouteTopLevel.Settings) { AppBarTop("Settings", RouteTopLevel.Settings, onBack, {}, {}) }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshAccess()
        viewModel.messages.collect { toast.show(it) }
    }
    val preferences = state.preferences

    // Start-aligned under the title; capped width keeps rows readable on wide windows.
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 8.dp)) {
            Group("Hot keys", first = true)
            HotKeyRow(
                title = "Start and stop",
                supporting = "Starts the selected routine, or stops whichever is running. Works in any app; on a Mac keyboard you may need to hold fn.",
                selected = preferences.toggleHotKey,
                onSelect = viewModel::updateToggleHotKey,
            )
            HotKeyRow(
                title = "Pick a point",
                supporting = "Records the pointer's position while you edit a routine.",
                selected = preferences.captureHotKey,
                onSelect = viewModel::updateCaptureHotKey,
            )
            HotKeyRow(
                title = "Click the highlighted button",
                supporting = "Page helper routines: clicks the button highlighted in Chrome.",
                selected = preferences.pageClickHotKey,
                onSelect = viewModel::updatePageClickHotKey,
            )
            HotKeyRow(
                title = "Auto-scroll",
                supporting = "Page helper routines: starts or stops scrolling. Scroll and click: stops it.",
                selected = preferences.pageScrollHotKey,
                onSelect = viewModel::updatePageScrollHotKey,
            )

            Group("Safety")
            SwitchRow(
                title = "Stop when I move the pointer",
                supporting = "Applies to routines with specific points.",
                checked = preferences.stopWhenPointerMoves,
                onChange = viewModel::updateStopWhenPointerMoves,
            )
            SwitchRow(
                title = "Stop at a screen corner",
                supporting = "Push the pointer into any corner of a screen to stop any routine.",
                checked = preferences.stopAtScreenCorner,
                onChange = viewModel::updateStopAtScreenCorner,
            )

            if (state.access != InputAccess.Unsupported) {
                Group("Chrome extension")
                ListItem(
                    colors = transparent(),
                    headlineContent = { Text(if (state.browserConnected) "Connected to Chrome" else "Not connected") },
                    supportingContent = {
                        Text("Needed for scroll and click and page helper routines. It talks only to this app on this computer.")
                    },
                )
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("1. Press Show extension folder.", style = MaterialTheme.typography.bodyMedium)
                    Text("2. In Chrome, open chrome://extensions and turn on Developer mode.", style = MaterialTheme.typography.bodyMedium)
                    Text("3. Choose Load unpacked, then select the folder that opened.", style = MaterialTheme.typography.bodyMedium)
                    Text("After an update, press reload there and refresh any open page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::showExtension) { Text("Show extension folder") }
                }

                Group("Display")
                SwitchRow(
                    title = "Keep the display on while running",
                    supporting = "Stops the display sleeping while a routine runs. It sleeps as usual afterwards.",
                    checked = preferences.keepDisplayOn,
                    onChange = viewModel::updateKeepDisplayOn,
                )

                Group("Permission")
                ListItem(
                    colors = transparent(),
                    headlineContent = { Text("Input in other apps") },
                    supportingContent = {
                        Text(
                            if (state.access == InputAccess.Granted) {
                                "Allowed. On Windows, apps running as administrator still can't be clicked."
                            } else {
                                "Not allowed yet. Turn it on under Privacy & Security → Accessibility."
                            },
                        )
                    },
                    trailingContent = if (state.access == InputAccess.NeedsPermission) {
                        { TextButton(onClick = viewModel::requestAccess) { Text("Allow") } }
                    } else {
                        null
                    },
                )
            }

            Group("Appearance")
            // Theme and colors are both one row each that opens a menu of choices.
            ThemeModeRow(preferences.themeMode, viewModel::updateThemeMode)
            ColorPaletteRow(preferences.colorPalette, viewModel::updateColorPalette)

            Group("Developer")
            SwitchRow(
                title = "Developer mode",
                supporting = "Activity shows a live log of what the app does, including what it sends to and hears from Chrome. Kept in memory for this session only.",
                checked = preferences.developerMode,
                onChange = viewModel::updateDeveloperMode,
            )

            Group("About")
            ListItem(colors = transparent(), headlineContent = { Text("Version") }, trailingContent = { Text(appVersion) })
            ListItem(
                colors = transparent(),
                headlineContent = { Text("Privacy") },
                supportingContent = {
                    Text("Routines and activity stay on this computer. Nothing is sent anywhere, and only the function keys are watched, never what you type.")
                },
            )
        }
    }
}

@Composable
private fun Group(title: String, first: Boolean = false) {
    // Inset so section dividers never meet the navigation divider.
    if (!first) HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(title: String, supporting: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        colors = transparent(),
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun HotKeyRow(title: String, supporting: String, selected: HotKey, onSelect: (HotKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose key for $title", role = Role.DropdownList) { expanded = true },
            colors = transparent(),
            headlineContent = { Text(title) },
            supportingContent = { Text(supporting) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.name)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            HotKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.name) },
                    trailingIcon = if (key == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun ColorPaletteRow(selected: ColorPalette, onSelect: (ColorPalette) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose colors", role = Role.DropdownList) { expanded = true },
            colors = transparent(),
            headlineContent = { Text("Colors") },
            supportingContent = { Text(selected.label) },
            leadingContent = { PaletteSwatch(selected, dark) },
            trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            ColorPalette.entries.forEach { palette ->
                DropdownMenuItem(
                    text = { Text(palette.label) },
                    leadingIcon = { PaletteSwatch(palette, dark) },
                    trailingIcon = if (palette == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(palette)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Two halves: the palette's action colour and its live colour. */
@Composable
private fun PaletteSwatch(palette: ColorPalette, dark: Boolean) {
    val (action, live) = Palette.valueOf(palette.name).previewColors(dark)
    Row(Modifier.size(24.dp).clip(CircleShape)) {
        Box(Modifier.weight(1f).fillMaxHeight().background(action))
        Box(Modifier.weight(1f).fillMaxHeight().background(live))
    }
}

@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose theme", role = Role.DropdownList) { expanded = true },
            colors = transparent(),
            headlineContent = { Text("Theme") },
            supportingContent = { Text(selected.label) },
            leadingContent = { Icon(selected.icon(), contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = { Icon(mode.icon(), contentDescription = null) },
                    trailingIcon = if (mode == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun ThemeMode.icon() = when (this) {
    ThemeMode.System -> Icons.Default.BrightnessAuto
    ThemeMode.Light -> Icons.Default.LightMode
    ThemeMode.Dark -> Icons.Default.DarkMode
}
