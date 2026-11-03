package com.jagdeep.pagehelper.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.HotKeyMonitor
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.UnsupportedDesktopAutomation
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.automation.PointerButton
import com.jagdeep.automation.PointerDevice
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json

private val SPOT = ScreenPoint(640, 880)

@OptIn(ExperimentalCoroutinesApi::class)
class PageHelperTest {
    private class FakeBridge : LocalBridge {
        override val connected = MutableStateFlow(true)
        override val problem = MutableStateFlow<String?>(null)
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val incoming: Flow<String> = messages
        val published = mutableListOf<String>()
        override fun publish(stateJson: String) {
            published += stateJson
        }
        override fun close() = Unit
    }

    private class FakeDesktop(val supported: Boolean = true) : DesktopAutomation by UnsupportedDesktopAutomation {
        val keys = MutableSharedFlow<HotKey>(extraBufferCapacity = 8)
        var alerts = 0
        var access_ = InputAccess.Granted
        var position: ScreenPoint? = ScreenPoint(100, 100)
        var corners = emptySet<ScreenPoint>()
        val clicks = mutableListOf<ScreenPoint?>()
        override val pointer = object : PointerDevice {
            override fun position() = this@FakeDesktop.position
            override fun click(at: ScreenPoint?, button: PointerButton, count: Int): Boolean {
                if (at != null) this@FakeDesktop.position = at
                clicks += at
                return true
            }
            override fun isScreenCorner(point: ScreenPoint) = point in corners
            override fun isOverOwnFocusedWindow(point: ScreenPoint) = false
        }
        override val hotKeys = object : HotKeyMonitor {
            override val presses: Flow<HotKey> = keys
        }
        override val access = object : InputAccessGate {
            override fun access() = if (supported) access_ else InputAccess.Unsupported
            override fun requestAccess() = true
        }
        override fun playAlert() {
            alerts++
        }
    }

    private val bridge = FakeBridge()
    private val desktop = FakeDesktop()
    private val store = InMemoryPreferencesStore("")
    private val json = Json { ignoreUnknownKeys = true }

    private val history = object : RunHistoryStore {
        val added = mutableListOf<RunRecord>()
        override val records = MutableStateFlow<List<RunRecord>>(emptyList())
        override suspend fun add(record: RunRecord) {
            added += record
        }
        override suspend fun clear() = added.clear()
    }

    private fun TestScope.controller(otherRunActive: Boolean = false) =
        PageHelperController(desktop, bridge, store, MutableStateFlow(AppPreferences()), backgroundScope, history, { currentTime }, { "run" }, { otherRunActive })
            .also { runCurrent() }

    private fun TestScope.page(atBottom: Boolean, signature: String? = "a", focused: Boolean = true) {
        val quoted = if (signature == null) "null" else "\"$signature\""
        bridge.messages.tryEmit("""{"type":"page","focused":$focused,"atBottom":$atBottom,"signature":$quoted}""")
        runCurrent()
    }

    /** Turned on, with a spot picked at [SPOT], and started. */
    private fun TestScope.startedRun(rounds: Int = 3): PageHelperController {
        val controller = controller()
        controller.setEnabled(true)
        controller.setScrollClickRounds(rounds)
        controller.startPickingPoint()
        desktop.position = SPOT
        press(HotKey.F7)
        desktop.position = ScreenPoint(100, 100)
        controller.startScrollClick()
        runCurrent()
        return controller
    }

    private fun TestScope.finish(controller: PageHelperController) {
        controller.stopScrollClick()
        runCurrent()
    }

    private fun lastCommand() = json.decodeFromString(HelperCommand.serializer(), bridge.published.last())

    private fun TestScope.found(label: String?) {
        val body = if (label == null) """{"type":"found","label":null}""" else """{"type":"found","label":"$label"}"""
        bridge.messages.tryEmit(body)
        runCurrent()
    }

    private fun TestScope.press(key: HotKey) {
        desktop.keys.tryEmit(key)
        runCurrent()
    }

    @Test
    fun `given the first launch when read then the helper is off and tells the extension so`() = runTest {
        val controller = controller()
        assertFalse(controller.state.value.settings.enabled)
        assertFalse(lastCommand().enabled)
    }

    @Test
    fun `given the helper is off when the keys are pressed then nothing happens`() = runTest {
        val controller = controller()
        found("Next")
        press(HotKey.F8)
        press(HotKey.F9)
        assertEquals(0, lastCommand().advance)
        assertFalse(controller.state.value.isScrolling)
    }

