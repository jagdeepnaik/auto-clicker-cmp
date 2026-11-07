package com.jagdeep.home.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.ForegroundApps
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.HotKeyMonitor
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.automation.PointerButton
import com.jagdeep.automation.PointerDevice
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.automation.UnsupportedDesktopAutomation
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

private val SPOT = ScreenPoint(640, 880)

@OptIn(ExperimentalCoroutinesApi::class)
class PageControllerTest {
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

    private class FakeDesktop : DesktopAutomation by UnsupportedDesktopAutomation {
        val keys = MutableSharedFlow<HotKey>(extraBufferCapacity = 8)
        var alerts = 0
        var inputAccess = InputAccess.Granted
        var position: ScreenPoint? = ScreenPoint(100, 100)
        var corners = emptySet<ScreenPoint>()
        val clicks = mutableListOf<ScreenPoint?>()
        val broughtForward = mutableListOf<String>()
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
        override val apps = object : ForegroundApps {
            override fun frontmost(): String? = null
            override fun running(): List<String> = emptyList()
            override fun bringToFront(name: String) {
                broughtForward += name
            }
        }
        override val hotKeys = object : HotKeyMonitor {
            override val presses: Flow<HotKey> = keys
        }
        override val access = object : InputAccessGate {
            override fun access() = inputAccess
            override fun requestAccess() = true
        }
        override fun playAlert() {
            alerts++
        }
    }

    private val bridge = FakeBridge()
    private val desktop = FakeDesktop()
    private val history = FakeRunHistory()
    private val json = Json { ignoreUnknownKeys = true }
    private val helper = PageHelperRoutine("h", "Course pages", words = listOf("Next", "Play"), pixelsPerSecond = 90)

    private fun TestScope.controller() =
        PageController(desktop, bridge, MutableStateFlow(AppPreferences()), backgroundScope, history, { currentTime }, { "run" }).also { runCurrent() }

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

    private fun TestScope.page(atBottom: Boolean, signature: String? = "a", focused: Boolean = true, video: String = "none") {
        val quoted = if (signature == null) "null" else "\"$signature\""
        bridge.messages.tryEmit("""{"type":"page","focused":$focused,"atBottom":$atBottom,"signature":$quoted,"video":"$video"}""")
        runCurrent()
    }

    private fun scrollRoutine(rounds: Int = 3, watchVideos: Boolean = true) =
        ScrollClickRoutine("s", "Next pages", ClickPoint(SPOT.x, SPOT.y), rounds = rounds, watchVideos = watchVideos)

    private fun TestScope.startedRun(rounds: Int = 3): PageController {
        val controller = controller()
        assertNull(controller.start(scrollRoutine(rounds)))
        runCurrent()
        return controller
    }

    private fun TestScope.finish(controller: PageController) {
        controller.stop()
        runCurrent()
    }

    // Nothing running

    @Test
    fun `given no routine running when launched then the extension is told to do nothing`() = runTest {
        controller()
        assertFalse(lastCommand().enabled)
    }

    @Test
    fun `given no routine running when the page keys are pressed then nothing happens`() = runTest {
        val controller = controller()
        found("Next")
        press(HotKey.F8)
        press(HotKey.F9)
        assertEquals(0, lastCommand().advance)
        assertFalse(controller.state.value.isScrolling)
    }

    @Test
    fun `given Chrome isn't connected when a routine is started then it explains and doesn't start`() = runTest {
        bridge.connected.value = false
        val controller = controller()
        assertNotNull(controller.start(helper))
        assertNotNull(controller.start(scrollRoutine()))
        assertNull(controller.state.value.activity)
    }

    @Test
    fun `given the link couldn't start when a routine is started then that's the reason given`() = runTest {
        bridge.problem.value = "Port 47613 is in use by another app."
        val controller = controller()
        assertEquals("Port 47613 is in use by another app.", controller.start(helper))
        assertNull(controller.state.value.activity)
    }

