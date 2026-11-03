package com.jagdeep.home.domain

import com.jagdeep.automation.PointerButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineDraftTest {
    private val valid = RoutineDraft(name = "Refresh")

    @Test
    fun `given a complete routine when converted to a draft and back then it is unchanged`() {
        val routine = ClickRoutine(
            id = "r",
            name = "Refresh",
            target = ClickTarget.AtPoints(listOf(ClickPoint(-20, 400), ClickPoint(5, 6))),
            button = PointerButton.Right,
            clicksPerAction = 2,
            intervalMillis = 120_000,
            randomExtraMillis = 250,
            limit = RunLimit.Duration(600_000),
            onlyInApp = "Google Chrome",
            startDelaySeconds = 7,
        )
        assertEquals(routine, routine.toDraft().toRoutine("r"))
    }

    @Test
    fun `given an interval when shown then it uses the largest unit that keeps it whole`() {
        assertEquals("2" to IntervalUnit.Minutes, ClickRoutine("r", "n", intervalMillis = 120_000).toDraft().let { it.interval to it.intervalUnit })
        assertEquals("90" to IntervalUnit.Seconds, ClickRoutine("r", "n", intervalMillis = 90_000).toDraft().let { it.interval to it.intervalUnit })
        assertEquals("1500" to IntervalUnit.Milliseconds, ClickRoutine("r", "n", intervalMillis = 1_500).toDraft().let { it.interval to it.intervalUnit })
    }

    @Test
    fun `given valid values when validated then there are no errors`() {
        assertFalse(valid.validate().hasAny)
    }

    @Test
    fun `given a blank or overlong name when validated then the name is flagged`() {
        assertNotNull(valid.copy(name = "  ").validate().name)
        assertNotNull(valid.copy(name = "a".repeat(61)).validate().name)
    }

    @Test
    fun `given an interval below the floor or not a number when validated then it is flagged`() {
        assertNotNull(valid.copy(interval = "9", intervalUnit = IntervalUnit.Milliseconds).validate().interval)
        assertNull(valid.copy(interval = "10", intervalUnit = IntervalUnit.Milliseconds).validate().interval)
        assertNotNull(valid.copy(interval = "fast").validate().interval)
        assertNotNull(valid.copy(interval = "1441", intervalUnit = IntervalUnit.Minutes).validate().interval)
    }

    @Test
    fun `given specific points when none are added or one is incomplete then points are flagged`() {
        assertNotNull(valid.copy(targetMode = TargetMode.AtPoints).validate().points)
        assertNotNull(valid.copy(targetMode = TargetMode.AtPoints, points = listOf(PointDraft("1", ""))).validate().points)
        assertNull(valid.copy(targetMode = TargetMode.AtPoints, points = listOf(PointDraft("-1", " 2 "))).validate().points)
    }

    @Test
    fun `given leftover points when the pointer target is chosen then they don't block saving or get saved`() {
        val draft = valid.copy(targetMode = TargetMode.AtPointer, points = listOf(PointDraft("", "")))
        assertFalse(draft.validate().hasAny)
        assertEquals(ClickTarget.AtPointer, draft.toRoutine("r").target)
    }

    @Test
    fun `given a limit when out of range then only the active limit is checked`() {
        assertNotNull(valid.copy(limitMode = LimitMode.Clicks, limitClicks = "0").validate().limit)
        assertNotNull(valid.copy(limitMode = LimitMode.Minutes, limitMinutes = "x").validate().limit)
        assertNull(valid.copy(limitMode = LimitMode.UntilStopped, limitClicks = "0").validate().limit)
    }

    @Test
    fun `given a negative extra or a long start delay when validated then they are flagged`() {
        assertNotNull(valid.copy(randomExtraMillis = "-1").validate().randomExtra)
        assertNotNull(valid.copy(startDelaySeconds = "61").validate().startDelay)
        assertTrue(valid.copy(startDelaySeconds = "0").validate().startDelay == null)
    }
}
