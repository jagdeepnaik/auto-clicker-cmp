package com.jagdeep.home.domain

import com.jagdeep.automation.PointerButton
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.automation.InputAccess
import com.jagdeep.shared.history.RunOutcome
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ClickRunTest {
    private val automation = FakeAutomation()

    private fun TestScope.clickRun(random: Random = Random(1)) =
        ClickRun(automation.pointer, automation.apps, automation.access, { currentTime }, random)

    private val everySecond = ClickRoutine("r", "Routine", intervalMillis = 1_000)

    @Test
    fun `given a click limit when run then it clicks exactly that many times and finishes`() = runTest {
        val outcome = clickRun().execute(everySecond.copy(limit = RunLimit.Clicks(3)), RunSafety()) { _, _ -> }
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(3, automation.clicks.size)
    }

    @Test
    fun `given a click limit when run then no time is spent waiting after the last click`() = runTest {
        clickRun().execute(everySecond.copy(limit = RunLimit.Clicks(3)), RunSafety()) { _, _ -> }
        assertEquals(2_000, currentTime)
    }

    @Test
    fun `given a duration limit when the time passes during a wait then the run ends on time`() = runTest {
        val outcome = clickRun().execute(everySecond.copy(intervalMillis = 60_000, limit = RunLimit.Duration(1_500)), RunSafety()) { _, _ -> }
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(1, automation.clicks.size)
        assertTrue(currentTime in 1_500..1_550)
    }

    @Test
    fun `given points with their own waits and buttons when run once then each waits after the click before it`() = runTest {
        val routine = everySecond.copy(
            target = ClickTarget.AtPoints(
                listOf(
                    ClickPoint(1, 1, delayMillis = 5_000, button = PointerButton.Right),
                    ClickPoint(2, 2, delayMillis = 200, button = PointerButton.Middle),
                    ClickPoint(3, 3, delayMillis = 3_000),
                ),
            ),
            limit = RunLimit.Passes(1),
        )
        val clickedAt = mutableListOf<Long>()
        val outcome = clickRun().execute(routine, RunSafety(stopWhenPointerMoves = false)) { _, _ -> clickedAt += currentTime }
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(listOf(PointerButton.Right, PointerButton.Middle, PointerButton.Left), automation.clicks.map { it.button })
        assertEquals(listOf(ScreenPoint(1, 1), ScreenPoint(2, 2), ScreenPoint(3, 3)), automation.clicks.map { it.at })
        // The first point clicks at once (after the start delay); then 200 ms, then 3 s.
        assertEquals(listOf(0L, 200L, 3_200L), clickedAt.distinct())
    }

    @Test
    fun `given points repeated for passes when run then the first point's wait follows the last click`() = runTest {
        val routine = everySecond.copy(
            target = ClickTarget.AtPoints(listOf(ClickPoint(1, 1, delayMillis = 5_000), ClickPoint(2, 2, delayMillis = 100))),
            limit = RunLimit.Passes(2),
        )
        clickRun().execute(routine, RunSafety(stopWhenPointerMoves = false)) { _, _ -> }
        assertEquals(4, automation.clicks.size)
        assertEquals(100L + 5_000 + 100, currentTime)
    }

    @Test
    fun `given several points when run then they are clicked in order and repeat`() = runTest {
        val routine = everySecond.copy(target = ClickTarget.AtPoints(listOf(ClickPoint(1, 1), ClickPoint(2, 2))), limit = RunLimit.Clicks(3))
        clickRun().execute(routine, RunSafety()) { _, _ -> }
        assertEquals(listOf(ScreenPoint(1, 1), ScreenPoint(2, 2), ScreenPoint(1, 1)), automation.clicks.map { it.at })
    }

    @Test
    fun `given the pointer target when run then it clicks in place without moving the pointer`() = runTest {
        clickRun().execute(everySecond.copy(limit = RunLimit.Clicks(1)), RunSafety()) { _, _ -> }
        assertEquals(null, automation.clicks.single().at)
    }

    @Test
    fun `given a right double click routine when run then that button and count are sent`() = runTest {
        clickRun().execute(everySecond.copy(button = PointerButton.Right, clicksPerAction = 2, limit = RunLimit.Clicks(1)), RunSafety()) { _, _ -> }
        assertEquals(SentClick(null, PointerButton.Right, 2), automation.clicks.single())
    }

    @Test
    fun `given random extra time when run then each wait stays within the allowed range`() = runTest {
        val routine = everySecond.copy(intervalMillis = 100, randomExtraMillis = 50, limit = RunLimit.Clicks(11))
        clickRun().execute(routine, RunSafety()) { _, _ -> }
        assertTrue(currentTime in 1_000..1_500, "ten waits took $currentTime ms")
    }

    @Test
    fun `given fixed points when the user moves the pointer during a long wait then the run stops promptly`() = runTest {
        val routine = everySecond.copy(intervalMillis = 600_000, target = ClickTarget.AtPoints(listOf(ClickPoint(10, 10))))
        var outcome: RunOutcome? = null
        val run = clickRun()
        launch { outcome = run.execute(routine, RunSafety()) { _, _ -> } }
        runCurrent()
        assertEquals(1, automation.clicks.size)

        automation.position = ScreenPoint(300, 300)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(RunOutcome.PointerMoved, outcome)
    }

    @Test
    fun `given a pixel of rounding from display scaling when checking then the run keeps going`() = runTest {
        val routine = everySecond.copy(target = ClickTarget.AtPoints(listOf(ClickPoint(10, 10))), limit = RunLimit.Clicks(2))
        val run = clickRun()
        var outcome: RunOutcome? = null
        launch { outcome = run.execute(routine, RunSafety()) { _, _ -> } }
        runCurrent()
        automation.position = ScreenPoint(11, 9)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(RunOutcome.Completed, outcome)
    }

    @Test
    fun `given the move stop is off when the pointer moves then the run keeps clicking`() = runTest {
        val routine = everySecond.copy(target = ClickTarget.AtPoints(listOf(ClickPoint(10, 10))), limit = RunLimit.Clicks(2))
        val run = clickRun()
        var outcome: RunOutcome? = null
        launch { outcome = run.execute(routine, RunSafety(stopWhenPointerMoves = false)) { _, _ -> } }
        runCurrent()
        automation.position = ScreenPoint(300, 300)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(RunOutcome.Completed, outcome)
    }

    @Test
    fun `given the pointer target when the pointer reaches a screen corner then the run stops`() = runTest {
        automation.corners = setOf(ScreenPoint(0, 0))
        var outcome: RunOutcome? = null
        val run = clickRun()
        launch { outcome = run.execute(everySecond, RunSafety()) { _, _ -> } }
        runCurrent()
        automation.position = ScreenPoint(0, 0)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(RunOutcome.ScreenCorner, outcome)
    }

    @Test
    fun `given the corner stop is off when the pointer is in a corner then clicking continues`() = runTest {
        automation.corners = setOf(automation.position!!)
        val outcome = clickRun().execute(everySecond.copy(limit = RunLimit.Clicks(1)), RunSafety(stopAtScreenCorner = false)) { _, _ -> }
        assertEquals(RunOutcome.Completed, outcome)
    }

    @Test
    fun `given a target point in a corner when clicked there then the run's own placement doesn't stop it`() = runTest {
        automation.corners = setOf(ScreenPoint(0, 0))
        val routine = everySecond.copy(target = ClickTarget.AtPoints(listOf(ClickPoint(0, 0))), limit = RunLimit.Clicks(2))
        assertEquals(RunOutcome.Completed, clickRun().execute(routine, RunSafety()) { _, _ -> })
    }

    @Test
    fun `given another app in front when run then nothing is clicked until the target app returns`() = runTest {
        automation.front = "Finder"
        val routine = everySecond.copy(onlyInApp = "Google Chrome", limit = RunLimit.Clicks(1))
        val pauses = mutableListOf<PauseReason?>()
        val run = clickRun()
        var outcome: RunOutcome? = null
        launch { outcome = run.execute(routine, RunSafety()) { _, pause -> pauses += pause } }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, automation.clicks.size)
        assertEquals(listOf<PauseReason?>(PauseReason.WaitingForApp("Google Chrome")), pauses)

        automation.front = "Google Chrome"
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, automation.clicks.size)
        assertEquals(RunOutcome.Completed, outcome)
    }

    @Test
    fun `given the front app can't be determined when a target app is set then it never clicks blindly`() = runTest {
        automation.front = null
        val routine = everySecond.copy(onlyInApp = "Google Chrome", limit = RunLimit.Duration(1_000))
        assertEquals(RunOutcome.Completed, clickRun().execute(routine, RunSafety()) { _, _ -> })
        assertEquals(0, automation.clicks.size)
    }

    @Test
    fun `given the pointer over this app's own window when run then it waits instead of clicking itself`() = runTest {
        automation.ownWindowCovers = setOf(automation.position!!)
        val outcome = clickRun().execute(everySecond.copy(limit = RunLimit.Duration(1_000)), RunSafety()) { _, _ -> }
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(0, automation.clicks.size)
    }

    @Test
    fun `given a pause with fixed points when the user moves the pointer then that isn't treated as interference`() = runTest {
        val routine = everySecond.copy(target = ClickTarget.AtPoints(listOf(ClickPoint(10, 10))), onlyInApp = "Google Chrome", limit = RunLimit.Clicks(2))
        val run = clickRun()
        var outcome: RunOutcome? = null
        launch { outcome = run.execute(routine, RunSafety()) { _, _ -> } }
        runCurrent()
        assertEquals(1, automation.clicks.size)

        automation.front = "Finder"
        advanceTimeBy(1_001)
        runCurrent()
        automation.position = ScreenPoint(300, 300)
        advanceTimeBy(1_000)
        automation.front = "Google Chrome"
        advanceTimeBy(300)
        runCurrent()
        assertEquals(RunOutcome.Completed, outcome)
    }

    @Test
    fun `given a click that can't be sent when run then the run reports the failure`() = runTest {
        automation.clickWorks = false
        assertEquals(RunOutcome.Failed, clickRun().execute(everySecond, RunSafety()) { _, _ -> })
    }

    @Test
    fun `given permission turned off during a run when checked then the run stops`() = runTest {
        val run = clickRun()
        var outcome: RunOutcome? = null
        launch { outcome = run.execute(everySecond.copy(intervalMillis = 60_000), RunSafety()) { _, _ -> } }
        runCurrent()
        automation.inputAccess = InputAccess.NeedsPermission
        advanceTimeBy(100)
        runCurrent()
        assertEquals(RunOutcome.AccessLost, outcome)
    }
}
