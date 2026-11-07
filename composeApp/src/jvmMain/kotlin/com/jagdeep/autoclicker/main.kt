package com.jagdeep.autoclicker

import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jagdeep.autoclicker.presentation.shell.DesktopToolbarHeight
import javax.swing.SwingUtilities
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jagdeep.common.config.AppConfig
import com.jagdeep.logger.Log
import com.jagdeep.logger.initLoggerJvm
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

fun main() {
    // Before any window exists: Java windows on macOS otherwise stay light, so a dark title bar's
    // name would be drawn in black. Read once, at start-up.
    System.setProperty("apple.awt.application.appearance", "system")
    initLoggerJvm()
    Log.i("-------------> Desktop App Start <-------------", "Desktop App")

    application {
        DesktopWindow()
    }
}

@Composable
@OptIn(FlowPreview::class)
@Suppress("DEPRECATION") // JVM classpath resource used for the native desktop window icon.
private fun ApplicationScope.DesktopWindow() {
    val windowPreferences = remember { DesktopWindowPreferences() }
    val restoredWindow = remember(windowPreferences) { windowPreferences.restore() }
    val windowState = rememberWindowState(
        placement = restoredWindow.placement,
        position = restoredWindow.initialPosition(),
        size = restoredWindow.initialSize(),
    )

    LaunchedEffect(windowState, windowPreferences) {
        snapshotFlow { DesktopWindowSnapshot.from(windowState) }
            .distinctUntilChanged()
            .debounce(400)
            .collect(windowPreferences::save)
    }

    Window(
        onCloseRequest = {
            windowPreferences.save(windowState)
            AppServices.shutdown()
            exitApplication()
        },
        title = AppConfig.APP_NAME,
        state = windowState,
        icon = painterResource("autoclicker-icon.png"),
    ) {
        remember(window) { DesktopTitleBar.prepare(window) }
        // Once the window exists, ask macOS for the taller title bar; until then, and if it can't, the standard one.
        var tallTitleBar by remember { mutableStateOf(false) }
        LaunchedEffect(window) {
            if (DesktopTitleBar.isMac) MacWindowToolbar.makeTall(AppConfig.APP_NAME) { done -> SwingUtilities.invokeLater { tallTitleBar = done } }
        }
        // Re-applied when either the app's theme or the system's light/dark setting changes.
        val systemDark = isSystemInDarkTheme()
        var theme by remember { mutableStateOf<AppliedTheme?>(null) }
        LaunchedEffect(theme, systemDark) {
            theme?.let { DesktopTitleBar.apply(window, it.dark, it.background, it.content) }
        }
        App(
            onThemeApplied = { dark, background, content -> theme = AppliedTheme(dark, background, content) },
            toolbarStartInset = DesktopTitleBar.toolbarStartInset,
            toolbarDragArea = { toolbar -> if (DesktopTitleBar.isMac) WindowDraggableArea { toolbar() } else toolbar() },
            toolbarHeight = if (tallTitleBar) MAC_TALL_TITLE_BAR_HEIGHT else DesktopToolbarHeight,
        )
    }
}

/** The colours the app's theme gave the window, for its title bar. */
private data class AppliedTheme(val dark: Boolean, val background: Color, val content: Color)

/** The height macOS gives a title bar with a compact unified toolbar. */
private val MAC_TALL_TITLE_BAR_HEIGHT = 38.dp