    @Test
    fun `given no input permission when started then scroll and click refuses but the page helper doesn't need it`() = runTest {
        desktop.inputAccess = InputAccess.NeedsPermission
        val controller = controller()
        assertNotNull(controller.start(scrollRoutine()))
        assertNull(controller.start(helper))
        finish(controller)
    }

    @Test
    fun `given a routine running when another is started then it's ignored`() = runTest {
        val controller = controller()
        controller.start(helper)
        controller.start(scrollRoutine())
        runCurrent()
        assertTrue(controller.state.value.activity is PageActivity.Helper)
        finish(controller)
    }

    // Page helper routine

    @Test
    fun `given a page helper routine when started then the extension gets its words and speed`() = runTest {
        val controller = controller()
        controller.start(helper)
        runCurrent()
        assertTrue(lastCommand().enabled)
        assertEquals(listOf("Next", "Play"), lastCommand().words)
        assertEquals(90, lastCommand().pixelsPerSecond)
        assertFalse(lastCommand().autoRun)
        finish(controller)
        assertFalse(lastCommand().enabled)
    }

    @Test
    fun `given a highlighted button when the key is pressed then exactly one click is requested per press`() = runTest {
        val controller = controller()
        controller.start(helper)
        found("Next")
        press(HotKey.F8)
        assertEquals(1, lastCommand().advance)
        press(HotKey.F8)
        assertEquals(2, lastCommand().advance)
        finish(controller)
    }

    @Test
    fun `given nothing highlighted, or no key press, then no click is requested`() = runTest {
        val controller = controller()
        controller.start(helper)
        found(null)
        press(HotKey.F8)
        repeat(5) { found("Next") }
        assertEquals(0, lastCommand().advance)
        finish(controller)
    }

    @Test
    fun `given a page helper routine when the scroll key is pressed twice then scrolling starts then stops`() = runTest {
        val controller = controller()
        controller.start(helper)
        press(HotKey.F9)
        assertTrue(lastCommand().scrolling)
        press(HotKey.F9)
        assertFalse(lastCommand().scrolling)
        finish(controller)
    }

