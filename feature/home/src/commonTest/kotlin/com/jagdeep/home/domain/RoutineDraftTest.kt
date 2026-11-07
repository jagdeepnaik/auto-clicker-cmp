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
            target = ClickTarget.AtPoints(
                listOf(ClickPoint(-20, 400, 750, PointerButton.Middle, "Play"), ClickPoint(5, 6, 120_000, PointerButton.Right, "Point 2")),
            ),
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
    fun `given points saved before they had their own wait and button when edited then they show the routine's`() {
        val routine = ClickRoutine("r", "Old", target = ClickTarget.AtPoints(listOf(ClickPoint(1, 2))), button = PointerButton.Right, intervalMillis = 2_000)
        assertEquals(listOf(PointDraft("1", "2", "2", IntervalUnit.Seconds, PointerButton.Right, "Point 1")), routine.toDraft().points)
    }

    @Test
    fun `given a step's wait in a unit when read then it's in milliseconds, decimals included`() {
        assertEquals(1_500L, PointDraft(delay = "1.5", delayUnit = IntervalUnit.Seconds).delayMillis)
        assertEquals(120_000L, PointDraft(delay = "2", delayUnit = IntervalUnit.Minutes).delayMillis)
        assertEquals(250L, PointDraft(delay = "250", delayUnit = IntervalUnit.Milliseconds).delayMillis)
        assertEquals(null, PointDraft(delay = "soon").delayMillis)
        assertEquals("1.5" to IntervalUnit.Seconds, delayDraft(1_500))
        assertEquals("2" to IntervalUnit.Minutes, delayDraft(120_000))
        assertEquals("250" to IntervalUnit.Milliseconds, delayDraft(250))
    }

    @Test
    fun `given steps with blank or long names when saved then blanks get their default and long ones are refused`() {
        val draft = valid.copy(targetMode = TargetMode.AtPoints, points = listOf(PointDraft("1", "2", name = " "), PointDraft("3", "4", name = "Play button")))
        val saved = (draft.toRoutine("r") as ClickRoutine).target as ClickTarget.AtPoints
        assertEquals(listOf("Point 1", "Play button"), saved.points.map { it.name })
        assertTrue(draft.copy(points = listOf(PointDraft("1", "2", name = "x".repeat(41)))).validate().points != null)
    }

    @Test
    fun `given passes when checked then they need specific points and a count in range`() {
        val points = valid.copy(targetMode = TargetMode.AtPoints, points = listOf(PointDraft("1", "2")), limitMode = LimitMode.Passes)
        assertFalse(points.validate().hasAny)
        assertEquals(RunLimit.Passes(1), (points.toRoutine("r") as ClickRoutine).limit)
        assertTrue(points.copy(limitPasses = "0").validate().limit != null)
        assertTrue(points.copy(targetMode = TargetMode.AtPointer).validate().limit != null)
        assertTrue(points.copy(points = listOf(PointDraft("1", "2", delay = "5", delayUnit = IntervalUnit.Milliseconds))).validate().points != null, "Wait under 10 ms")
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
        assertEquals(ClickTarget.AtPointer, (draft.toRoutine("r") as ClickRoutine).target)
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

    @Test
    fun `given a scroll and click draft when checked then it needs a spot and rounds in range`() {
        val draft = RoutineDraft(kind = RoutineKind.ScrollClick, name = "Pages")
        assertEquals("Add at least one step to click at the bottom.", draft.validate().points)
        val picked = draft.copy(points = listOf(PointDraft("640", "880")), rounds = "0")
        assertTrue(picked.validate().rounds != null)
        assertFalse(picked.copy(rounds = "5").validate().hasAny)
    }

    @Test
    fun `given a scroll and click saved with one spot when edited then it becomes one step that clicks at once`() {
        val old = ScrollClickRoutine("s", "Pages", ClickPoint(640, 880))
        assertEquals(listOf(PointDraft("640", "880", "0", IntervalUnit.Milliseconds, PointerButton.Left, "Point 1")), old.toDraft().points)
        val saved = old.toDraft().toRoutine("s") as ScrollClickRoutine
        assertEquals(listOf(ClickPoint(640, 880, 0, PointerButton.Left, "Point 1")), saved.steps)
    }

    @Test
    fun `given a scroll and click button step when checked and saved then it needs text and keeps it`() {
        val draft = RoutineDraft(kind = RoutineKind.ScrollClick, name = "Lessons", points = listOf(PointDraft(byText = true, text = " ", name = "Back")))
        assertEquals("Each button step needs the button's text.", draft.validate().points)
        val ok = draft.copy(points = listOf(PointDraft(delay = "0", delayUnit = IntervalUnit.Milliseconds, byText = true, text = " Back to Course ", name = "Back")))
        assertFalse(ok.validate().hasAny)
        val saved = ok.toRoutine("s") as ScrollClickRoutine
        assertEquals("Back to Course", saved.steps.single().text)
        assertEquals(ok.points, saved.toDraft().points.map { it.copy(text = it.text) }.let { listOf(it.single().copy(text = " Back to Course ")) })
    }

    @Test
    fun `given a course helper when edited and saved then it keeps its sound choice`() {
        val routine = CourseHelperRoutine("k", "Yoga course", soundOnVideoEnd = false)
        assertEquals(routine, routine.toDraft().toRoutine("k"))
        assertFalse(routine.needsInputAccess(), "It clicks inside Chrome, not through the pointer")
    }

    @Test
    fun `given a page helper draft with no words when checked then it says so`() {
        val draft = RoutineDraft(kind = RoutineKind.PageHelper, name = "Course", words = " , ")
        assertEquals("Add at least one word.", draft.validate().words)
    }

    @Test
    fun `given Chrome routines when turned into drafts and back then nothing is lost`() {
        val scroll = ScrollClickRoutine(
            "s", "Pages", rounds = 7, pixelsPerSecond = 120, watchVideos = false,
            points = listOf(ClickPoint(640, 880, 0, PointerButton.Left, "Next"), ClickPoint(10, 20, 1_500, PointerButton.Right, "Point 2")),
        )
        val helper = PageHelperRoutine("h", "Course", words = listOf("Next", "Weiter"), pixelsPerSecond = 30, soundOnVideoEnd = false)
        assertEquals(scroll, scroll.toDraft().toRoutine("s"))
        assertEquals(helper, helper.toDraft().toRoutine("h"))
    }
}
