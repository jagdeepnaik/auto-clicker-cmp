package com.jagdeep.automation

import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Robot
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sends real input, so it moves the pointer and only runs when asked for:
 *
 *   AUTOCLICKER_LIVE_INPUT_TEST=1 ./gradlew :core:automation:jvmTest --tests '*LiveInputTest*' --rerun
 *
 * It clicks only inside its own always-on-top window, and refuses to continue if that window isn't
 * focused. Keep your hands off the mouse and keyboard while it runs.
 */
class LiveInputTest {
    private val enabled = System.getenv("AUTOCLICKER_LIVE_INPUT_TEST") == "1"

    @Test
    fun `given a real window when the device clicks it then it receives those clicks and the hot key is seen`() {
        if (!enabled) return
        val automation = createDesktopAutomation()
        check(automation.access.access() == InputAccess.Granted) { "This process isn't allowed to send input." }
        val received = CopyOnWriteArrayList<Pair<Int, Int>>()
        var frame: JFrame? = null
        EventQueue.invokeAndWait {
            frame = JFrame("AutoClicker live input test").apply {
                isAlwaysOnTop = true
                preferredSize = Dimension(400, 300)
                pack()
                setLocationRelativeTo(null)
                contentPane.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        received += e.button to e.clickCount
                    }
                })
                isVisible = true
                toFront()
                requestFocus()
            }
        }
        val window = frame!!
        val original = automation.pointer.position()
        val scope = CoroutineScope(Dispatchers.Default)
        val keys = CopyOnWriteArrayList<HotKey>()
        scope.launch { automation.hotKeys.presses.collect { keys += it } }
        try {
            val robot = Robot()
            robot.delay(1_500)
            val onScreen = window.contentPane.locationOnScreen
            val target = ScreenPoint(onScreen.x + 200, onScreen.y + 150)

            // Bringing it forward can need a real click on some setups; this one lands in our window.
            assertTrue(automation.pointer.click(target, PointerButton.Left, 1))
            robot.delay(500)
            check(window.isActive) { "The test window isn't focused; stopping before sending anything else." }
            assertEquals(target, automation.pointer.position())
            assertTrue(automation.pointer.isOverOwnFocusedWindow(target))
            assertEquals(MouseEvent.BUTTON1 to 1, received.first())

            received.clear()
            automation.pointer.click(target, PointerButton.Right, 1)
            robot.delay(400)
            assertEquals(MouseEvent.BUTTON3, received.single().first)

            received.clear()
            robot.delay(600)
            automation.pointer.click(target, PointerButton.Left, 2)
            robot.delay(400)
            assertEquals(listOf(1, 2), received.map { it.second })

            // Over a focused window of ours, so the key goes nowhere else.
            robot.keyPress(KeyEvent.VK_F6)
            robot.delay(200)
            robot.keyRelease(KeyEvent.VK_F6)
            robot.delay(200)
            assertEquals(listOf(HotKey.F6), keys.toList())
        } finally {
            scope.cancel()
            automation.close()
            EventQueue.invokeAndWait { window.dispose() }
            original?.let { Robot().mouseMove(it.x, it.y) }
        }
    }

    @Test
    fun `given the pointer moved by hand when clicking in place then it stays where it is`() {
        if (!enabled) return
        val automation = createDesktopAutomation()
        check(automation.access.access() == InputAccess.Granted) { "This process isn't allowed to send input." }
        val received = CopyOnWriteArrayList<java.awt.Point>()
        var frame: JFrame? = null
        EventQueue.invokeAndWait {
            frame = JFrame("AutoClicker live input test").apply {
                isAlwaysOnTop = true
                preferredSize = Dimension(500, 400)
                pack()
                setLocationRelativeTo(null)
                contentPane.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        received += e.locationOnScreen
                    }
                })
                isVisible = true
                toFront()
            }
        }
        val window = frame!!
        val original = automation.pointer.position()
        try {
            // A second Robot stands in for the user's hand: the device's own Robot doesn't see this move.
            val hand = Robot()
            hand.delay(1_500)
            val origin = window.contentPane.locationOnScreen
            val first = ScreenPoint(origin.x + 100, origin.y + 100)
            val second = ScreenPoint(origin.x + 350, origin.y + 250)

            hand.mouseMove(first.x, first.y)
            hand.delay(300)
            assertTrue(automation.pointer.click(null, PointerButton.Left, 1))
            hand.delay(400)

            hand.mouseMove(second.x, second.y)
            hand.delay(300)
            assertTrue(automation.pointer.click(null, PointerButton.Left, 1))
            hand.delay(400)

            assertEquals(second, automation.pointer.position(), "The click moved the pointer")
            assertEquals(java.awt.Point(second.x, second.y), received.last(), "The click landed somewhere else")
        } finally {
            automation.close()
            EventQueue.invokeAndWait { window.dispose() }
            original?.let { Robot().mouseMove(it.x, it.y) }
        }
    }
}