    @Test
    fun `given a page helper routine when stopped then it's recorded with its clicks and name`() = runTest {
        val controller = controller()
        controller.start(helper)
        found("Next")
        press(HotKey.F8)
        press(HotKey.F8)
        finish(controller)
        val record = history.added.single()
        assertEquals("Course pages", record.routineName)
        assertEquals(2, record.clicks)
        assertEquals(RunOutcome.StoppedByUser, record.outcome)
        assertEquals(record, controller.lastRun.value)
        assertNull(controller.state.value.activity)
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
    fun `given a video ended when its page helper wants a sound it plays, and otherwise it doesn't`() = runTest {
        val controller = controller()
        bridge.messages.tryEmit("""{"type":"videoEnded"}""")
        runCurrent()
        assertEquals(0, desktop.alerts, "Nothing running")

        controller.start(helper)
        bridge.messages.tryEmit("""{"type":"videoEnded"}""")
        runCurrent()
        assertEquals(1, desktop.alerts)
        finish(controller)

        controller.start(helper.copy(soundOnVideoEnd = false))
        bridge.messages.tryEmit("""{"type":"videoEnded"}""")
        runCurrent()
        assertEquals(1, desktop.alerts)
        finish(controller)
    }

    @Test
    fun `given malformed or unknown messages when received then they're ignored`() = runTest {
        val controller = controller()
        listOf("not json", "{}", """{"type":"other"}""").forEach { bridge.messages.tryEmit(it) }
        runCurrent()
        assertNull(controller.state.value.foundLabel)
        assertEquals(0, desktop.alerts)
    }

    // Scroll and click routine

    @Test
    fun `given a run starts when no page has reported then Chrome is brought forward and it's reading the page`() = runTest {
        val controller = startedRun()
        advanceTimeBy(1_000)
        assertEquals(listOf(BROWSER_APP_NAME), desktop.broughtForward)
        assertEquals(ScrollClickPhase.ReadingPage, (controller.state.value.activity as PageActivity.ScrollClick).progress.phase)
        assertTrue(desktop.clicks.isEmpty())
        finish(controller)
    }

    @Test
    fun `given a run when started then it scrolls and tells the extension, and doesn't click before the bottom`() = runTest {
        val controller = startedRun()
        assertTrue(lastCommand().scrolling)
        assertTrue(lastCommand().autoRun)
        assertTrue(lastCommand().watchVideos)
        page(atBottom = false)
        advanceTimeBy(60_000)
        assertTrue(desktop.clicks.isEmpty())
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
        finish(controller)
    }

    @Test
    fun `given the page changes after each click when rounds run out then it finishes and records the run by name`() = runTest {
        val controller = startedRun(rounds = 2)
        page(atBottom = true, signature = "one")
        advanceTimeBy(2_200)
        page(atBottom = false, signature = null)
        advanceTimeBy(200)
        assertTrue(lastCommand().scrolling, "It scrolls again on the new page")
        page(atBottom = true, signature = "two")
        advanceTimeBy(2_200)
        runCurrent()

        assertEquals(2, desktop.clicks.size)
        assertNull(controller.state.value.activity)
        assertFalse(lastCommand().autoRun)
        val record = history.added.single()
        assertEquals(RunOutcome.Completed, record.outcome)
        assertEquals("Next pages", record.routineName)
    }

    @Test
    fun `given a click that changes nothing when time passes then it stops instead of clicking again`() = runTest {
        val controller = startedRun()
        page(atBottom = true, signature = "same")
        advanceTimeBy(18_200)
        runCurrent()
        assertEquals(1, desktop.clicks.size)
        assertNull(controller.state.value.activity)
        assertEquals(RunOutcome.PageUnchanged, history.added.single().outcome)
    }

    @Test
    fun `given another app in front when at the bottom then it never clicks, and goes on when Chrome returns`() = runTest {
        val controller = startedRun()
        page(atBottom = true, focused = false)
        advanceTimeBy(60_000)
        assertTrue(desktop.clicks.isEmpty())
        page(atBottom = true, focused = true)
        advanceTimeBy(2_200)
        assertEquals(1, desktop.clicks.size)
        finish(controller)
    }

    @Test
    fun `given a video playing at the bottom then it never clicks until the video ends`() = runTest {
        val controller = startedRun()
        page(atBottom = true, video = "playing")
        advanceTimeBy(600_000)
        assertTrue(desktop.clicks.isEmpty())
        page(atBottom = true)
        advanceTimeBy(2_200)
        assertEquals(1, desktop.clicks.size)
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
        assertNull(controller.state.value.activity)
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
        assertEquals(RunOutcome.ScreenCorner, history.added.single().outcome)
    }

    @Test
    fun `given the scroll key during a run when pressed then the run stops`() = runTest {
        val controller = startedRun()
        press(HotKey.F9)
        runCurrent()
        assertNull(controller.state.value.activity)
        assertFalse(lastCommand().scrolling)
        assertEquals(RunOutcome.StoppedByUser, history.added.single().outcome)
    }

    @Test
    fun `given a run when asked to stop then it reports there was one, and afterwards that there isn't`() = runTest {
        val controller = startedRun()
        assertTrue(controller.stop())
        runCurrent()
        assertFalse(controller.stop())
    }

    @Test
    fun `given Chrome disconnects during a run when checked then the run stops and is recorded`() = runTest {
        val controller = startedRun()
        bridge.connected.value = false
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNull(controller.state.value.activity)
        assertEquals(RunOutcome.BrowserDisconnected, history.added.single().outcome)
    }

    @Test
    fun `given a scroll and click run when the click key is pressed then it doesn't click a highlighted button`() = runTest {
        val controller = startedRun()
        found("Next")
        press(HotKey.F8)
        assertEquals(0, lastCommand().advance)
        finish(controller)
    }
}
