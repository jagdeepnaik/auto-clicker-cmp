package com.jagdeep.ui.components

import androidx.compose.ui.unit.dp

import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.navigation3.runtime.NavKey
import com.jagdeep.ui.navigation.RouteTopLevel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AppBarTop(
    title: String,
    selectedRoute: NavKey,
    onBackButtonClicked: () -> Unit,
    onHomeButtonClicked: () -> Unit,
    onItemClick: (navRoute: NavKey) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * Optional mark shown before the title on Home, e.g. the app icon. It sits where the back
     * button is on other screens, so every title starts at the same place.
     */
    titleIcon: (@Composable () -> Unit)? = null,
    homeNavigationIcon: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        // Back on every screen except Home, the start destination, which shows [titleIcon] there.
        navigationIcon = {
            if (selectedRoute != RouteTopLevel.Home) {
                IconButton(onClick = onBackButtonClicked) {
                    Icon(imageVector = Icons.Default.ArrowBackIosNew, contentDescription = "Back")
                }
            } else if (titleIcon != null) {
                Box(Modifier.size(NAVIGATION_SLOT_SIZE), contentAlignment = Alignment.Center) { titleIcon() }
            }
        },
        actions = { actions() }
    )
}

/** The navigation icon slot's size, matching a back [IconButton]'s touch target. */
private val NAVIGATION_SLOT_SIZE = 48.dp
