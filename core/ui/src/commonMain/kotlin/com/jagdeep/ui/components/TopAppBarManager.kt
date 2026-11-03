package com.jagdeep.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlinx.coroutines.flow.MutableStateFlow

data class TopAppBarState(
    val ownerRoute: RouteTopLevel? = null,
    val modifier: Modifier = Modifier,
    val content: @Composable () -> Unit = {},
)

val LocalTopAppBarManager = staticCompositionLocalOf {
    MutableStateFlow(TopAppBarState())
}