    @Test
    fun `given a highlighted button when the key is pressed then exactly one click is requested per press`() = runTest {
        val controller = controller()
        controller.setEnabled(true)
        found("Next")
        press(HotKey.F8)
        assertEquals(1, lastCommand().advance)
        press(HotKey.F8)
        assertEquals(2, lastCommand().advance)
    }

    @Test
    fun `given nothing highlighted when the key is pressed then no click is requested`() = runTest {
        val controller = controller()
        controller.setEnabled(true)
        found(null)
        press(HotKey.F8)
        assertEquals(0, lastCommand().advance)
    }

    @Test
    fun `given a found button with no key press when time passes then no click is ever requested`() = runTest {
        val controller = controller()
        controller.setEnabled(true)
        repeat(5) { found("Next") }
        assertEquals(0, lastCommand().advance)
    }

    @Test
    fun `given the scroll key when pressed twice then scrolling starts then stops`() = runTest {
        val controller = controller()
        controller.setEnabled(true)
        press(HotKey.F9)
        assertTrue(lastCommand().scrolling)
        press(HotKey.F9)
        assertFalse(lastCommand().scrolling)
    }

    @Test
    fun `given scrolling when the helper is turned off then scrolling stops too`() = runTest {
        val controller = controller()
        controller.setEnabled(true)
        press(HotKey.F9)
        controller.setEnabled(false)
        runCurrent()
        assertFalse(lastCommand().scrolling)
        assertFalse(lastCommand().enabled)
    }

    @Test
    fun `given a lost connection when shown then nothing counts as highlighted`() = runTest {
        val controller = controller()
        found("Next")
        assertEquals("Next", controller.state.value.foundLabel)
        bridge.connected.value = false
        runCurrent()
        assertNull(controller.state.value.foundLabel)
    }

    @Test
    fun `given a video ended when sound is on it plays, and when off it doesn't`() = runTest {
        val controller = controller()
        bridge.messages.tryEmit("""{"type":"videoEnded"}""")
        runCurrent()
        assertEquals(1, desktop.alerts)

        controller.setSoundOnVideoEnd(false)
        runCurrent()
        bridge.messages.tryEmit("""{"type":"videoEnded"}""")
        runCurrent()
        assertEquals(1, desktop.alerts)
    }

    @Test
    fun `given malformed or unknown messages when received then they're ignored`() = runTest {
        val controller = controller()
        listOf("not json", "{}", """{"type":"other"}""").forEach { bridge.messages.tryEmit(it) }
        runCurrent()
        assertNull(controller.state.value.foundLabel)
        assertEquals(0, desktop.alerts)
    }

    @Test
    fun `given settings when changed then a new instance reads them back, with speed kept in range`() = runTest {
        val controller = controller()
        controller.setEnabled(true)
        controller.setWords(listOf("Weiter"))
        controller.setSpeed(9_999)
        runCurrent()
        val settings = json.decodeFromString(PageHelperSettings.serializer(), store.data.first())
        assertEquals(PageHelperSettings(enabled = true, words = listOf("Weiter"), pixelsPerSecond = MAX_PIXELS_PER_SECOND), settings)
    }

    @Test
    fun `given the capture key when picking then the spot is saved, and when not picking it isn't`() = runTest {
        val controller = controller()
        desktop.position = SPOT
        press(HotKey.F7)
        assertNull(controller.state.value.settings.clickPoint)

        controller.startPickingPoint()
        press(HotKey.F7)
        runCurrent()
        assertEquals(SavedPoint(SPOT.x, SPOT.y), controller.state.value.settings.clickPoint)
        assertFalse(controller.state.value.isPickingPoint)
    }

    @Test
    fun `given what start needs is missing when started then it explains and doesn't run`() = runTest {
        val controller = controller()
        controller.startScrollClick()
        assertEquals("Turn on the page helper first.", controller.state.value.scrollClickNote)

        controller.setEnabled(true)
        controller.startScrollClick()
        assertEquals("Pick the spot to click first.", controller.state.value.scrollClickNote)
        assertNull(controller.state.value.scrollClick)

        controller.startPickingPoint()
        press(HotKey.F7)
        desktop.access_ = InputAccess.NeedsPermission
        controller.startScrollClick()
        assertNull(controller.state.value.scrollClick)
    }

