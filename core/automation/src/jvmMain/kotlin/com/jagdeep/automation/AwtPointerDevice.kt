package com.jagdeep.automation

import com.jagdeep.logger.Log
import java.awt.AWTException
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import kotlin.math.abs

/**
 * Sends input through AWT's Robot, which works on macOS and Windows and needs no extra native
 * code. Short holds matter: pages and games often ignore a press and release in the same instant.
 */
internal class AwtPointerDevice : PointerDevice {
    private val robot: Robot? by lazy {
        try {
            Robot().apply { isAutoWaitForIdle = false; autoDelay = 0 }
        } catch (e: AWTException) {
            Log.w("Input unavailable: ${e.message}", TAG)
            null
        } catch (e: SecurityException) {
            Log.w("Input unavailable: ${e.message}", TAG)
            null
        }
    }

    override fun position(): ScreenPoint? = runCatching {
        MouseInfo.getPointerInfo()?.location?.let { ScreenPoint(it.x, it.y) }
    }.getOrNull()

    override fun click(at: ScreenPoint?, button: PointerButton, count: Int): Boolean {
        val robot = robot ?: return false
        return runCatching {
            if (at != null) {
                robot.mouseMove(at.x, at.y)
                Thread.sleep(MOVE_SETTLE_MILLIS)
            } else {
                // On macOS, Robot presses at the last place it moved to, dragging the pointer back
                // there. Telling it where the pointer is now keeps the click in place.
                position()?.let { robot.mouseMove(it.x, it.y) }
            }
            val mask = button.toInputMask()
            repeat(count) { index ->
                if (index > 0) Thread.sleep(BETWEEN_CLICKS_MILLIS)
                robot.mousePress(mask)
                Thread.sleep(PRESS_HOLD_MILLIS)
                robot.mouseRelease(mask)
            }
        }.onFailure { Log.w("Click failed: ${it.message}", TAG) }.isSuccess
    }

    override fun isScreenCorner(point: ScreenPoint): Boolean = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { screen ->
            val bounds = screen.defaultConfiguration.bounds
            val nearLeftOrRight = abs(point.x - bounds.x) <= CORNER_SIZE || abs(point.x - (bounds.x + bounds.width - 1)) <= CORNER_SIZE
            val nearTopOrBottom = abs(point.y - bounds.y) <= CORNER_SIZE || abs(point.y - (bounds.y + bounds.height - 1)) <= CORNER_SIZE
            nearLeftOrRight && nearTopOrBottom
        }
    }.getOrDefault(false)

    override fun isOverOwnFocusedWindow(point: ScreenPoint): Boolean = runCatching {
        Window.getWindows().any { window ->
            val minimized = (window as? Frame)?.let { it.extendedState and Frame.ICONIFIED != 0 } ?: false
            window.isShowing && window.isActive && !minimized && window.bounds.contains(point.x, point.y)
        }
    }.getOrDefault(false)

    private fun PointerButton.toInputMask(): Int = when (this) {
        PointerButton.Left -> InputEvent.BUTTON1_DOWN_MASK
        PointerButton.Middle -> InputEvent.BUTTON2_DOWN_MASK
        PointerButton.Right -> InputEvent.BUTTON3_DOWN_MASK
    }

    private companion object {
        const val TAG = "AwtPointerDevice"
        const val MOVE_SETTLE_MILLIS = 5L
        const val PRESS_HOLD_MILLIS = 5L
        const val BETWEEN_CLICKS_MILLIS = 30L
        const val CORNER_SIZE = 4
    }
}
