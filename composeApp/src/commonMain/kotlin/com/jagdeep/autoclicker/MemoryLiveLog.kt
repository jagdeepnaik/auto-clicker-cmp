package com.jagdeep.autoclicker

import com.jagdeep.shared.live.LiveEntry
import com.jagdeep.shared.live.LiveLog
import com.jagdeep.shared.live.LiveSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Developer mode's log, in memory only: it keeps the newest [capacity] entries and records only while
 * [recording]. Safe to write from any thread, because each write is one atomic update.
 */
internal class MemoryLiveLog(
    private val now: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
) : LiveLog {
    private val mutableEntries = MutableStateFlow<List<LiveEntry>>(emptyList())
    override val entries: StateFlow<List<LiveEntry>> = mutableEntries.asStateFlow()

    var recording: Boolean = false

    override val isRecording: Boolean get() = recording

    override fun add(source: LiveSource, text: String) {
        if (!recording) return
        val at = now()
        mutableEntries.update { list ->
            val id = (list.lastOrNull()?.id ?: 0) + 1
            (list + LiveEntry(id, at, source, text)).takeLast(capacity)
        }
    }

    override fun clear() {
        mutableEntries.value = emptyList()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
