package com.jagdeep.shared.preferences

import com.jagdeep.automation.HotKey

/** Non-sensitive app preferences intentionally shared by feature modules. */
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorPalette: ColorPalette = DEFAULT_COLOR_PALETTE,
    /** Starts or stops the selected routine from any app. */
    val toggleHotKey: HotKey = HotKey.F6,
    /** Records the pointer's position as a click point while a routine is being edited. */
    val captureHotKey: HotKey = HotKey.F7,
    /** Page helper: clicks the highlighted button in Chrome. */
    val pageClickHotKey: HotKey = HotKey.F8,
    /** Page helper: starts or stops auto-scroll in Chrome. */
    val pageScrollHotKey: HotKey = HotKey.F9,
    /** Stops a routine with fixed points as soon as the user moves the pointer by hand. */
    val stopWhenPointerMoves: Boolean = true,
    /** Stops any routine when the pointer reaches a corner of the screen. */
    val stopAtScreenCorner: Boolean = true,
    /** Stops the display sleeping while a routine, auto-scroll or a scroll and click run is going. */
    val keepDisplayOn: Boolean = false,
    /** Shows a live log of what the app is doing in Activity, in memory only. */
    val developerMode: Boolean = false,
)

/**
 * The palette used until the user picks one in Settings. Change it here, and only here. A palette
 * the user has already chosen is saved on the device and always wins over this default.
 */
val DEFAULT_COLOR_PALETTE = ColorPalette.Default

/** App colour palette; mirrors `Palette` in core:ui, which feature:shared can't depend on. */
enum class ColorPalette(val label: String) {
    Default("Default"),
    Lagoon("Lagoon"),
    Graphite("Graphite"),
    Ocean("Ocean"),
    Classic("Classic blue"),
    Forest("Forest"),
    Sunset("Sunset"),
    Midnight("Midnight"),
    Aurora("Aurora"),
    Rose("Rose"),
    Desert("Desert"),
    Neon("Neon"),
    Mocha("Mocha"),
    Candy("Candy"),
    Royal("Royal"),
    Saffron("Saffron"),
    Obsidian("Obsidian"),
    Ember("Ember"),
    Abyss("Abyss"),
}

/** App appearance; System follows the device's light/dark setting. */
enum class ThemeMode(val label: String) { System("System default"), Light("Light"), Dark("Dark") }
