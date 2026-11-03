package com.jagdeep.ui.themes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme.shapes
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** The app passes its chosen palette; [Palette.Default] is the template-wide standard. */
    palette: Palette = Palette.Default,
    content: @Composable () -> Unit
) {
    // Zynk keeps one recognisable accent across platforms; dynamic colour would
    // make the transfer affordances and security states inconsistent.
    val appColorScheme = palette.scheme(darkTheme)
    SystemBarsAppearance(darkTheme)

//    MaterialTheme(
//        colorScheme = appColorScheme,
//        shapes = shapes,
//        typography = typography,
//        content = content
//    )

    CompositionLocalProvider(LocalLiveColor provides palette.liveColor(appColorScheme)) {
        MaterialExpressiveTheme(
            colorScheme = appColorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = shapes,
            typography = typography,
            content = content
        )
    }
}
