package com.jagdeep.settings.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.settings.ui.screens.SettingsScreen
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {
    private val gate = object : InputAccessGate {
        var current = InputAccess.Granted
        override fun access() = current
        override fun requestAccess() = true
    }

    @Test
    fun `given the safety switch when clicked then the choice is saved`() = runDesktopComposeUiTest(1000, 1200) {
        val store = InMemoryPreferencesStore(AppPreferences())
        val viewModel = SettingsViewModel(store, gate)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(viewModel, onBack = {}, appVersion = "1.0.0") } }
        saveShot("settings")
        onNodeWithText("Stop when I move the pointer").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { !store.data.first().stopWhenPointerMoves } }
        assertFalse(runBlocking { store.data.first().stopWhenPointerMoves })
    }

    @Test
    fun `given the display switch when clicked then the choice is saved`() = runDesktopComposeUiTest(1000, 1400) {
        val store = InMemoryPreferencesStore(AppPreferences())
        val viewModel = SettingsViewModel(store, gate)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(viewModel, onBack = {}, appVersion = "1.0.0") } }
        onNodeWithText("Keep the display on while running").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { store.data.first().keepDisplayOn } }
    }

    @Test
    fun `given missing access when shown then the permission row offers the way to allow it`() = runDesktopComposeUiTest(1000, 1200) {
        gate.current = InputAccess.NeedsPermission
        val viewModel = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate)
        setContent { AppTheme(darkTheme = true) { SettingsScreen(viewModel, onBack = {}, appVersion = "1.0.0") } }
        onNodeWithText("Allow").assertExists()
    }
}

/** Set UI_TEST_SHOT_DIR to save what a test rendered, for reviewing a layout by eye. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.saveShot(name: String) {
    val directory = System.getenv("UI_TEST_SHOT_DIR") ?: return
    waitForIdle()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
}

