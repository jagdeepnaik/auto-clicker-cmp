package com.jagdeep.autoclicker

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdsClick
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import autoclicker.composeapp.generated.resources.Res
import autoclicker.composeapp.generated.resources.autoclicker_logo
import com.jagdeep.autoclicker.presentation.navigation.AppNavHost
import com.jagdeep.autoclicker.presentation.navigation.rememberNavigationState
import com.jagdeep.common.config.AppConfig
import com.jagdeep.common.time.AppClock
import com.jagdeep.shared.preferences.ThemeMode
import com.jagdeep.ui.components.AdaptiveNavigationScaffold
import com.jagdeep.ui.components.LocalToastManager
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.ToastHost
import com.jagdeep.ui.components.TopAppBarState
import com.jagdeep.ui.components.rememberToastHostState
import com.jagdeep.ui.navigation.MainNavigationItem
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteTopLevel
import com.jagdeep.ui.themes.AppTheme
import com.jagdeep.ui.themes.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.painterResource

/** Top-level destinations, in the order they appear in navigation. */
internal val TOP_LEVEL_ROUTES: List<NavKey> = listOf(RouteTopLevel.Home, RouteTopLevel.Activity, RouteTopLevel.Settings)

/** Application UI shell: hands the process-scoped [AppServices] to feature entry providers. */
@Composable
fun App() {
    // Services outlive this composition (activity recreation must not stop a run).
    val services = remember { AppServices.get() }
    val preferencesStore = services.preferences
    val topAppBarManager = remember { MutableStateFlow(TopAppBarState()) }
    val toastHost = rememberToastHostState()
    val topAppBar by topAppBarManager.collectAsState()
    val navigationItems = remember {
        linkedMapOf<NavKey, MainNavigationItem>(
            RouteTopLevel.Home to MainNavigationItem("Routines", Icons.Default.AdsClick),
            RouteTopLevel.Activity to MainNavigationItem("Activity", Icons.Default.History),
            RouteTopLevel.Settings to MainNavigationItem("Settings", Icons.Default.Settings),
        )
    }
    val navigationState = rememberNavigationState(RouteTopLevel.Home, TOP_LEVEL_ROUTES.toSet())
    val navigator = remember { Navigator(navigationState) }
    // Explanations can come from the hot key, whichever tab is open.
    LaunchedEffect(services) { services.controller.messages.collect(toastHost::show) }

    // Null until the saved choice is read, so the app never flashes the wrong theme on launch.
    val appearance by remember(preferencesStore) { preferencesStore.data.map { it.themeMode to it.colorPalette } }
        .collectAsState(null)
    val (themeMode, colorPalette) = appearance ?: return
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // Same names on both sides; feature:shared can't depend on core:ui's enum.
    val palette = Palette.valueOf(colorPalette.name)
    AppTheme(darkTheme = darkTheme, palette = palette) {
        Surface(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalTopAppBarManager provides topAppBarManager,
                LocalToastManager provides toastHost,
            ) {
                AdaptiveNavigationScaffold(
                    selectedRoute = navigationState.topLevelRoute,
                    navigationItems = navigationItems,
                    onNavigate = navigator::navigate,
                    topBar = { topAppBar.content() },
                ) { padding ->
                    AppNavHost(
                        navigationState = navigationState,
                        navigator = navigator,
                        topLevelRoutes = TOP_LEVEL_ROUTES,
                        preferencesStore = preferencesStore,
                        controller = services.controller,
                        routineRepository = services.routines,
                        browserConnected = remember(services) { services.page.state.map { it.isConnected } },
                        runHistory = services.runHistory,
                        accessGate = services.automation.access,
                        appVersion = AppConfig.APP_VERSION_NAME,
                        now = AppClock::nowMillis,
                        newId = AppServices::newId,
                        appIcon = { AppLogo() },
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
                ToastHost(toastHost)
            }
        }
    }
}

/** The app mark for the start screen's navigation slot; the title names the app for screen readers. */
@Composable
private fun AppLogo() {
    Image(painterResource(Res.drawable.autoclicker_logo), contentDescription = null, modifier = Modifier.size(28.dp))
}
