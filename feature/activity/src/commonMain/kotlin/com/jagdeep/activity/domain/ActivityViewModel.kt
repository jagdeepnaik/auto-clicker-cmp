package com.jagdeep.activity.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActivityUiState(
    val isLoading: Boolean = true,
    val records: List<RunRecord> = emptyList(),
    val isConfirmingClear: Boolean = false,
    val nowMillis: Long = 0,
)

class ActivityViewModel(
    private val history: RunHistoryStore,
    private val now: () -> Long,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ActivityUiState(nowMillis = now()))
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            history.records.collect { records ->
                mutableState.update { it.copy(isLoading = false, records = records, nowMillis = now()) }
            }
        }
    }

    fun requestClear() = mutableState.update { it.copy(isConfirmingClear = true) }

    fun cancelClear() = mutableState.update { it.copy(isConfirmingClear = false) }

    fun confirmClear() {
        mutableState.update { it.copy(isConfirmingClear = false) }
        viewModelScope.launch { history.clear() }
    }
}
