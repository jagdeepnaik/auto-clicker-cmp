package com.jagdeep.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches keys by sampling their state rather than installing a system-wide keyboard hook: it
 * sees only the function keys, never what the user types, and needs no native library of its own.
 */
internal class PollingHotKeyMonitor(
    private val isKeyDown: (HotKey) -> Boolean,
    scope: CoroutineScope,
    private val pollMillis: Long = POLL_MILLIS,
) : HotKeyMonitor {
    private val mutablePresses = MutableSharedFlow<HotKey>(extraBufferCapacity = 8)
    override val presses: Flow<HotKey> = mutablePresses.asSharedFlow()

    init {
        scope.launch {
            val detector = HotKeyEdgeDetector()
            while (isActive) {
                val down = HotKey.entries.filterTo(mutableSetOf()) { key -> runCatching { isKeyDown(key) }.getOrDefault(false) }
                detector.update(down).forEach { mutablePresses.tryEmit(it) }
                delay(pollMillis)
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 30L
    }
}
