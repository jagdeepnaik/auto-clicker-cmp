package com.jagdeep.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.jagdeep.ui.navigation.MainNavigationItem

/**
 * Persistent desktop navigation for top-level destinations: the Material 3 wide navigation rail.
 * Collapsed it shows icons only (each named for screen readers); expanded, every item shows
 * icon and label side by side.
 */
@Composable
fun DesktopNavigationSidebar(
    selectedRoute: NavKey,
    items: Map<NavKey, MainNavigationItem>,
    onNavigate: (NavKey) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val state = rememberWideNavigationRailState(if (expanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed)
    LaunchedEffect(expanded) { if (expanded) state.expand() else state.collapse() }
    // The rail's own minimum expanded width (220dp) leaves a wide empty band after our short
    // labels, and it keeps that width while collapsing; an explicit width keeps both states tight.
    val railWidth by animateDpAsState(if (expanded) ExpandedRailWidth else CollapsedRailWidth)
    WideNavigationRail(
        modifier = Modifier.width(railWidth),
        state = state,
        // Puts the menu button on the same line as the top app bar title (64dp bar, 48dp button).
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        header = {
            // The rail places its header and items at the start edge, so collapsed content is
            // given the full collapsed width to sit centred.
            Box(
                if (expanded) Modifier.padding(start = 24.dp) else Modifier.width(CollapsedRailWidth),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
                        contentDescription = if (expanded) "Collapse navigation" else "Expand navigation",
                    )
                }
            }
        },
    ) {
        items.forEach { (route, item) ->
            val selected = selectedRoute == route
            val showLabel = expanded
            WideNavigationRailItem(
                selected = selected,
                onClick = { onNavigate(route) },
                // When the label is hidden, the icon names the destination for screen readers.
                icon = { Icon(item.icon, contentDescription = if (showLabel) null else item.title) },
                label = if (showLabel) ({ Text(item.title) }) else null,
                railExpanded = expanded,
                modifier = if (expanded) Modifier else Modifier.width(CollapsedRailWidth),
            )
        }
    }
}

/** Material 3 collapsed wide navigation rail width. */
private val CollapsedRailWidth = 96.dp

/** Fits the longest label ("Settings") with comfortable space before the divider. */
private val ExpandedRailWidth = 180.dp
