package com.jagdeep.activity.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.live.LiveEntry
import com.jagdeep.shared.live.LiveLog
import com.jagdeep.shared.live.NoLiveLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** With developer mode on, Activity shows the live log or the list of runs. */
enum class ActivityView { Live, Runs }

data class ActivityUiState(
    val isLoading: Boolean = true,
    val records: List<RunRecord> = emptyList(),
    val isConfirmingClear: Boolean = false,
    val nowMillis: Long = 0,
    val developerMode: Boolean = false,
    val view: ActivityView = ActivityView.Live,
    /** Oldest first. Frozen while [isLivePaused], so a fast log can be read. */
    val liveEntries: List<LiveEntry> = emptyList(),
    val isLivePaused: Boolean = false,
) {
    /** What the screen shows: the live log only in developer mode. */
    val showsLive: Boolean get() = developerMode && view == ActivityView.Live
}

class ActivityViewModel(
    private val history: RunHistoryStore,
    private val now: () -> Long,
    private val live: LiveLog = NoLiveLog,
    developerMode: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val mutableState = MutableStateFlow(ActivityUiState(nowMillis = now()))
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            history.records.collect { records ->
                mutableState.update { it.copy(isLoading = false, records = records, nowMillis = now()) }
            }
        }
        viewModelScope.launch { developerMode.collect { on -> mutableState.update { it.copy(developerMode = on) } } }
        viewModelScope.launch {
            live.entries.collect { entries -> mutableState.update { if (it.isLivePaused) it else it.copy(liveEntries = entries) } }
        }
    }

    fun show(view: ActivityView) = mutableState.update { it.copy(view = view) }

    fun toggleLivePaused() = mutableState.update {
        // Resuming catches up with everything recorded while paused.
        if (it.isLivePaused) it.copy(isLivePaused = false, liveEntries = live.entries.value) else it.copy(isLivePaused = true)
    }

    fun clearLive() {
        live.clear()
        mutableState.update { it.copy(liveEntries = emptyList()) }
    }

    fun requestClear() = mutableState.update { it.copy(isConfirmingClear = true) }

    fun cancelClear() = mutableState.update { it.copy(isConfirmingClear = false) }

    fun confirmClear() {
        mutableState.update { it.copy(isConfirmingClear = false) }
        viewModelScope.launch { history.clear() }
    }
}
