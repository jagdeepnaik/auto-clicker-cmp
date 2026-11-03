package com.jagdeep.ui.themes

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colour palettes. Each lives in its own `Palette<Name>.kt` file; to add one, create
 * that file and add an entry here. [AppTheme] applies the selected palette.
 */
enum class Palette {
    /** Standard, product-neutral palette shared by every project on this template. */
    Default,

    /** The original: brand blue with aqua-tinted neutrals. */
    Classic,

    /** Deep cobalt with teal for live states. */
    Ocean,

    /** Deep emerald with amber for live states. */
    Forest,

    /** Warm coral with teal for live states. */
    Sunset,

    /** Near-monochrome graphite with electric blue for live states. */
    Graphite,

    /** Indigo with cyan for live states. */
    Midnight,

    /** Deep teal with violet for live states. */
    Aurora,

    /** Dusty rose with sage for live states. */
    Rose,

    /** Terracotta with sky blue for live states. */
    Desert,

    /** Cyan-blue with mint for live states. */
    Lagoon,

    /** Vivid magenta with electric lime for live states. */
    Neon,

    /** Coffee brown with caramel for live states. */
    Mocha,

    /** Bubblegum pink with sky blue for live states. */
    Candy,

    /** Deep navy with gold for live states. */
    Royal,

    /** Claude-style warm saffron/terracotta on cream, with sage for live states. */
    Saffron,

    /** Charcoal and slate with emerald for live states (dark). */
    Obsidian,

    /** Deep burgundy with orange for live states (dark). */
    Ember,

    /** Deep ocean navy with aqua for live states (dark). */
    Abyss,
}

internal fun Palette.scheme(dark: Boolean): ColorScheme = when (this) {
    Palette.Default -> if (dark) DefaultDarkColors else DefaultLightColors
    Palette.Classic -> if (dark) ClassicDarkColors else ClassicLightColors
    Palette.Ocean -> if (dark) OceanDarkColors else OceanLightColors
    Palette.Forest -> if (dark) ForestDarkColors else ForestLightColors
    Palette.Sunset -> if (dark) SunsetDarkColors else SunsetLightColors
    Palette.Graphite -> if (dark) GraphiteDarkColors else GraphiteLightColors
    Palette.Midnight -> if (dark) MidnightDarkColors else MidnightLightColors
    Palette.Aurora -> if (dark) AuroraDarkColors else AuroraLightColors
    Palette.Rose -> if (dark) RoseDarkColors else RoseLightColors
    Palette.Desert -> if (dark) DesertDarkColors else DesertLightColors
    Palette.Lagoon -> if (dark) LagoonDarkColors else LagoonLightColors
    Palette.Neon -> if (dark) NeonDarkColors else NeonLightColors
    Palette.Mocha -> if (dark) MochaDarkColors else MochaLightColors
    Palette.Candy -> if (dark) CandyDarkColors else CandyLightColors
    Palette.Royal -> if (dark) RoyalDarkColors else RoyalLightColors
    Palette.Saffron -> if (dark) SaffronDarkColors else SaffronLightColors
    Palette.Obsidian -> if (dark) ObsidianDarkColors else ObsidianLightColors
    Palette.Ember -> if (dark) EmberDarkColors else EmberLightColors
    Palette.Abyss -> if (dark) AbyssDarkColors else AbyssLightColors
}

/**
 * Colour for things that are happening now: transfer progress, Ready devices and status. Classic
 * uses its primary blue (unchanged look); Ocean uses teal so movement stands out from actions.
 */
internal fun Palette.liveColor(scheme: ColorScheme): Color = when (this) {
    // Default and Classic use their primary for live states, so they need no extra hue.
    Palette.Default, Palette.Classic -> scheme.primary
    // Every palette except Classic keeps its tertiary colour for live states.
    else -> scheme.tertiary
}

/** A palette's action and live colours, e.g. for a swatch in a palette picker. */
fun Palette.previewColors(dark: Boolean): Pair<Color, Color> = scheme(dark).let { it.primary to liveColor(it) }

internal val LocalLiveColor = staticCompositionLocalOf { Color.Unspecified }

/** Zynk colours beyond Material's roles. */
object AppColors {
    val live: Color
        @Composable @ReadOnlyComposable
        get() = LocalLiveColor.current.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
}
