package com.jagdeep.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jagdeep.ui.util.MessageText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.milliseconds

interface ToastManager {
    suspend fun show(message: String)
}

data class ToastMessage(val id: Long, val message: String)

class ToastHostState : ToastManager {
    private val _messages = MutableStateFlow<List<ToastMessage>>(emptyList())
    private var nextId = 0L

    val messages: StateFlow<List<ToastMessage>> = _messages

    override suspend fun show(message: String) {
        if (message.isBlank()) return
        val cleanMessage = MessageText.clean(message)
        val toast = ToastMessage(
            id = nextId++,
            message = cleanMessage,
        )
        _messages.update { messages -> messages + toast }
    }

    fun dismiss(id: Long) {
        _messages.update { messages -> messages.filterNot { it.id == id } }
    }
}

private object NoOpToastManager : ToastManager {
    override suspend fun show(message: String) = Unit
}

val LocalToastManager = staticCompositionLocalOf<ToastManager> {
    NoOpToastManager
}

@Composable
fun rememberToastHostState(): ToastHostState = remember { ToastHostState() }

@Composable
fun ToastHost(
    hostState: ToastHostState,
    modifier: Modifier = Modifier,
    durationMillis: Long = 2_000,
) {
    val messages by hostState.messages.collectAsState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            messages.takeLast(3).forEach { toast ->
                LaunchedEffect(toast.id) {
                    delay(durationMillis.milliseconds)
                    hostState.dismiss(toast.id)
                }
                ToastItem(message = toast.message)
            }
        }
    }
}

@Composable
private fun ToastItem(message: String) {
    Surface(
        modifier = Modifier.widthIn(max = 360.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
