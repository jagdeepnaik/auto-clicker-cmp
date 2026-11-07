package com.jagdeep.autoclicker

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.jagdeep.automation.HotKey
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.shared.preferences.ColorPalette
import com.jagdeep.shared.preferences.ThemeMode
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppWiringTest {
    @Test
    fun `given the app shell when listing destinations then routines come first`() {
        assertEquals(listOf(RouteTopLevel.Home, RouteTopLevel.Activity, RouteTopLevel.Settings), TOP_LEVEL_ROUTES)
    }

    @Test
    fun `given preferences when written and read back then every field survives`() {
        val preferences = AppPreferences(
            themeMode = ThemeMode.Dark,
            colorPalette = ColorPalette.Ocean,
            toggleHotKey = HotKey.F9,
            captureHotKey = HotKey.F10,
            pageClickHotKey = HotKey.F11,
            pageScrollHotKey = HotKey.F12,
            stopWhenPointerMoves = false,
            stopAtScreenCorner = false,
            keepDisplayOn = true,
        )
        val stored = mutablePreferencesOf()
        writeAppPreferences(preferences, stored)
        assertEquals(preferences, readAppPreferences(stored))
    }

    @Test
    fun `given the display setting when something runs then the display stays on, and otherwise it sleeps as usual`() {
        assertTrue(shouldKeepDisplayAwake(enabled = true, routineActive = true, pageHelperActive = false))
        assertTrue(shouldKeepDisplayAwake(enabled = true, routineActive = false, pageHelperActive = true))
        assertFalse(shouldKeepDisplayAwake(enabled = true, routineActive = false, pageHelperActive = false), "Idle")
        assertFalse(shouldKeepDisplayAwake(enabled = false, routineActive = true, pageHelperActive = true), "Setting off")
    }

    @Test
    fun `given nothing saved when read then the defaults apply`() {
        assertEquals(AppPreferences(), readAppPreferences(mutablePreferencesOf()))
    }
}
