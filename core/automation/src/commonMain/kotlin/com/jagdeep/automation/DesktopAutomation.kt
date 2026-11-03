package com.jagdeep.automation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A position on the desktop, in the coordinates the operating system reports for the pointer. */
data class ScreenPoint(val x: Int, val y: Int)

enum class PointerButton { Left, Right, Middle }

/** Keys that can be watched from any app. Function keys rarely clash with what an app is doing. */
enum class HotKey { F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }

/** Whether this process may send input to other apps. */
enum class InputAccess {
    Granted,

    /** The operating system blocks input until the user allows it (macOS Accessibility). */
    NeedsPermission,

    /** This platform has no way to control other apps. */
    Unsupported,
}

interface PointerDevice {
    /** The pointer's current position, or null when it can't be read. */
    fun position(): ScreenPoint?

    /**
     * Presses and releases [button] [count] times, first moving to [at] when given (otherwise at
     * the pointer's current position). Blocks briefly; call it off the UI thread.
     * Returns false when the input could not be sent.
     */
    fun click(at: ScreenPoint?, button: PointerButton, count: Int): Boolean

    /** True when [point] is in a corner of a display: the conventional "stop everything" gesture. */
    fun isScreenCorner(point: ScreenPoint): Boolean

    /** True when this app's own focused window covers [point], so a click would land on itself. */
    fun isOverOwnFocusedWindow(point: ScreenPoint): Boolean
}

interface InputAccessGate {
    fun access(): InputAccess

    /** Asks the operating system to let the user allow input. Returns false when it couldn't. */
    fun requestAccess(): Boolean
}

interface HotKeyMonitor {
    /** Emits once each time a key goes down, whichever app is in front. */
    val presses: Flow<HotKey>
}

interface ForegroundApps {
    /** The name of the app in front, or null when it can't be determined. */
    fun frontmost(): String?

    /** Names of the apps with a visible window, for choosing a target. */
    fun running(): List<String>
}

/** Everything needed to drive other desktop apps. Close it when the process shuts down. */
interface DesktopAutomation {
    val pointer: PointerDevice
    val access: InputAccessGate
    val hotKeys: HotKeyMonitor
    val apps: ForegroundApps

    /** Plays the system alert sound, heard even while another app is in front. */
    fun playAlert() = Unit

    fun close()
}

/** Whether two app names refer to the same app; tolerant of case and invisible characters. */
fun isSameApp(first: String, second: String): Boolean =
    normalizedAppName(first).equals(normalizedAppName(second), ignoreCase = true)

/** Strips the invisible direction marks some apps carry in their display names. */
fun normalizedAppName(name: String): String =
    name.filterNot { it in '\u200E'..'\u200F' || it in '\u202A'..'\u202E' }.trim()

expect fun createDesktopAutomation(): DesktopAutomation

/** For platforms that can't control other apps; every action reports that it did nothing. */
object UnsupportedDesktopAutomation : DesktopAutomation {
    override val pointer = object : PointerDevice {
        override fun position(): ScreenPoint? = null
        override fun click(at: ScreenPoint?, button: PointerButton, count: Int) = false
        override fun isScreenCorner(point: ScreenPoint) = false
        override fun isOverOwnFocusedWindow(point: ScreenPoint) = false
    }
    override val access = object : InputAccessGate {
        override fun access() = InputAccess.Unsupported
        override fun requestAccess() = false
    }
    override val hotKeys = object : HotKeyMonitor {
        override val presses: Flow<HotKey> = emptyFlow()
    }
    override val apps = object : ForegroundApps {
        override fun frontmost(): String? = null
        override fun running(): List<String> = emptyList()
    }

    override fun close() = Unit
}