    @Test
    fun `given a routine is running when started then it refuses`() = runTest {
        val controller = controller(otherRunActive = true)
        controller.setEnabled(true)
        controller.startPickingPoint()
        press(HotKey.F7)
        controller.startScrollClick()
        assertEquals("Stop the running routine first.", controller.state.value.scrollClickNote)
        assertNull(controller.state.value.scrollClick)
    }

    @Test
    fun `given a run when started then it scrolls and tells the extension, and doesn't click before the bottom`() = runTest {
        val controller = startedRun()
        assertTrue(lastCommand().scrolling)
        assertTrue(lastCommand().autoRun)
        page(atBottom = false)
        advanceTimeBy(60_000)
        assertTrue(desktop.clicks.isEmpty())
        assertEquals(ScrollClickPhase.Scrolling, controller.state.value.scrollClick?.phase)
        finish(controller)
    }

    @Test
    fun `given the bottom when it holds then one click lands on the spot and scrolling pauses`() = runTest {
        val controller = startedRun()
        page(atBottom = true)
        advanceTimeBy(1_000)
        assertTrue(desktop.clicks.isEmpty())
        advanceTimeBy(1_200)
        assertEquals(listOf<ScreenPoint?>(SPOT), desktop.clicks)
        assertFalse(lastCommand().scrolling)
        assertEquals(ScrollClickPhase.WaitingForPage, controller.state.value.scrollClick?.phase)
        finish(controller)
    }

    @Test
    fun `given more content arrives during the hold when the page grows then it keeps scrolling instead of clicking`() = runTest {
        val controller = startedRun()
        page(atBottom = true)
        advanceTimeBy(1_500)
        page(atBottom = false)
        advanceTimeBy(5_000)
        assertTrue(desktop.clicks.isEmpty())
        page(atBottom = true)
        advanceTimeBy(1_500)
        assertTrue(desktop.clicks.isEmpty())
        finish(controller)
    }

    @Test
    fun `given the page changes after each click when rounds run out then it finishes and records the run`() = runTest {
        val controller = startedRun(rounds = 2)
        page(atBottom = true, signature = "one")
        advanceTimeBy(2_200)
        assertEquals(1, desktop.clicks.size)

        page(atBottom = false, signature = null)
        advanceTimeBy(200)
        assertTrue(lastCommand().scrolling, "It scrolls again on the new page")
        page(atBottom = true, signature = "two")
        advanceTimeBy(2_200)
        runCurrent()

        assertEquals(2, desktop.clicks.size)
        assertNull(controller.state.value.scrollClick)
        assertFalse(lastCommand().autoRun)
        assertFalse(lastCommand().scrolling)
        val record = history.added.single()
        assertEquals(RunOutcome.Completed, record.outcome)
        assertEquals(2, record.clicks)
        assertEquals(SCROLL_CLICK_RUN_NAME, record.routineName)
    }

    @Test
    fun `given a short page with new contents when clicked then the next round goes on without scrolling first`() = runTest {
        startedRun(rounds = 2)
        page(atBottom = true, signature = "one")
        advanceTimeBy(2_200)
        page(atBottom = true, signature = "two")
        advanceTimeBy(2_300)
        runCurrent()
        assertEquals(2, desktop.clicks.size)
    }

    @Test
    fun `given a click that changes nothing when time passes then it stops instead of clicking again`() = runTest {
        val controller = startedRun()
        page(atBottom = true, signature = "same")
        advanceTimeBy(2_200)
        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(1, desktop.clicks.size)
        assertNull(controller.state.value.scrollClick)
        assertEquals(RunOutcome.PageUnchanged, history.added.single().outcome)
    }

    @Test
    fun `given another app in front when at the bottom then it never clicks, and goes on when Chrome returns`() = runTest {
        val controller = startedRun()
        page(atBottom = true, focused = false)
        advanceTimeBy(60_000)
        assertTrue(desktop.clicks.isEmpty())
        assertEquals(ScrollClickPhase.WaitingForChrome, controller.state.value.scrollClick?.phase)

        page(atBottom = true, focused = true)
        advanceTimeBy(2_200)
        assertEquals(1, desktop.clicks.size)
        finish(controller)
    }

