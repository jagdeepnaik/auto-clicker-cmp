package com.jagdeep.autoclicker

import com.jagdeep.shared.preferences.ColorPalette
import com.jagdeep.ui.themes.Palette
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorPaletteMappingTest {
    @Test
    fun `given every saved palette choice when applied then a theme palette of the same name exists`() {
        // App maps the preference to the theme by name; a missing entry would crash at launch.
        assertEquals(ColorPalette.entries.map { it.name }.toSet(), Palette.entries.map { it.name }.toSet())
    }
}
