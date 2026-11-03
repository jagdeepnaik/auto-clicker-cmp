package com.jagdeep.home.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.shared.history.RunRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val routines: List<ClickRoutine> = emptyList(),
    val selected: ClickRoutine? = null,
    val run: RunState = RunState.Idle,
    val lastRun: RunRecord? = null,
    val access: InputAccess = InputAccess.Granted,
    val toggleHotKey: HotKey = HotKey.F6,
    val routinePendingDeletion: ClickRoutine? = null,
)

class HomeViewModel(
    private val controller: ClickController,
    private val repository: RoutineRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(access = controller.access()))
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.library.collect { library ->
                mutableState.update {
                    it.copy(isLoading = library == null, routines = library?.routines.orEmpty(), selected = library?.selected)
                }
            }
        }
        viewModelScope.launch { controller.state.collect { run -> mutableState.update { it.copy(run = run) } } }
        viewModelScope.launch { controller.lastRun.collect { last -> mutableState.update { it.copy(lastRun = last) } } }
        viewModelScope.launch { controller.preferences.collect { prefs -> mutableState.update { it.copy(toggleHotKey = prefs.toggleHotKey) } } }
        watchAccess()
    }

    fun start() {
        state.value.selected?.let(controller::start)
        // Access can be turned off in System Settings at any time.
        mutableState.update { it.copy(access = controller.access()) }
        watchAccess()
    }

    fun stop() = controller.stop()

    fun select(routine: ClickRoutine) {
        viewModelScope.launch { repository.select(routine.id) }
    }

    fun requestAccess() {
        controller.requestAccess()
    }

    fun requestDeletion(routine: ClickRoutine) = mutableState.update { it.copy(routinePendingDeletion = routine) }

    fun cancelDeletion() = mutableState.update { it.copy(routinePendingDeletion = null) }

    fun confirmDeletion() {
        val routine = state.value.routinePendingDeletion ?: return
        mutableState.update { it.copy(routinePendingDeletion = null) }
        viewModelScope.launch { repository.delete(routine.id) }
    }

    /** Notices the user allowing access in System Settings; only polls while it is missing. */
    private var watchingAccess = false

    private fun watchAccess() {
        if (watchingAccess || state.value.access != InputAccess.NeedsPermission) return
        watchingAccess = true
        viewModelScope.launch {
            while (state.value.access == InputAccess.NeedsPermission) {
                delay(ACCESS_CHECK_MILLIS)
                val access = controller.access()
                mutableState.update { it.copy(access = access) }
            }
            watchingAccess = false
        }
    }

    private companion object {
        const val ACCESS_CHECK_MILLIS = 2_000L
    }
}