    @Test
    fun `given Chrome leaves the front just before the click when the hold ends then it doesn't click`() = runTest {
        val controller = startedRun()
        page(atBottom = true)
        advanceTimeBy(1_990)
        page(atBottom = true, focused = false)
        advanceTimeBy(5_000)
        assertTrue(desktop.clicks.isEmpty())
        finish(controller)
    }

    @Test
    fun `given the pointer moved by hand after a click when checked then the run stops`() = runTest {
        val controller = startedRun()
        page(atBottom = true)
        advanceTimeBy(2_200)
        desktop.position = ScreenPoint(SPOT.x + 50, SPOT.y)
        advanceTimeBy(100)
        runCurrent()
        assertNull(controller.state.value.scrollClick)
        assertEquals(RunOutcome.PointerMoved, history.added.single().outcome)
    }

    @Test
    fun `given the pointer in a screen corner when checked then the run stops before any click`() = runTest {
        val controller = startedRun()
        desktop.corners = setOf(ScreenPoint(0, 0))
        desktop.position = ScreenPoint(0, 0)
        page(atBottom = true)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(desktop.clicks.isEmpty())
        assertNull(controller.state.value.scrollClick)
        assertEquals(RunOutcome.ScreenCorner, history.added.single().outcome)
    }

    @Test
    fun `given the scroll key during a run when pressed then the run stops and doesn't restart scrolling`() = runTest {
        val controller = startedRun()
        press(HotKey.F9)
        runCurrent()
        assertNull(controller.state.value.scrollClick)
        assertFalse(lastCommand().scrolling)
        assertEquals(RunOutcome.StoppedByUser, history.added.single().outcome)
    }

    @Test
    fun `given a run when asked to stop then it reports there was one, and afterwards that there isn't`() = runTest {
        val controller = startedRun()
        assertTrue(controller.stopScrollClick())
        runCurrent()
        assertFalse(controller.stopScrollClick())
    }

    @Test
    fun `given Chrome disconnects during a run when checked then the run stops and is recorded`() = runTest {
        val controller = startedRun()
        bridge.connected.value = false
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNull(controller.state.value.scrollClick)
        assertEquals(RunOutcome.BrowserDisconnected, history.added.single().outcome)
    }

    @Test
    fun `given the helper is turned off during a run when checked then no more clicks happen`() = runTest {
        val controller = startedRun()
        controller.setEnabled(false)
        page(atBottom = true)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(desktop.clicks.isEmpty())
        assertNull(controller.state.value.scrollClick)
    }

    @Test
    fun `given a run when the click key is pressed then it doesn't also click the highlighted button`() = runTest {
        val controller = startedRun()
        found("Next")
        press(HotKey.F8)
        assertEquals(0, lastCommand().advance)
        finish(controller)
    }

    @Test
    fun `given rounds text when typed then only a number in range is saved`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val controller = controller()
            val viewModel = PageHelperViewModel(controller, installExtension = { null })
            runCurrent()
            viewModel.updateRounds("abc")
            runCurrent()
            assertNotNull(viewModel.state.value.roundsError)
            assertEquals(DEFAULT_SCROLL_CLICK_ROUNDS, controller.state.value.settings.scrollClickRounds)

            viewModel.updateRounds("7")
            runCurrent()
            assertNull(viewModel.state.value.roundsError)
            assertEquals(7, controller.state.value.settings.scrollClickRounds)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `given word text when parsed then order is kept and blanks and repeats are dropped`() {
        assertEquals(listOf("Next", "Play"), parseWords(" Next, ,next , Play,"))
    }

    @Test
    fun `given a blank word list when typed then it shows an error and keeps the saved words`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val controller = controller()
            val viewModel = PageHelperViewModel(controller, installExtension = { null })
            runCurrent()
            viewModel.updateWords("  , ")
            runCurrent()
            assertNotNull(viewModel.state.value.wordsError)
            assertEquals(DEFAULT_WORDS, controller.state.value.settings.words)

            viewModel.updateWords("Next, ")
            runCurrent()
            assertNull(viewModel.state.value.wordsError)
            assertEquals("Next, ", viewModel.state.value.wordsDraft)
            assertEquals(listOf("Next"), controller.state.value.settings.words)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `given the extension can't be installed when asked then the reason is shown`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = PageHelperViewModel(controller(), installExtension = { "No disk space." })
            viewModel.showExtension()
            runCurrent()
            assertEquals("No disk space.", viewModel.messages.first())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
