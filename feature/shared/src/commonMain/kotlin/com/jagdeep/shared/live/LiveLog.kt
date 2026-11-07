package com.jagdeep.shared.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a live log entry came from. */
enum class LiveSource(val label: String) {
    Chrome("Chrome"),
    Routine("Routine"),
    Click("Click"),
    Keys("Keys"),
    App("App"),
}

data class LiveEntry(val id: Long, val atMillis: Long, val source: LiveSource, val text: String)

/**
 * Developer mode's live log: what the app is doing, as it happens. Held in memory for this session
 * only, never saved or sent anywhere. Written by the routines, shown by Activity.
 */
interface LiveLog {
    /** Oldest first. */
    val entries: StateFlow<List<LiveEntry>>

    /** False while developer mode is off, when entries are dropped without being built. */
    val isRecording: Boolean

    fun add(source: LiveSource, text: String)

    fun clear()
}

/**
 * Builds the text only while recording, so a busy routine pays almost nothing when developer mode is
 * off. Not inline: the Android app is built for an older JVM than this module.
 */
fun LiveLog.log(source: LiveSource, text: () -> String) {
    if (isRecording) add(source, text())
}

/** For code that runs without the app shell, such as tests. */
object NoLiveLog : LiveLog {
    override val entries: StateFlow<List<LiveEntry>> = MutableStateFlow<List<LiveEntry>>(emptyList()).asStateFlow()
    override val isRecording = false
    override fun add(source: LiveSource, text: String) = Unit
    override fun clear() = Unit
}
