package com.jagdeep.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import com.jagdeep.ui.navigation.MainNavigationItem

@Composable
fun AppBarBottom(
    selectedRoute: NavKey,
    items: Map<NavKey, MainNavigationItem>,
    onBottomBarItemClick: (bottomBarRoute: NavKey) -> Unit
) {
    NavigationBar(containerColor = NavigationBarDefaults.containerColor) {
        items.forEach { (bottomBarRoute, bottomNavItem) ->
            NavigationBarItem(
                enabled = true,
                alwaysShowLabel = false,
                selected = bottomBarRoute == selectedRoute,
                label = { Text(bottomNavItem.title) },
                colors = NavigationBarItemDefaults.colors(),
                onClick = { onBottomBarItemClick(bottomBarRoute) },
                interactionSource = null,
                icon = {
                    Icon(
                        imageVector = bottomNavItem.icon,
                        contentDescription = bottomNavItem.title
                    )
                }
            )
        }
    }
}
