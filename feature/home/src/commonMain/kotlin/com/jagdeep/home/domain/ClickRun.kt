package com.jagdeep.home.domain

import com.jagdeep.automation.ForegroundApps
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.automation.PointerDevice
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.automation.isSameApp
import com.jagdeep.shared.history.RunOutcome
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Which safety stops apply to a run; both come from Settings. */
data class RunSafety(
    val stopWhenPointerMoves: Boolean = true,
    val stopAtScreenCorner: Boolean = true,
)

/** Why a running routine is waiting instead of clicking. */
sealed interface PauseReason {
    data class WaitingForApp(val app: String) : PauseReason

    /** The click would land on this app's own window, for example on the Stop button. */
    data object OverOwnWindow : PauseReason
}

/**
 * Performs one routine until it finishes or is cancelled. Safety checks run between clicks and
 * throughout every wait, so a long interval never delays a stop.
 */
internal class ClickRun(
    private val pointer: PointerDevice,
    private val apps: ForegroundApps,
    private val access: InputAccessGate,
    private val now: () -> Long,
    private val random: Random,
) {
    /** Reports each change of click count or pause, then returns how the run ended. */
    suspend fun execute(
        routine: ClickRoutine,
        safety: RunSafety,
        onProgress: (clicks: Long, pause: PauseReason?) -> Unit,
    ): RunOutcome {
        val steps = (routine.target as? ClickTarget.AtPoints)?.points
        val points = steps?.map { ScreenPoint(it.x, it.y) }
        val startedAt = now()
        var clicks = 0L
        var nextPoint = 0
        // Where this run last put the pointer; anywhere else means the user moved it.
        var placedAt: ScreenPoint? = null
        var lastPause: PauseReason? = null

        fun limitReached(): Boolean = when (val limit = routine.limit) {
            RunLimit.UntilStopped -> false
            is RunLimit.Clicks -> clicks >= limit.count
            is RunLimit.Duration -> now() - startedAt >= limit.millis
            // One pass clicks every point once; without points each click is a pass.
            is RunLimit.Passes -> clicks >= limit.count * (points?.size ?: 1)
        }

        fun safetyStop(): RunOutcome? {
            if (access.access() != InputAccess.Granted) return RunOutcome.AccessLost
            val position = pointer.position() ?: return null
            val placed = placedAt
            if (position.isNear(placed)) return null
            if (safety.stopAtScreenCorner && pointer.isScreenCorner(position)) return RunOutcome.ScreenCorner
            if (safety.stopWhenPointerMoves && placed != null) return RunOutcome.PointerMoved
            return null
        }

        /** Waits in short slices; returns the outcome if the run should end during the wait. */
        suspend fun waitWatching(millis: Long): RunOutcome? {
            val waitUntil = now() + millis
            while (true) {
                safetyStop()?.let { return it }
                if (limitReached()) return RunOutcome.Completed
                val remaining = waitUntil - now()
                if (remaining <= 0) return null
                delay(min(remaining, SAFETY_CHECK_MILLIS))
            }
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            if (limitReached()) return RunOutcome.Completed
            safetyStop()?.let { return it }

            val point = points?.get(nextPoint)
            val pause = pauseReason(routine, point)
            if (pause != lastPause) {
                lastPause = pause
                onProgress(clicks, pause)
            }
            if (pause != null) {
                // The user may well move the pointer to bring the right app forward.
                placedAt = null
                waitWatching(PAUSED_CHECK_MILLIS)?.let { return it }
                continue
            }

            val button = steps?.get(nextPoint)?.button ?: routine.button
            if (!pointer.click(point, button, routine.clicksPerAction)) return RunOutcome.Failed
            clicks++
            placedAt = point
            onProgress(clicks, null)
            if (points != null) nextPoint = (nextPoint + 1) % points.size
            if (limitReached()) return RunOutcome.Completed

            // Each point's wait comes before its click, counted from the click before it.
            val wait = steps?.get(nextPoint)?.delayMillis ?: routine.intervalMillis
            val extra = if (routine.randomExtraMillis > 0) random.nextLong(routine.randomExtraMillis + 1) else 0
            waitWatching(wait + extra)?.let { return it }
        }
    }

    private fun pauseReason(routine: ClickRoutine, point: ScreenPoint?): PauseReason? {
        val app = routine.onlyInApp
        if (app != null && apps.frontmost()?.let { isSameApp(it, app) } != true) return PauseReason.WaitingForApp(app)
        val clickAt = point ?: pointer.position()
        return if (clickAt != null && pointer.isOverOwnFocusedWindow(clickAt)) PauseReason.OverOwnWindow else null
    }

    /** Display scaling can round a position by a pixel or two after the pointer is placed. */
    private fun ScreenPoint.isNear(other: ScreenPoint?): Boolean =
        other != null && abs(x - other.x) <= MOVE_TOLERANCE && abs(y - other.y) <= MOVE_TOLERANCE

    private companion object {
        const val SAFETY_CHECK_MILLIS = 50L
        const val PAUSED_CHECK_MILLIS = 200L
        const val MOVE_TOLERANCE = 3
    }
}
