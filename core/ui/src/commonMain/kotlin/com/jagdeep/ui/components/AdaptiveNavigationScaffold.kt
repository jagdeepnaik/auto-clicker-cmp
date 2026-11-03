package com.jagdeep.ui.components

import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.jagdeep.ui.navigation.MainNavigationItem
import com.jagdeep.ui.platform.isDesktopPlatform

/**
 * Shared application shell for top-level destinations.
 *
 * Desktop uses a collapsible persistent sidebar, while compact layouts use the
 * bottom navigation pattern. Feature screens only provide their top bar and content.
 */
@Composable
fun AdaptiveNavigationScaffold(
    selectedRoute: NavKey,
    navigationItems: Map<NavKey, MainNavigationItem>,
    onNavigate: (NavKey) -> Unit,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (isDesktopPlatform()) {
        var sidebarExpanded by remember { mutableStateOf(true) }
        BoxWithConstraints(modifier.fillMaxSize()) {
            LaunchedEffect(maxWidth) {
                if (maxWidth < DesktopSidebarAutoCollapseWidth) sidebarExpanded = false
            }
            Row(Modifier.fillMaxSize()) {
                DesktopNavigationSidebar(
                    selectedRoute = selectedRoute,
                    items = navigationItems,
                    onNavigate = onNavigate,
                    expanded = sidebarExpanded,
                    onToggle = { sidebarExpanded = !sidebarExpanded },
                )
                // Inset like the divider between Home's columns, rather than running edge to edge.
                VerticalDivider(Modifier.padding(vertical = 24.dp))
                Scaffold(
                    // Breathing room after the navigation divider; title and content shift together.
                    modifier = Modifier.weight(1f).padding(start = DesktopContentStartPadding),
                    topBar = topBar,
                ) { contentPadding ->
                    content(contentPadding)
                }
            }
        }
    } else {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = {
                AppBarBottom(
                    selectedRoute = selectedRoute,
                    items = navigationItems,
                    onBottomBarItemClick = onNavigate,
                )
            },
            content = content,
        )
    }
}

private val DesktopSidebarAutoCollapseWidth = 960.dp
private val DesktopContentStartPadding = 16.dp
