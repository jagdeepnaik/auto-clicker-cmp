package com.jagdeep.autoclicker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.jagdeep.logger.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference

/**
 * Makes the window's title bar follow the app's theme, not just the system's. macOS has no setting
 * for that this Java can reach, so the title bar is made transparent and the app draws its own strip
 * behind the window buttons. Windows takes the colours through DWM. Linux keeps its own title bar.
 */
internal object DesktopTitleBar {
    private val osName = System.getProperty("os.name").orEmpty()
    val isMac = osName.contains("Mac", ignoreCase = true)
    private val isWindows = osName.contains("Windows", ignoreCase = true)

    /** macOS's title bar height, which the app's strip fills. */
    val macTitleBarHeight = 28.dp

    /** Call once, before the window is shown. */
    fun prepare(window: ComposeWindow) {
        if (!isMac) return
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        // The app draws the title itself, in the theme's colours.
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }

    /** Windows 10 (20H1 and later) follows dark mode; Windows 11 also takes the exact colours. */
    fun apply(window: ComposeWindow, dark: Boolean, background: Color, content: Color) {
        if (!isWindows) return
        runCatching {
            val hwnd = HWND(Native.getComponentPointer(window) ?: return)
            setAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
            setAttribute(hwnd, DWMWA_CAPTION_COLOR, background.toColorRef())
            setAttribute(hwnd, DWMWA_TEXT_COLOR, content.toColorRef())
        }.onFailure { Log.w("Title bar colours not applied: ${it.message}", TAG) }
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

/** macOS: the strip behind the window buttons, in the theme's colours. Drag it to move the window. */
@Composable
internal fun WindowScope.MacTitleBar(title: String) {
    WindowDraggableArea(Modifier.fillMaxWidth().height(DesktopTitleBar.macTitleBarHeight)) {
        Box(Modifier.fillMaxWidth().height(DesktopTitleBar.macTitleBarHeight), contentAlignment = Alignment.Center) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
