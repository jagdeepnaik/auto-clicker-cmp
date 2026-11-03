package com.jagdeep.shared.history

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** How a run ended. */
@Serializable
enum class RunOutcome(val label: String) {
    Completed("Finished"),
    StoppedByUser("Stopped by you"),
    PointerMoved("Stopped because you moved the pointer"),
    ScreenCorner("Stopped at a screen corner"),
    AccessLost("Stopped because input permission was turned off"),
    Failed("Stopped because a click couldn't be sent"),
    PageUnchanged("Stopped because the click didn't change the page"),
    BrowserDisconnected("Stopped because Chrome closed or the helper was turned off"),
}

/** One finished run: written by Home and Page helper, shown by Activity. Holds no screen contents. */
@Serializable
data class RunRecord(
    val id: String,
    val routineName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val clicks: Long,
    val outcome: RunOutcome,
)

interface RunHistoryStore {
    /** Newest first. */
    val records: Flow<List<RunRecord>>

    suspend fun add(record: RunRecord)

    suspend fun clear()
}
