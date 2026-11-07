package com.jagdeep.home.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.logger.Log
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.live.LiveLog
import com.jagdeep.shared.live.LiveSource
import com.jagdeep.shared.live.NoLiveLog
import com.jagdeep.shared.live.log
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the clicker is doing right now. */
sealed interface RunState {
    data object Idle : RunState

    data class CountingDown(val routine: ClickRoutine, val secondsLeft: Int) : RunState

    data class Running(val routine: ClickRoutine, val clicks: Long, val pause: PauseReason?) : RunState
}

/**
 * Starts and stops routines of every kind for the whole app, one at a time. It runs click routines
 * itself and hands Chrome routines to [page]. It is process-scoped, not tied to a screen, because
 * the stop hot key has to keep working from any tab and from any other app.
 */
class ClickController(
    private val automation: DesktopAutomation,
    private val repository: RoutineRepository,
    preferences: Flow<AppPreferences>,
    private val history: RunHistoryStore,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val newId: () -> String,
    random: Random = Random.Default,
    /** Runs the Chrome routines, so that only one routine of any kind runs at a time. */
    val page: PageRunner = NoPageRunner,
    private val live: LiveLog = NoLiveLog,
) {
    private val run = ClickRun(automation.pointer, automation.apps, automation.access, now, random)
    private val currentPreferences: StateFlow<AppPreferences> = preferences.stateIn(scope, SharingStarted.Eagerly, AppPreferences())
    private val mutableState = MutableStateFlow<RunState>(RunState.Idle)
    private val mutableLastRun = MutableStateFlow<RunRecord?>(null)
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val mutableCaptures = MutableSharedFlow<ScreenPoint>(extraBufferCapacity = 8)
    private var job: Job? = null

    val state: StateFlow<RunState> = mutableState.asStateFlow()

    /** The most recent run finished since the app opened. */
    val lastRun: StateFlow<RunRecord?> = mutableLastRun.asStateFlow()

    /** Short explanations for actions that couldn't happen, including from the hot key. */
    val messages: Flow<String> = mutableMessages.asSharedFlow()

    /** Pointer positions recorded with the capture hot key. */
    val captures: Flow<ScreenPoint> = mutableCaptures.asSharedFlow()

    val preferences: StateFlow<AppPreferences> = currentPreferences

    init {
        scope.launch {
            automation.hotKeys.presses.collect { key ->
                live.log(LiveSource.Keys) { "${key.name} pressed" }
                val prefs = currentPreferences.value
                when (key) {
                    prefs.toggleHotKey -> toggle()
                    prefs.captureHotKey -> if (state.value == RunState.Idle) automation.pointer.position()?.let(mutableCaptures::tryEmit)
                    else -> Unit
                }
            }
        }
    }

    fun access(): InputAccess = automation.access.access()

    fun requestAccess(): Boolean = automation.access.requestAccess()

    fun pointerPosition(): ScreenPoint? = automation.pointer.position()

    fun runningApps(): List<String> = automation.apps.running()

    /** Stops whatever is happening, or starts the selected routine. */
    /** True while any routine runs, of any kind. */
    val isActive: Boolean get() = state.value != RunState.Idle || page.isActive

    fun toggle() {
        // This key stops everything: it never stops one routine and starts another in the same press.
        if (isActive) {
            stop()
            return
        }
        val routine = repository.library.value?.selected
        if (routine == null) mutableMessages.tryEmit("Create a routine first.") else start(routine)
    }

    /** Starts [routine] unless a routine is already running: only one runs at a time. */
    fun start(routine: Routine) {
        if (isActive) return
        if (routine !is ClickRoutine) {
            page.start(routine)?.let(mutableMessages::tryEmit)
            return
        }
        live.log(LiveSource.Routine) { "Starting ${routine.name} (${routine.kind.label}) after ${routine.startDelaySeconds} s" }
        when (automation.access.access()) {
            InputAccess.Granted -> Unit
            InputAccess.NeedsPermission -> {
                mutableMessages.tryEmit("Allow Accessibility access so clicks can reach other apps.")
                return
            }
            InputAccess.Unsupported -> {
                mutableMessages.tryEmit("Clicking in other apps works on Mac and Windows for now.")
                return
            }
        }
        // Set before launching, so a second press of the hot key can never start a second run.
        mutableState.value = RunState.CountingDown(routine, routine.startDelaySeconds)
        job = scope.launch { perform(routine) }.also { started ->
            // Cancelled before its first dispatch, the body never runs to reset this.
            started.invokeOnCompletion {
                if (mutableState.value is RunState.CountingDown) mutableState.value = RunState.Idle
            }
        }
    }

    /** Stops whatever routine is running. */
    fun stop() {
        job?.cancel()
        page.stop()
    }

    private suspend fun perform(routine: ClickRoutine) {
        try {
            for (secondsLeft in routine.startDelaySeconds downTo 1) {
                mutableState.value = RunState.CountingDown(routine, secondsLeft)
                delay(COUNTDOWN_STEP_MILLIS)
            }
        } catch (e: CancellationException) {
            // Cancelled before the first click: nothing ran, so there is nothing to record.
            mutableState.value = RunState.Idle
            throw e
        }

        val startedAt = now()
        var clicks = 0L
        mutableState.value = RunState.Running(routine, 0, null)
        val prefs = currentPreferences.value
        val safety = RunSafety(prefs.stopWhenPointerMoves, prefs.stopAtScreenCorner)
        var outcome = RunOutcome.StoppedByUser
        try {
            var lastPause: PauseReason? = null
            outcome = run.execute(routine, safety) { count, pause ->
                if (count > clicks) live.log(LiveSource.Click) { "Click $count" }
                if (pause != lastPause) {
                    live.log(LiveSource.Routine) { if (pause == null) "Clicking again" else "Paused: $pause" }
                    lastPause = pause
                }
                clicks = count
                mutableState.value = RunState.Running(routine, count, pause)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Run failed: ${e.message}", TAG)
            outcome = RunOutcome.Failed
        } finally {
            withContext(NonCancellable) {
                val record = RunRecord(newId(), routine.name, startedAt, now(), clicks, outcome)
                live.log(LiveSource.Routine) { "Ended ${routine.name}: ${outcome.label}, $clicks clicks" }
                mutableLastRun.value = record
                mutableState.value = RunState.Idle
                history.add(record)
            }
        }
    }

    private companion object {
        const val TAG = "ClickController"
        const val COUNTDOWN_STEP_MILLIS = 1_000L
    }
}
