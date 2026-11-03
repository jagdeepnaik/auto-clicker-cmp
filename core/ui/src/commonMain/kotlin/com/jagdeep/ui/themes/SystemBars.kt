package com.jagdeep.ui.themes

import androidx.compose.runtime.Composable

/**
 * Matches the OS status and navigation bar icons to the app's light/dark choice, which may differ
 * from the system setting. A no-op where the app draws no system bars.
 */
@Composable
internal expect fun SystemBarsAppearance(darkTheme: Boolean)
