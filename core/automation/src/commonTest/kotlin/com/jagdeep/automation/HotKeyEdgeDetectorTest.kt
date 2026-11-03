package com.jagdeep.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotKeyEdgeDetectorTest {
    @Test
    fun `given a key already held when watching starts then it does not count as a press`() {
        val detector = HotKeyEdgeDetector()
        assertEquals(emptySet(), detector.update(setOf(HotKey.F6)))
        assertEquals(emptySet(), detector.update(setOf(HotKey.F6)))
    }

    @Test
    fun `given a key held across samples when sampled then it fires once until released`() {
        val detector = HotKeyEdgeDetector()
        detector.update(emptySet())
        assertEquals(setOf(HotKey.F6), detector.update(setOf(HotKey.F6)))
        assertEquals(emptySet(), detector.update(setOf(HotKey.F6)))
        assertEquals(emptySet(), detector.update(emptySet()))
        assertEquals(setOf(HotKey.F6), detector.update(setOf(HotKey.F6)))
    }

    @Test
    fun `given two keys pressed together when sampled then both fire`() {
        val detector = HotKeyEdgeDetector()
        detector.update(emptySet())
        assertEquals(setOf(HotKey.F6, HotKey.F7), detector.update(setOf(HotKey.F6, HotKey.F7)))
    }

    @Test
    fun `given display names that differ only by case or invisible marks then they are the same app`() {
        assertTrue(isSameApp("\u200EWhatsApp", "whatsapp "))
        assertFalse(isSameApp("Google Chrome", "Chrome"))
    }

    @Test
    fun `given the unsupported platform when used then nothing happens and it says so`() {
        val automation = UnsupportedDesktopAutomation
        assertEquals(InputAccess.Unsupported, automation.access.access())
        assertFalse(automation.pointer.click(ScreenPoint(1, 1), PointerButton.Left, 1))
        assertEquals(null, automation.pointer.position())
    }
}
