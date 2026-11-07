package com.jagdeep.autoclicker.presentation.shell

import com.jagdeep.automation.HotKey
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.PageActivity
import com.jagdeep.home.domain.PageState
import com.jagdeep.home.domain.PauseReason
import com.jagdeep.home.domain.Routine
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.home.domain.RunState
import com.jagdeep.home.domain.ScrollClickPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What the desktop toolbar and status bar show. */
data class ShellUiState(
    val routines: List<Routine> = emptyList(),
    val selected: Routine? = null,
    val isActive: Boolean = false,
    /** The routine that's running, if any. */
    val runningName: String? = null,
    /** What's happening now, in a few words: "Refresh · 12 clicks", "Ready · Refresh". */
    val operation: String = "",
    /** When a click routine's next click is due, so the status bar can count down to it. */
    val nextClickAtMillis: Long? = null,
    val chromeConnected: Boolean = false,
    val chromeProblem: String? = null,
    val toggleHotKey: HotKey = HotKey.F6,
)

/**
 * The desktop shell's toolbar and status bar: pick a routine, start or stop it, and see what it's
 * doing. Like Android Studio's run configuration, it acts on the same routines and runner as the
 * Home page.
 */
class ShellModel(
    private val controller: ClickController,
    private val repository: RoutineRepository,
) {
    val state: Flow<ShellUiState> = combine(
        repository.library,
        controller.state,
        controller.page.state,
        controller.preferences,
    ) { library, run, page, prefs ->
        val running = when (run) {
            is RunState.CountingDown -> run.routine.name
            is RunState.Running -> run.routine.name
            RunState.Idle -> page.activity?.routine?.name
        }
        val selected = library?.selected
        ShellUiState(
            routines = library?.routines.orEmpty(),
            selected = selected,
            isActive = running != null,
            runningName = running,
            operation = operation(run, page, selected),
            nextClickAtMillis = (run as? RunState.Running)?.nextClickAtMillis,
            chromeConnected = page.isConnected,
            chromeProblem = page.problem,
            toggleHotKey = prefs.toggleHotKey,
        )
    }

    suspend fun select(routine: Routine) = repository.select(routine.id)

    fun start() {
        repository.library.value?.selected?.let(controller::start)
    }

    fun stop() = controller.stop()

    private fun operation(run: RunState, page: PageState, selected: Routine?): String {
        val activity = page.activity
        return when {
            run is RunState.CountingDown -> "${run.routine.name} · starting in ${run.secondsLeft} s"
            run is RunState.Running -> buildString {
                append(run.routine.name).append(" · ").append(clicks(run.clicks))
                when (val pause = run.pause) {
                    is PauseReason.WaitingForApp -> append(" · paused until ${pause.app} is in front")
                    PauseReason.OverOwnWindow -> append(" · paused: the click would land on this window")
                    null -> Unit
                }
            }
            activity is PageActivity.Helper -> buildString {
                append(activity.routine.name).append(" · ")
                append(page.foundLabel?.let { "next step: $it" } ?: "looking for a button")
                if (page.isScrolling) append(" · scrolling")
            }
            activity is PageActivity.Course -> "${activity.routine.name} · " + (page.foundLabel?.let { "next: $it" } ?: "open sagarmeinyog.com in Chrome")
            activity is PageActivity.ScrollClick -> {
                val progress = activity.progress
                val doing = if (progress.phase == ScrollClickPhase.ClickingSteps) "clicking step ${progress.step} of ${progress.steps}" else phase(progress.phase)
                "${activity.routine.name} · round ${progress.round} of ${progress.rounds} · $doing · ${clicks(progress.clicks)}"
            }
            selected != null -> "Ready · ${selected.name}"
            else -> "No routine yet"
        }
    }

    private fun phase(phase: ScrollClickPhase): String = when (phase) {
        ScrollClickPhase.ReadingPage -> "reading the page"
        ScrollClickPhase.WaitingForChrome -> "waiting for Chrome to be in front"
        ScrollClickPhase.Scrolling -> "scrolling"
        ScrollClickPhase.WatchingVideo -> "playing a video"
        ScrollClickPhase.VideoNeedsPlay -> "press play on the video"
        ScrollClickPhase.AtBottom -> "at the bottom, clicking soon"
        ScrollClickPhase.ClickingSteps -> "clicking steps at the bottom"
        ScrollClickPhase.WaitingForPage -> "waiting for the page to change"
    }

    private fun clicks(count: Long) = if (count == 1L) "1 click" else "$count clicks"
}

/** "next click in 0.8 s", counting down from [nextClickAtMillis]. */
internal fun nextClickLabel(nextClickAtMillis: Long, nowMillis: Long): String {
    val tenths = ((nextClickAtMillis - nowMillis).coerceAtLeast(0) + 99) / 100
    return when {
        tenths < 600 -> "next click in ${tenths / 10}.${tenths % 10} s"
        else -> "next click in ${tenths / 600} min ${(tenths % 600) / 10} s"
    }
}
