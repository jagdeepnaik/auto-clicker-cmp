package com.jagdeep.home.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.automation.HotKey
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.ClickPoint
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.ClickTarget
import com.jagdeep.home.domain.FakeAutomation
import com.jagdeep.home.domain.FakeRoutineRepository
import com.jagdeep.home.domain.FakeRunHistory
import com.jagdeep.home.domain.HomeViewModel
import com.jagdeep.home.domain.RoutineEditorViewModel
import com.jagdeep.home.domain.RoutineLibrary
import com.jagdeep.home.domain.RunLimit
import com.jagdeep.home.ui.screens.HomeScreen
import com.jagdeep.home.ui.screens.RoutineEditorScreen
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class HomeScreensUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val automation = FakeAutomation()
    private val atPoints = ClickRoutine(
        id = "points",
        name = "Chrome button",
        target = ClickTarget.AtPoints(listOf(ClickPoint(100, 200), ClickPoint(300, 400))),
        intervalMillis = 60_000,
        startDelaySeconds = 0,
        limit = RunLimit.Clicks(500),
        onlyInApp = "Google Chrome",
    )
    private val atPointer = ClickRoutine("pointer", "Click at the pointer", startDelaySeconds = 0, intervalMillis = 60_000)
    private val repository = FakeRoutineRepository(RoutineLibrary(listOf(atPointer, atPoints), "pointer"))

    private fun controller() = ClickController(
        automation, repository, MutableStateFlow(AppPreferences()), FakeRunHistory(), scope, { System.currentTimeMillis() }, { "run" },
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `given a selected routine when Start then Stop are pressed then the screen follows the run`() = runDesktopComposeUiTest(1000, 800) {
        val viewModel = HomeViewModel(controller(), repository)
        setContent { AppTheme(darkTheme = false) { HomeScreen(viewModel, appIcon = {}, onNewRoutine = {}, onEditRoutine = {}) } }
        onNodeWithText("Ready").assertExists()
        saveShot("home-ready")

        onNodeWithText("Start").performClick()
        waitUntil(timeoutMillis = 5_000) { automation.clicks.isNotEmpty() }
        onNodeWithText("Clicking").assertExists()
        onNodeWithContentDescription("Edit Chrome button").assertIsNotEnabled()
        saveShot("home-running")

        onNodeWithText("Stop").performClick()
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.lastRun != null }
        onNodeWithText("Ready").assertExists()
        onNodeWithText("Last run: 1 click. Stopped by you.").assertExists()
    }

    @Test
    fun `given macOS hasn't allowed input when shown then the next step is allowing it`() = runDesktopComposeUiTest(1000, 800) {
        automation.inputAccess = InputAccess.NeedsPermission
        val viewModel = HomeViewModel(controller(), repository)
        setContent { AppTheme(darkTheme = true) { HomeScreen(viewModel, appIcon = {}, onNewRoutine = {}, onEditRoutine = {}) } }
        onNodeWithText("Allow access to click").assertExists()
        saveShot("home-needs-access")

        onNodeWithText("Allow access").performClick()
        waitForIdle()
        assertEquals(1, automation.accessRequests)
        onNodeWithText("Start").assertDoesNotExist()
    }

    @Test
    fun `given no routines when shown then it invites creating one`() = runDesktopComposeUiTest(1000, 800) {
        val empty = FakeRoutineRepository(RoutineLibrary())
        var opened = false
        val viewModel = HomeViewModel(controller(), empty)
        setContent { AppTheme(darkTheme = false) { HomeScreen(viewModel, appIcon = {}, onNewRoutine = { opened = true }, onEditRoutine = {}) } }
        onNodeWithText("No routines yet").assertExists()
        onNodeWithText("New routine").performClick()
        assertTrue(opened)
    }

    @Test
    fun `given a blank name when saving in the editor then the error shows and nothing is saved`() = runDesktopComposeUiTest(1000, 1700) {
        val viewModel = RoutineEditorViewModel("points", controller(), repository, newId = { "new" })
        setContent { AppTheme(darkTheme = false) { RoutineEditorScreen(viewModel, onBack = {}) } }
        saveShot("editor")
        viewModel.updateDraft { it.copy(name = " ") }
        onNodeWithText("Save").performScrollTo().performClick()
        onNodeWithText("Enter a name.").assertExists()
        assertEquals("Chrome button", runBlocking { repository.library.value!!.routines.last().name })
    }

    @Test
    fun `given picking on screen when the hot key is pressed then the point appears in the editor`() = runDesktopComposeUiTest(1000, 1700) {
        val viewModel = RoutineEditorViewModel("points", controller(), repository, newId = { "new" })
        setContent { AppTheme(darkTheme = true) { RoutineEditorScreen(viewModel, onBack = {}) } }
        onNodeWithText("Pick on screen").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.capture != null }
        Thread.sleep(200) // Lets the capture collector subscribe on the real dispatcher.
        automation.position = ScreenPoint(640, 480)
        automation.keyPresses.tryEmit(HotKey.F7)
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.draft.points.size == 3 }
        onNodeWithText("Done (1 added)").assertExists()
        saveShot("editor-picking")
    }
}

/** Set UI_TEST_SHOT_DIR to save what a test rendered, for reviewing a layout by eye. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.saveShot(name: String) {
    val directory = System.getenv("UI_TEST_SHOT_DIR") ?: return
    waitForIdle()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
}

