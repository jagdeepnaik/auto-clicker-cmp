package com.jagdeep.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import com.jagdeep.ui.util.MessageText

interface SnackbarManager {
    suspend fun showMessage(message: String)
}

class MaterialSnackbarManager(
    private val snackbarHostState: SnackbarHostState,
) : SnackbarManager {
    override suspend fun showMessage(message: String) {
        if (message.isBlank()) return
        val cleanMessage = MessageText.clean(message)
        snackbarHostState.showSnackbar(
            message = cleanMessage,
            duration = SnackbarDuration.Short,
        )
    }
}

private object NoOpSnackbarManager : SnackbarManager {
    override suspend fun showMessage(message: String) = Unit
}

val LocalSnackbarManager = staticCompositionLocalOf<SnackbarManager> {
    NoOpSnackbarManager
}
