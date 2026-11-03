package com.jagdeep.autoclicker

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.util.prefs.Preferences

/**
 * Keeps desktop-window chrome state outside Room. Java Preferences is backed by the native user
 * preferences store, so this is retained between launches without changing the app database.
 */
internal class DesktopWindowPreferences {
    private val preferences = Preferences.userRoot().node("com/jagdeep/autoclicker/window")

    fun restore(): DesktopWindowSnapshot = DesktopWindowSnapshot(
        width = preferences.getFloat(WIDTH, DEFAULT_WIDTH).validWindowDimension(DEFAULT_WIDTH),
        height = preferences.getFloat(HEIGHT, DEFAULT_HEIGHT).validWindowDimension(DEFAULT_HEIGHT),
        positionX = preferences.getFloat(POSITION_X, 0f).takeIf { preferences.getBoolean(HAS_POSITION, false) },
        positionY = preferences.getFloat(POSITION_Y, 0f).takeIf { preferences.getBoolean(HAS_POSITION, false) },
        placement = preferences.get(PLACEMENT, WindowPlacement.Floating.name)
            .let { saved -> WindowPlacement.entries.firstOrNull { it.name == saved } ?: WindowPlacement.Floating },
    )

    fun save(state: WindowState) = save(DesktopWindowSnapshot.from(state))

    fun save(snapshot: DesktopWindowSnapshot) {
        preferences.putFloat(WIDTH, snapshot.width)
        preferences.putFloat(HEIGHT, snapshot.height)
        preferences.put(PLACEMENT, snapshot.placement.name)
        val positionX = snapshot.positionX
        val positionY = snapshot.positionY
        val hasPosition = positionX != null && positionY != null
        preferences.putBoolean(HAS_POSITION, hasPosition)
        if (hasPosition) {
            preferences.putFloat(POSITION_X, positionX)
            preferences.putFloat(POSITION_Y, positionY)
        }
        // Flush on every state snapshot so an OS shutdown or an explicit close cannot lose it.
        runCatching { preferences.flush() }
    }

    private fun Float.validWindowDimension(fallback: Float): Float =
        takeIf { it.isFinite() && it >= MIN_WINDOW_DIMENSION && it <= MAX_WINDOW_DIMENSION } ?: fallback

    private companion object {
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val POSITION_X = "position_x"
        const val POSITION_Y = "position_y"
        const val HAS_POSITION = "has_position"
        const val PLACEMENT = "placement"
        const val DEFAULT_WIDTH = 1_000f
        const val DEFAULT_HEIGHT = 760f
        const val MIN_WINDOW_DIMENSION = 320f
        const val MAX_WINDOW_DIMENSION = 10_000f
    }
}

internal data class DesktopWindowSnapshot(
    val width: Float,
    val height: Float,
    val positionX: Float?,
    val positionY: Float?,
    val placement: WindowPlacement,
) {
    fun initialSize(): DpSize = DpSize(width.dp, height.dp)

    fun initialPosition(): WindowPosition =
        if (positionX != null && positionY != null) {
            WindowPosition.Absolute(positionX.dp, positionY.dp)
        } else {
            WindowPosition.PlatformDefault
        }

    companion object {
        fun from(state: WindowState): DesktopWindowSnapshot {
            val position = state.position.takeIf { it.isSpecified }
            return DesktopWindowSnapshot(
                width = state.size.width.value,
                height = state.size.height.value,
                positionX = position?.x?.value,
                positionY = position?.y?.value,
                placement = state.placement,
            )
        }
    }
}
