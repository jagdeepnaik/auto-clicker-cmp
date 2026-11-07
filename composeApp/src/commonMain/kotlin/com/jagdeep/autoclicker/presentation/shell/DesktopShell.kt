package com.jagdeep.autoclicker.presentation.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.jagdeep.home.ui.util.icon
import com.jagdeep.ui.navigation.MainNavigationItem
import com.jagdeep.ui.themes.AppColors
import kotlinx.coroutines.delay

/** Height of the toolbar row: the Mac title bar's, so its items line up with the window buttons. */
internal val DesktopToolbarHeight = 28.dp

/** Extra height under a standard-height toolbar row, so the bar doesn't look thin. */
private val DesktopToolbarBottomSpace = 8.dp

/**
 * The desktop app shell, laid out like Android Studio: a toolbar in the title bar row with the button
 * that shows the pages' names, then the routine to run and its ▶ and ■; an icon strip for the pages,
 * with Settings at its foot; the page in a rounded panel; and a status bar with what's happening and
 * Chrome's link. Phones keep the adaptive scaffold.
 */
@Composable
internal fun DesktopShell(
    state: ShellUiState,
    onSelectRoutine: (com.jagdeep.home.domain.Routine) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    selectedRoute: NavKey,
    /** The pages at the top of the strip on the left. */
    navigationItems: Map<NavKey, MainNavigationItem>,
    /** Pages at the foot of the strip, such as Settings. */
    footerItems: Map<NavKey, MainNavigationItem>,
    onNavigate: (NavKey) -> Unit,
    now: () -> Long,
    appIcon: @Composable () -> Unit,
    appName: String,
    appVersion: String,
    /** Room for the Mac window buttons, which share the toolbar's row. */
    toolbarStartInset: Dp,
    /** Lets the window be dragged by the toolbar where the system bar used to be. */
    toolbarDragArea: @Composable (@Composable () -> Unit) -> Unit,
    /**
     * The toolbar row's height. On a Mac with the taller title bar it matches that bar, so the row is
     * centred on the window buttons; otherwise it's the standard bar's, with a little space below.
     */
    toolbarHeight: Dp = DesktopToolbarHeight,
    topBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Only whether the strip shows the pages' names: a layout choice, so ordinary UI state.
    var stripExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.surfaceContainer)) {
        toolbarDragArea {
            Column {
                Toolbar(state, onSelectRoutine, onStart, onStop, appIcon, appName, toolbarStartInset, toolbarHeight, stripExpanded) { stripExpanded = !stripExpanded }
                // A little more bar below a standard-height row, which stays in line with the Mac window buttons.
                if (toolbarHeight <= DesktopToolbarHeight) Spacer(Modifier.fillMaxWidth().height(DesktopToolbarBottomSpace))
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolStripe(selectedRoute, navigationItems, footerItems, stripExpanded, onNavigate)
            // The page as a rounded panel on the darker frame.
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(end = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(colors.surface),
            ) {
                topBar()
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
        StatusBar(state, appVersion, now)
    }
}

@Composable
private fun Toolbar(
    state: ShellUiState,
    onSelectRoutine: (com.jagdeep.home.domain.Routine) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    appIcon: @Composable () -> Unit,
    appName: String,
    startInset: Dp,
    height: Dp,
    stripExpanded: Boolean,
    onToggleStrip: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(height).padding(start = startInset + 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The Mac sidebar button, just after the window buttons as in Finder; shaded while names show.
        ToolbarIcon(
            SidebarIcon,
            if (stripExpanded) "Collapse sidebar" else "Expand sidebar",
            colors.onSurfaceVariant,
            enabled = true,
            selected = stripExpanded,
            onClick = onToggleStrip,
        )
        appIcon()
        Text(appName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurface)
        VerticalDivider(Modifier.height(16.dp), color = colors.outlineVariant)
        RoutinePicker(state, onSelectRoutine)
        ToolbarIcon(Icons.Default.PlayArrow, "Start ${state.selected?.name.orEmpty()}".trim(), AppColors.live, enabled = !state.isActive && state.selected != null, onClick = onStart)
        ToolbarIcon(Icons.Default.Stop, "Stop", colors.error, enabled = state.isActive, onClick = onStop)
        state.runningName?.let {
            Text("Running $it", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RoutinePicker(state: ShellUiState, onSelect: (com.jagdeep.home.domain.Routine) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val selected = state.selected
    // A running routine stays what it is until stopped.
    val enabled = !state.isActive && state.routines.isNotEmpty()
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClickLabel = "Choose the routine to run", role = Role.DropdownList) { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .widthIn(max = 260.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            selected?.let { Icon(it.kind.icon(), contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.primary) }
            Text(
                selected?.name ?: "No routine",
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.routines.forEach { routine ->
                DropdownMenuItem(
                    leadingIcon = { Icon(routine.kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = {
                        Column {
                            Text(routine.name)
                            Text(routine.kind.label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    },
                    trailingIcon = if (routine.id == selected?.id) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        open = false
                        onSelect(routine)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarIcon(icon: ImageVector, label: String, tint: Color, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
        }
    }
}

@Composable
private fun ChromeStatus(state: ShellUiState) {
    val colors = MaterialTheme.colorScheme
    val (dot, label) = when {
        state.chromeProblem != null -> colors.error to "Can't reach Chrome"
        state.chromeConnected -> AppColors.live to "Chrome connected"
        else -> colors.outline to "Chrome not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** The pages as icons, with Settings at the foot; the toolbar's first button widens it to show their names. */
@Composable
private fun ToolStripe(
    selectedRoute: NavKey,
    items: Map<NavKey, MainNavigationItem>,
    footerItems: Map<NavKey, MainNavigationItem>,
    expanded: Boolean,
    onNavigate: (NavKey) -> Unit,
) {
    val width by animateDpAsState(if (expanded) 168.dp else 44.dp)
    Column(
        Modifier.width(width).fillMaxHeight().padding(top = 8.dp, bottom = 6.dp, start = 6.dp, end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (route, item) -> StripEntry(item, route == selectedRoute, expanded) { onNavigate(route) } }
        Spacer(Modifier.weight(1f))
        footerItems.forEach { (route, item) -> StripEntry(item, route == selectedRoute, expanded) { onNavigate(route) } }
    }
}

/** One page in the strip. Narrow, its name shows on hover, to the right as in Android Studio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StripEntry(item: MainNavigationItem, isSelected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val entry = @Composable {
        Row(
            Modifier.height(32.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) colors.secondaryContainer else Color.Transparent)
                .semantics { selected = isSelected }
                .clickable(onClickLabel = item.title, role = Role.Tab, onClick = onClick)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                item.icon,
                contentDescription = if (expanded) null else item.title,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
            if (expanded) {
                Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (isSelected) colors.onSecondaryContainer else colors.onSurface)
            }
        }
    }
    if (expanded) {
        entry()
    } else {
        val spacing = with(LocalDensity.current) { TOOLTIP_GAP.roundToPx() }
        TooltipBox(
            positionProvider = remember(spacing) { RightOfAnchor(spacing) },
            tooltip = { PlainTooltip { Text(item.title) } },
            state = rememberTooltipState(),
        ) { entry() }
    }
}

/** Places a tooltip to the right of what it describes, centred on it, kept inside the window. */
private class RightOfAnchor(private val spacing: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right + spacing).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val y = (anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

private val TOOLTIP_GAP = 8.dp

/** What's happening now on the left, counting down to a click routine's next click; Chrome's link and the version on the right. */
@Composable
private fun StatusBar(state: ShellUiState, appVersion: String, now: () -> Long) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall
    // Ticks only while a click is due, so an idle app does no work here.
    var nowMillis by remember { mutableStateOf(now()) }
    LaunchedEffect(state.nextClickAtMillis) {
        while (state.nextClickAtMillis != null) {
            nowMillis = now()
            delay(STATUS_TICK_MILLIS)
        }
    }
    val operation = buildString {
        append(state.operation)
        state.nextClickAtMillis?.let { append(" · ").append(nextClickLabel(it, nowMillis)) }
    }
    Row(
        Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            operation,
            style = style,
            color = if (state.isActive) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        ChromeStatus(state)
        Text("${state.toggleHotKey.name} starts or stops", style = style, color = colors.onSurfaceVariant)
        Text(appVersion, style = style, color = colors.onSurfaceVariant)
    }
}

private const val STATUS_TICK_MILLIS = 100L

/** macOS's sidebar symbol: a rounded window outline with the sidebar's edge near the left. */
private val SidebarIcon: ImageVector = ImageVector.Builder(
    name = "Sidebar",
    defaultWidth = 18.dp,
    defaultHeight = 18.dp,
    viewportWidth = 18f,
    viewportHeight = 18f,
).apply {
    val stroke = SolidColor(Color.Black)
    path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4.5f, 3.5f)
        lineTo(13.5f, 3.5f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15.5f, y1 = 5.5f)
        lineTo(15.5f, 12.5f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 13.5f, y1 = 14.5f)
        lineTo(4.5f, 14.5f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 2.5f, y1 = 12.5f)
        lineTo(2.5f, 5.5f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4.5f, y1 = 3.5f)
        close()
    }
    path(stroke = stroke, strokeLineWidth = 1.4f) {
        moveTo(7f, 3.5f)
        lineTo(7f, 14.5f)
    }
}.build()
