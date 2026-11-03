package com.jagdeep.autoclicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
        App()
    }
}
