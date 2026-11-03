package com.jagdeep.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

/**
 * Platform scrollbar for a [LazyListState]. Desktop provides a draggable thumb;
 * touch platforms retain their native scrolling behavior.
 */
@Composable
expect fun BoxScope.PlatformLazyListScrollbar(state: LazyListState)
