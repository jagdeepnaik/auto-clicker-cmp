package com.jagdeep.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
actual fun BoxScope.PlatformLazyListScrollbar(state: LazyListState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd),
    )
}
