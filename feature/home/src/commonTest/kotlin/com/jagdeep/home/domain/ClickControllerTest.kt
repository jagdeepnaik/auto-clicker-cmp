package com.jagdeep.home.domain

import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ClickControllerTest {
    private val automation = FakeAutomation()
    private val routine = ClickRoutine("r", "Chrome button", intervalMillis = 1_000, startDelaySeconds = 2)
    private val history = FakeRunHistory()
    private val preferences = MutableStateFlow(AppPreferences())

    private fun TestScope.controller(library: RoutineLibrary = RoutineLibrary(listOf(routine), "r")): ClickController {
        var id = 0
        return ClickController(automation, FakeRoutineRepository(library), preferences, history, backgroundScope, { currentTime }, { "run-${++id}" })
            .also { runCurrent() }
    }

    @Test
    fun `given another run going when the hot key is pressed then it stops that run and starts nothing`() = runTest {
        var otherRunning = true
        val controller = ClickController(
            automation, FakeRoutineRepository(RoutineLibrary(listOf(routine), "r")), preferences, history, backgroundScope, { currentTime }, { "run" },
            stopOtherRun = { otherRunning.also { otherRunning = false } },
        )
        runCurrent()
        automation.keyPresses.tryEmit(preferences.value.toggleHotKey)
        runCurrent()
        assertFalse(otherRunning)
        assertEquals(RunState.Idle, controller.state.value)

        automation.keyPresses.tryEmit(preferences.value.toggleHotKey)
        runCurrent()
        assertIs<RunState.CountingDown>(controller.state.value)
        controller.stop()
    }

    @Test
    fun `given a start delay when started then it counts down and sends nothing until it ends`() = runTest {
        val controller = controller()
        controller.start(routine)
        runCurrent()
        assertEquals(RunState.CountingDown(routine, 2), controller.state.value)
        advanceTimeBy(1_001)
        assertEquals(RunState.CountingDown(routine, 1), controller.state.value)
        assertEquals(0, automation.clicks.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, automation.clicks.size)
        assertEquals(RunState.Running(routine, 1, null), controller.state.value)
        controller.stop()
    }

    @Test
    fun `given the run is stopped when finished then it is recorded with its clicks and outcome`() = runTest {
        val controller = controller()
        controller.start(routine.copy(startDelaySeconds = 0))
        advanceTimeBy(2_500)
        runCurrent()
        controller.stop()
        runCurrent()

        assertEquals(RunState.Idle, controller.state.value)
        val record = history.added.single()
        assertEquals(3, record.clicks)
        assertEquals(RunOutcome.StoppedByUser, record.outcome)
        assertEquals("Chrome button", record.routineName)
        assertEquals(record, controller.lastRun.value)
    }

    @Test
    fun `given a countdown when stopped then no clicks happen and nothing is recorded`() = runTest {
        val controller = controller()
        controller.start(routine)
        advanceTimeBy(500)
        controller.stop()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
        assertEquals(0, automation.clicks.size)
        assertTrue(history.added.isEmpty())
    }

    @Test
    fun `given a stop straight after start when the run hasn't begun then it still returns to idle`() = runTest {
        val controller = controller()
        controller.start(routine)
        controller.stop()
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
        assertEquals(0, automation.clicks.size)
    }

    @Test
    fun `given input that throws during a run when it fails then it is recorded and returns to idle`() = runTest {
        val controller = controller()
        automation.clickThrows = true
        controller.start(routine.copy(startDelaySeconds = 0))
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
        assertEquals(RunOutcome.Failed, history.added.single().outcome)
    }

    @Test
    fun `given a routine that finishes by itself when done then it returns to idle and records completion`() = runTest {
        val controller = controller()
        controller.start(routine.copy(startDelaySeconds = 0, limit = RunLimit.Clicks(2)))
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
        assertEquals(RunOutcome.Completed, history.added.single().outcome)
    }

    @Test
    fun `given the hot key when pressed twice then it starts the selected routine then stops it`() = runTest {
        val controller = controller()
        automation.keyPresses.emit(HotKey.F6)
        runCurrent()
        assertIs<RunState.CountingDown>(controller.state.value)

        automation.keyPresses.emit(HotKey.F6)
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
    }

    @Test
    fun `given a changed hot key when the old key is pressed then nothing starts`() = runTest {
        preferences.value = AppPreferences(toggleHotKey = HotKey.F9)
        val controller = controller()
        automation.keyPresses.emit(HotKey.F6)
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)

        automation.keyPresses.emit(HotKey.F9)
        runCurrent()
        assertIs<RunState.CountingDown>(controller.state.value)
        controller.stop()
    }

    @Test
    fun `given a running routine when started again then a second run never begins`() = runTest {
        val controller = controller()
        controller.start(routine.copy(startDelaySeconds = 0))
        controller.start(routine.copy(startDelaySeconds = 0))
        runCurrent()
        assertEquals(1, automation.clicks.size)
        controller.stop()
    }

    @Test
    fun `given no routines when the hot key is pressed then it explains instead of starting`() = runTest {
        val controller = controller(RoutineLibrary())
        val messages = mutableListOf<String>()
        backgroundScope.launch { controller.messages.collect { messages += it } }
        runCurrent()
        automation.keyPresses.emit(HotKey.F6)
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
        assertEquals(listOf("Create a routine first."), messages)
    }

    @Test
    fun `given macOS hasn't allowed input when started then it explains and stays idle`() = runTest {
        automation.inputAccess = InputAccess.NeedsPermission
        val controller = controller()
        val messages = mutableListOf<String>()
        backgroundScope.launch { controller.messages.collect { messages += it } }
        runCurrent()
        controller.start(routine)
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
        assertEquals(1, messages.size)
    }

    @Test
    fun `given a platform without input when started then it says so and stays idle`() = runTest {
        automation.inputAccess = InputAccess.Unsupported
        val controller = controller()
        controller.start(routine)
        runCurrent()
        assertEquals(RunState.Idle, controller.state.value)
    }

    @Test
    fun `given the capture key when idle then it records the pointer, and during a run it doesn't`() = runTest {
        val controller = controller()
        val captured = mutableListOf<ScreenPoint>()
        backgroundScope.launch { controller.captures.collect { captured += it } }
        runCurrent()
        automation.position = ScreenPoint(42, 24)
        automation.keyPresses.emit(HotKey.F7)
        runCurrent()
        assertEquals(listOf(ScreenPoint(42, 24)), captured)

        controller.start(routine)
        runCurrent()
        automation.keyPresses.emit(HotKey.F7)
        runCurrent()
        assertEquals(1, captured.size)
        controller.stop()
    }
}
