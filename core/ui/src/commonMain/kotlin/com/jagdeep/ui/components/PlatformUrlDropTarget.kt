package com.jagdeep.ui.components

import androidx.compose.runtime.Composable

/** Receives a URL dragged in from another application when the platform supports it. */
@Composable
expect fun PlatformUrlDropTarget(
    onUrlDropped: (String) -> Unit,
    content: @Composable () -> Unit,
)
