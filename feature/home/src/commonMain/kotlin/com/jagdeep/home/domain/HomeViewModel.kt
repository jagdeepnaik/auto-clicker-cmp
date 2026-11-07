package com.jagdeep.home.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.shared.history.RunRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val routines: List<Routine> = emptyList(),
    val selected: Routine? = null,
    val run: RunState = RunState.Idle,
    val page: PageState = PageState(),
    val lastRun: RunRecord? = null,
    val access: InputAccess = InputAccess.Granted,
    val toggleHotKey: HotKey = HotKey.F6,
    val pageClickHotKey: HotKey = HotKey.F8,
    val pageScrollHotKey: HotKey = HotKey.F9,
    val routinePendingDeletion: Routine? = null,
    /** Whether routines can be exported to and imported from files here. */
    val canUseFiles: Boolean = true,
) {
    /** True while any routine runs, of any kind. */
    val isActive: Boolean get() = run != RunState.Idle || page.activity != null
}

/** A page helper only reads Chrome and clicks through the extension, so it needs no input permission. */
fun Routine.needsInputAccess(): Boolean = this !is PageHelperRoutine && this !is CourseHelperRoutine

class HomeViewModel(
    private val controller: ClickController,
    private val repository: RoutineRepository,
    private val files: RoutineFiles = NoRoutineFiles,
    private val newId: () -> String = { "" },
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState(access = controller.access(), canUseFiles = files.isAvailable))
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)

    /** Short results of an export or import, for a toast. */
    val messages: Flow<String> = mutableMessages.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.library.collect { library ->
                mutableState.update {
                    it.copy(isLoading = library == null, routines = library?.routines.orEmpty(), selected = library?.selected)
                }
            }
        }
        viewModelScope.launch { controller.state.collect { run -> mutableState.update { it.copy(run = run) } } }
        viewModelScope.launch { controller.page.state.collect { page -> mutableState.update { it.copy(page = page) } } }
        viewModelScope.launch {
            // Whichever kind of routine finished most recently.
            combine(controller.lastRun, controller.page.lastRun) { click, page ->
                listOfNotNull(click, page).maxByOrNull { it.endedAtMillis }
            }.collect { last -> mutableState.update { it.copy(lastRun = last) } }
        }
        viewModelScope.launch {
            controller.preferences.collect { prefs ->
                mutableState.update {
                    it.copy(toggleHotKey = prefs.toggleHotKey, pageClickHotKey = prefs.pageClickHotKey, pageScrollHotKey = prefs.pageScrollHotKey)
                }
            }
        }
        watchAccess()
    }

    fun start() {
        state.value.selected?.let(controller::start)
        // Access can be turned off in System Settings at any time.
        mutableState.update { it.copy(access = controller.access()) }
        watchAccess()
    }

    fun stop() = controller.stop()

    fun toggleScrolling() = controller.page.toggleScrolling()

    fun select(routine: Routine) {
        viewModelScope.launch { repository.select(routine.id) }
    }

    fun requestAccess() {
        controller.requestAccess()
    }

    /** Saves every routine to one file, to share or keep. */
    fun exportAll() = export(state.value.routines, suggestedName = "AutoClicker routines")

    fun export(routine: Routine) = export(listOf(routine), suggestedName = routine.name)

    private fun export(routines: List<Routine>, suggestedName: String) {
        if (routines.isEmpty()) return
        viewModelScope.launch {
            val fileName = suggestedName.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Routines" } + RoutineFileCodec.FILE_SUFFIX
            val result = runCatching { files.save(fileName, RoutineFileCodec.encode(routines)) }
            val message = result.fold(
                onSuccess = { savedTo -> savedTo?.let { "Exported ${count(routines.size)}." } },
                onFailure = { (it as? RoutineFileException)?.message ?: "Couldn't save the file. Try another folder." },
            )
            message?.let { mutableMessages.send(it) }
        }
    }

    /** Adds the routines in a file the user picks, as new routines alongside their own. */
    fun import() {
        viewModelScope.launch {
            val text = runCatching { files.open() }.getOrElse {
                mutableMessages.send((it as? RoutineFileException)?.message ?: "Couldn't read that file.")
                return@launch
            } ?: return@launch
            val imported = RoutineFileCodec.decode(text)
            if (imported == null || imported.routines.isEmpty()) {
                mutableMessages.send("That file has no routines AutoClicker can use.")
                return@launch
            }
            // New ids, so an import never replaces the user's own routines.
            val added = imported.routines.map { it.withId(newId()) }
            added.forEach { repository.save(it) }
            repository.select(added.first().id)
            val skipped = if (imported.skipped > 0) " ${count(imported.skipped)} couldn't be used and were left out." else ""
            mutableMessages.send("Imported ${count(added.size)}.$skipped Spots are positions on the screen they were made on, so check them before running.")
        }
    }

    private fun count(n: Int) = if (n == 1) "1 routine" else "$n routines"

    fun requestDeletion(routine: Routine) = mutableState.update { it.copy(routinePendingDeletion = routine) }

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

/** The same routine under a new id. */
internal fun Routine.withId(id: String): Routine = when (this) {
    is ClickRoutine -> copy(id = id)
    is ScrollClickRoutine -> copy(id = id)
    is PageHelperRoutine -> copy(id = id)
    is CourseHelperRoutine -> copy(id = id)
}
