package com.jagdeep.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformUrlDropTarget(
    onUrlDropped: (String) -> Unit,
    content: @Composable () -> Unit,
) = content()
