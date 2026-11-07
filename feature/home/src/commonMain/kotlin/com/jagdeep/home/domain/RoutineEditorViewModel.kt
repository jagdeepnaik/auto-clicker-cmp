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
data class PointCapture(
    val pointer: ScreenPoint? = null,
    val added: Int = 0,
    /** Set while picking a new spot for an existing step, which then ends the picking. */
    val replacing: Int? = null,
)

data class RoutineEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val draft: RoutineDraft = RoutineDraft(),
    val errors: DraftErrors = DraftErrors(),
    val capture: PointCapture? = null,
    val captureHotKey: HotKey = HotKey.F7,
    val pageClickHotKey: HotKey = HotKey.F8,
    val pageScrollHotKey: HotKey = HotKey.F9,
    val runningApps: List<String> = emptyList(),
    val isSaved: Boolean = false,
)

class RoutineEditorViewModel(
    private val routineId: String?,
    private val controller: ClickController,
    private val repository: RoutineRepository,
    private val newId: () -> String,
    /** The kind of a new routine; an existing one keeps its own. */
    private val newKind: RoutineKind = RoutineKind.Click,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RoutineEditorUiState(isNew = routineId == null))
    val state = mutableState.asStateFlow()
    private var captureJob: Job? = null

    init {
        viewModelScope.launch {
            val library = repository.library.filterNotNull().first()
            val existing = routineId?.let { id -> library.routines.firstOrNull { it.id == id } }
            mutableState.update {
                it.copy(isLoading = false, draft = existing?.toDraft() ?: newDraft(newKind, "Routine ${library.routines.size + 1}"))
            }
        }
        viewModelScope.launch { controller.preferences.collect { prefs -> mutableState.update { it.copy(captureHotKey = prefs.captureHotKey, pageClickHotKey = prefs.pageClickHotKey, pageScrollHotKey = prefs.pageScrollHotKey) } } }
        refreshApps()
    }

    /** Applies an edit from a field, and clears errors so they only reappear on the next save. */
    fun updateDraft(change: (RoutineDraft) -> RoutineDraft) =
        mutableState.update { it.copy(draft = change(it.draft), errors = DraftErrors()) }

    fun refreshApps() = mutableState.update { it.copy(runningApps = controller.runningApps().sorted()) }

    fun removePoint(index: Int) = updateDraft { draft -> draft.copy(points = draft.points.filterIndexed { i, _ -> i != index }) }

    fun addEmptyPoint() = updateDraft { draft -> draft.copy(points = draft.points + newPoint(draft, "", "")) }

    /** Moves a point up ([by] -1) or down (+1); the points run top to bottom. */
    fun movePoint(index: Int, by: Int) = updateDraft { draft ->
        val target = index + by
        if (index !in draft.points.indices || target !in draft.points.indices) return@updateDraft draft
        val points = draft.points.toMutableList()
        points[index] = points[target].also { points[target] = points[index] }
        draft.copy(points = points)
    }

    /** A new point starts with the routine's own timing and button, ready to change. */
    private fun newPoint(draft: RoutineDraft, x: String, y: String): PointDraft {
        val interval = draft.interval.trim().toLongOrNull()?.let { it * draft.intervalUnit.millis } ?: DEFAULT_POINT_DELAY_MILLIS
        return PointDraft(x, y, interval.toString(), draft.button)
    }

    fun updatePoint(index: Int, point: PointDraft) =
        updateDraft { draft -> draft.copy(points = draft.points.mapIndexed { i, old -> if (i == index) point else old }) }

    /**
     * Each press of the capture key adds a point, so several can be picked without coming back. A
     * scroll and click routine has one spot, so a press replaces it and picking ends.
     */
    fun startCapture(replacing: Int? = null) {
        if (captureJob != null) return
        mutableState.update { it.copy(capture = PointCapture(pointer = controller.pointerPosition(), replacing = replacing)) }
        captureJob = viewModelScope.launch {
            launch {
                controller.captures.collect { point ->
                    if (replacing != null) {
                        // A step keeps its wait and button; only where it clicks changes.
                        updateDraft { draft ->
                            draft.copy(points = draft.points.mapIndexed { i, old -> if (i == replacing) old.copy(x = point.x.toString(), y = point.y.toString()) else old })
                        }
                        finishCapture()
                        return@collect
                    }
                    val picked = newPoint(state.value.draft, point.x.toString(), point.y.toString())
                    if (state.value.draft.kind == RoutineKind.ScrollClick) {
                        updateDraft { it.copy(points = listOf(picked)) }
                        finishCapture()
                        return@collect
                    }
                    updateDraft { it.copy(points = it.points + picked) }
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

    /** A new multi-point click runs its list once until told otherwise. */
    private fun newDraft(kind: RoutineKind, name: String): RoutineDraft = when (kind) {
        RoutineKind.Sequence -> RoutineDraft(kind = kind, name = name, targetMode = TargetMode.AtPoints, limitMode = LimitMode.Passes, limitPasses = "1")
        else -> RoutineDraft(kind = kind, name = name)
    }

    private companion object {
        const val POINTER_REFRESH_MILLIS = 100L
    }
}
