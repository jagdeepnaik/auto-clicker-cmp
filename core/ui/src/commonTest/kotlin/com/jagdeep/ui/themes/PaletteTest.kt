package com.jagdeep.ui.themes

import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteTest {
    @Test
    fun `given every palette when its schemes are built then each one is distinct in light and dark`() {
        // Guards against a palette entry pointing at another palette's colours by mistake.
        for (dark in listOf(false, true)) {
            val primaries = Palette.entries.map { it.scheme(dark).primary }
            assertEquals(Palette.entries.size, primaries.toSet().size, "Duplicate primary colours (dark=$dark)")
            val backgrounds = Palette.entries.map { it.scheme(dark).background to it.scheme(dark).tertiary }
            assertEquals(Palette.entries.size, backgrounds.toSet().size, "Duplicate background/live pairs (dark=$dark)")
        }
    }
}
