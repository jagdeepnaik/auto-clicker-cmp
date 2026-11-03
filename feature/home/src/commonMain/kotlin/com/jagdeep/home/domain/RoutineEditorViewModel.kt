package com.jagdeep.home.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.ScreenPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Set while points are being picked with the capture hot key. */
data class PointCapture(val pointer: ScreenPoint? = null, val added: Int = 0)

data class RoutineEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val draft: RoutineDraft = RoutineDraft(),
    val errors: DraftErrors = DraftErrors(),
    val capture: PointCapture? = null,
    val captureHotKey: HotKey = HotKey.F7,
    val runningApps: List<String> = emptyList(),
    val isSaved: Boolean = false,
)

class RoutineEditorViewModel(
    private val routineId: String?,
    private val controller: ClickController,
    private val repository: RoutineRepository,
    private val newId: () -> String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RoutineEditorUiState(isNew = routineId == null))
    val state = mutableState.asStateFlow()
    private var captureJob: Job? = null

    init {
        viewModelScope.launch {
            val library = repository.library.filterNotNull().first()
            val existing = routineId?.let { id -> library.routines.firstOrNull { it.id == id } }
            mutableState.update {
                it.copy(isLoading = false, draft = existing?.toDraft() ?: RoutineDraft(name = "Routine ${library.routines.size + 1}"))
            }
        }
        viewModelScope.launch { controller.preferences.collect { prefs -> mutableState.update { it.copy(captureHotKey = prefs.captureHotKey) } } }
        refreshApps()
    }

    /** Applies an edit from a field, and clears errors so they only reappear on the next save. */
    fun updateDraft(change: (RoutineDraft) -> RoutineDraft) =
        mutableState.update { it.copy(draft = change(it.draft), errors = DraftErrors()) }

    fun refreshApps() = mutableState.update { it.copy(runningApps = controller.runningApps().sorted()) }

    fun removePoint(index: Int) = updateDraft { draft -> draft.copy(points = draft.points.filterIndexed { i, _ -> i != index }) }

    fun addEmptyPoint() = updateDraft { draft -> draft.copy(points = draft.points + PointDraft()) }

    fun updatePoint(index: Int, point: PointDraft) =
        updateDraft { draft -> draft.copy(points = draft.points.mapIndexed { i, old -> if (i == index) point else old }) }

    /** Each press of the capture key adds a point, so several can be picked without coming back. */
    fun startCapture() {
        if (captureJob != null) return
        mutableState.update { it.copy(capture = PointCapture(pointer = controller.pointerPosition())) }
        captureJob = viewModelScope.launch {
            launch {
                controller.captures.collect { point ->
                    updateDraft { it.copy(points = it.points + PointDraft(point.x.toString(), point.y.toString())) }
                    mutableState.update { it.copy(capture = it.capture?.copy(added = it.capture.added + 1)) }
                }
            }
            while (true) {
                delay(POINTER_REFRESH_MILLIS)
                val pointer = controller.pointerPosition()
                mutableState.update { it.copy(capture = it.capture?.copy(pointer = pointer)) }
            }
        }
    }

    fun finishCapture() {
        captureJob?.cancel()
        captureJob = null
        mutableState.update { it.copy(capture = null) }
    }

    fun save() {
        finishCapture()
        val draft = state.value.draft
        val errors = draft.validate()
        if (errors.hasAny) {
            mutableState.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            repository.save(draft.toRoutine(routineId ?: newId()))
            mutableState.update { it.copy(isSaved = true) }
        }
    }

    private companion object {
        const val POINTER_REFRESH_MILLIS = 100L
    }
}
