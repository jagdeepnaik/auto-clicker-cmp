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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.jagdeep.autoclicker.presentation.shell.DesktopShell
import com.jagdeep.autoclicker.presentation.shell.DesktopToolbarHeight
import com.jagdeep.autoclicker.presentation.shell.ShellModel
import com.jagdeep.autoclicker.presentation.shell.ShellUiState
import com.jagdeep.ui.platform.isDesktopPlatform
import kotlinx.coroutines.launch
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
/**
 * Application UI shell. On desktop it uses an Android Studio-like layout: a desktop window passes
 * [toolbarStartInset] to leave room for window buttons that share the toolbar's row, [toolbarDragArea]
 * to let the toolbar move the window, and [onThemeApplied] to colour its title bar to match the theme.
 */
@Composable
fun App(
    onThemeApplied: (dark: Boolean, background: Color, content: Color) -> Unit = { _, _, _ -> },
    toolbarStartInset: Dp = 0.dp,
    toolbarDragArea: @Composable (@Composable () -> Unit) -> Unit = { it() },
    toolbarHeight: Dp = DesktopToolbarHeight,
) {
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
    val routineFiles = remember { createRoutineFiles() }
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
        val scheme = MaterialTheme.colorScheme
        // The desktop shell's frame colour, so a title bar joins the toolbar below it.
        SideEffect { onThemeApplied(darkTheme, scheme.surfaceContainer, scheme.onSurface) }
        Surface(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalTopAppBarManager provides topAppBarManager,
                LocalToastManager provides toastHost,
            ) {
                val navHost: @Composable (Modifier) -> Unit = { hostModifier ->
                    AppNavHost(
                        navigationState = navigationState,
                        navigator = navigator,
                        topLevelRoutes = TOP_LEVEL_ROUTES,
                        preferencesStore = preferencesStore,
                        controller = services.controller,
                        routineRepository = services.routines,
                        browserConnected = remember(services) { services.page.state.map { it.isConnected } },
                        runHistory = services.runHistory,
                        liveLog = services.liveLog,
                        routineFiles = routineFiles,
                        accessGate = services.automation.access,
                        appVersion = AppConfig.APP_VERSION_NAME,
                        now = AppClock::nowMillis,
                        newId = AppServices::newId,
                        appIcon = { AppLogo() },
                        modifier = hostModifier,
                    )
                }
                if (isDesktopPlatform()) {
                    val shellModel = remember(services) { ShellModel(services.controller, services.routines) }
                    val shellState by shellModel.state.collectAsState(ShellUiState())
                    val shellScope = rememberCoroutineScope()
                    DesktopShell(
                        state = shellState,
                        onSelectRoutine = { routine -> shellScope.launch { shellModel.select(routine) } },
                        onStart = shellModel::start,
                        onStop = shellModel::stop,
                        selectedRoute = navigationState.topLevelRoute,
                        // Settings sits at the foot of the strip, as in Android Studio.
                        navigationItems = navigationItems.filterKeys { it != RouteTopLevel.Settings },
                        footerItems = navigationItems.filterKeys { it == RouteTopLevel.Settings },
                        onNavigate = navigator::navigate,
                        now = AppClock::nowMillis,
                        appIcon = { AppLogo(18.dp) },
                        appName = AppConfig.APP_NAME,
                        appVersion = AppConfig.APP_VERSION_NAME,
                        toolbarStartInset = toolbarStartInset,
                        toolbarDragArea = toolbarDragArea,
                        toolbarHeight = toolbarHeight,
                        topBar = { topAppBar.content() },
                    ) { navHost(Modifier.fillMaxSize()) }
                } else {
                    AdaptiveNavigationScaffold(
                        selectedRoute = navigationState.topLevelRoute,
                        navigationItems = navigationItems,
                        onNavigate = navigator::navigate,
                        topBar = { topAppBar.content() },
                    ) { padding -> navHost(Modifier.fillMaxSize().padding(padding)) }
                }
                ToastHost(toastHost)
            }
        }
    }
}

/** The app mark for the start screen's navigation slot; the title names the app for screen readers. */
@Composable
private fun AppLogo(size: Dp = 28.dp) {
    Image(painterResource(Res.drawable.autoclicker_logo), contentDescription = null, modifier = Modifier.size(size))
}
