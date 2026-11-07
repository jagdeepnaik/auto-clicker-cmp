package com.jagdeep.autoclicker

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jagdeep.logger.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference

/**
 * Fits the window to the Android Studio-like shell. macOS: as in Android Studio, the app's toolbar
 * shares the title bar's row with the system's own window buttons, so the bar is see-through, the
 * content runs under it, and the window name gives way to the toolbar. Windows: the system title bar
 * stays, in the theme's colours through DWM, with the toolbar below it. Linux keeps its own bar.
 */
internal object DesktopTitleBar {
    private val osName = System.getProperty("os.name").orEmpty()
    val isMac = osName.contains("Mac", ignoreCase = true)
    private val isWindows = osName.contains("Windows", ignoreCase = true)

    /** Room the Mac window buttons take at the start of the toolbar's row. */
    val toolbarStartInset: Dp = if (isMac) 72.dp else 0.dp

    /** Call once, before the window is shown. */
    fun prepare(window: ComposeWindow) {
        if (!isMac) return
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }

    /** Colours the window behind the shell, and the Windows title bar, to match the theme. */
    fun apply(window: ComposeWindow, dark: Boolean, background: Color, content: Color) {
        when {
            isMac -> window.background = java.awt.Color(background.toArgb())
            isWindows -> runCatching {
                val hwnd = HWND(Native.getComponentPointer(window) ?: return)
                setAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
                setAttribute(hwnd, DWMWA_CAPTION_COLOR, background.toColorRef())
                setAttribute(hwnd, DWMWA_TEXT_COLOR, content.toColorRef())
            }.onFailure { Log.w("Title bar colours not applied: ${it.message}", TAG) }
        }
    }

    private fun setAttribute(hwnd: HWND, attribute: Int, value: Int) {
        // Older Windows rejects attributes it doesn't know; that only means the default title bar.
        Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value).pointer, Int.SIZE_BYTES)
    }

    /** COLORREF is 0x00BBGGRR. */
    private fun Color.toColorRef(): Int {
        val argb = toArgb()
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (blue shl 16) or (green shl 8) or red
    }

    @Suppress("FunctionName")
    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int

        companion object {
            val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java)
        }
    }

    private const val TAG = "DesktopTitleBar"
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36
}
