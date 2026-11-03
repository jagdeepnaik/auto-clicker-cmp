package com.jagdeep.pagehelper.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.automation.UnsupportedDesktopAutomation
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.pagehelper.domain.PageHelperController
import com.jagdeep.pagehelper.domain.PageHelperViewModel
import com.jagdeep.pagehelper.ui.screens.PageHelperScreen
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTestApi::class)
class PageHelperScreenUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Bridge(connected: Boolean) : LocalBridge {
        override val connected = MutableStateFlow(connected)
        override val problem = MutableStateFlow<String?>(null)
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
        override val incoming: Flow<String> = messages
        override fun publish(stateJson: String) = Unit
        override fun close() = Unit
    }

    private val history = object : RunHistoryStore {
        override val records = MutableStateFlow<List<RunRecord>>(emptyList())
        override suspend fun add(record: RunRecord) = Unit
        override suspend fun clear() = Unit
    }

    private val desktop = object : DesktopAutomation by UnsupportedDesktopAutomation {
        override val access = object : InputAccessGate {
            override fun access() = InputAccess.Granted
            override fun requestAccess() = true
        }
    }

    private fun viewModel(bridge: LocalBridge, saved: String = "") = PageHelperViewModel(
        PageHelperController(desktop, bridge, InMemoryPreferencesStore(saved), MutableStateFlow(AppPreferences()), scope, history, { 0L }, { "run" }),
        installExtension = { null },
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `given no extension when shown then it asks for the setup steps`() = runDesktopComposeUiTest(1000, 1100) {
        val viewModel = viewModel(Bridge(connected = false))
        setContent { AppTheme(darkTheme = false) { Surface { PageHelperScreen(viewModel, onBack = {}) } } }
        onNodeWithText("Waiting for Chrome").assertExists()
        onNodeWithText("Show extension folder").assertExists()
        saveShot("page-helper-waiting")
    }

    @Test
    fun `given a found button when shown then it names the button and the key`() = runDesktopComposeUiTest(1000, 1100) {
        val bridge = Bridge(connected = true)
        bridge.messages.tryEmit("""{"type":"found","label":"Next"}""")
        val viewModel = viewModel(bridge, saved = """{"enabled":true}""")
        setContent { AppTheme(darkTheme = true) { Surface { PageHelperScreen(viewModel, onBack = {}) } } }
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.helper.foundLabel == "Next" }
        onNodeWithText("Next step: Next").assertExists()
        onNodeWithText("Press F8 to click it.").assertExists()
        saveShot("page-helper-found")
    }

    @Test
    fun `given a picked spot when shown then it shows the spot and offers to start`() = runDesktopComposeUiTest(1000, 1400) {
        val viewModel = viewModel(Bridge(connected = true), saved = """{"enabled":true,"clickPoint":{"x":640,"y":880}}""")
        setContent { AppTheme(darkTheme = false) { Surface { PageHelperScreen(viewModel, onBack = {}) } } }
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.helper.settings.clickPoint != null }
        onNodeWithText("Clicks at 640, 880 on your screen.").assertExists()
        onNodeWithText("Start scroll and click").assertIsEnabled()
        saveShot("page-helper-scroll-click")
    }

    @Test
    fun `given no picked spot when shown then starting is unavailable`() = runDesktopComposeUiTest(1000, 1400) {
        val viewModel = viewModel(Bridge(connected = true), saved = """{"enabled":true}""")
        setContent { AppTheme(darkTheme = false) { Surface { PageHelperScreen(viewModel, onBack = {}) } } }
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.helper.settings.enabled }
        onNodeWithText("No spot picked yet.").assertExists()
        onNodeWithText("Start scroll and click").assertIsNotEnabled()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.saveShot(name: String) {
    val directory = System.getenv("UI_TEST_SHOT_DIR") ?: return
    waitForIdle()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
}
