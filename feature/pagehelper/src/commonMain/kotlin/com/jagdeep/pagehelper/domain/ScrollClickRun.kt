package com.jagdeep.pagehelper.domain

import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.automation.PointerButton
import com.jagdeep.automation.PointerDevice
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.shared.history.RunOutcome
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** A video the run has stopped for. */
internal enum class PageVideo {
    None,
    Playing,

    /** The browser refused to start it, or it was paused: it's up to the user to press play. */
    NeedsPlay,
}

/** What the extension last said about the page being scrolled. */
internal data class PageReading(
    /** False while another app is in front, so a click would land in the wrong place. */
    val focused: Boolean,
    val atBottom: Boolean,
    /** Identifies the page's contents at the bottom, to tell whether a click changed anything. */
    val signature: String?,
    val video: PageVideo = PageVideo.None,
)

enum class ScrollClickPhase { WaitingForChrome, Scrolling, WatchingVideo, VideoNeedsPlay, AtBottom, WaitingForPage }

data class ScrollClickProgress(val round: Int, val rounds: Int, val clicks: Long, val phase: ScrollClickPhase)

/**
 * Scrolls to the bottom, stopping for any video until it ends, then clicks one point, waits for the
 * page to change, and repeats. Safety checks run throughout every wait, and it never clicks unless
 * Chrome is in front.
 */
internal class ScrollClickRun(
    private val pointer: PointerDevice,
    private val access: InputAccessGate,
    /** Null while the run may go on; otherwise why it can't (Chrome closed, helper turned off). */
    private val interruption: () -> RunOutcome?,
) {
    suspend fun execute(
        point: ScreenPoint,
        rounds: Int,
        safety: ScrollClickSafety,
        pages: StateFlow<PageReading?>,
        setScrolling: (Boolean) -> Unit,
        onProgress: (ScrollClickProgress) -> Unit,
    ): RunOutcome {
        var clicks = 0L
        // Where this run put the pointer; anywhere else means the user moved it.
        var placedAt: ScreenPoint? = null

        fun safetyStop(): RunOutcome? {
            interruption()?.let { return it }
            if (access.access() != InputAccess.Granted) return RunOutcome.AccessLost
            val position = pointer.position() ?: return null
            val placed = placedAt
            if (placed != null && abs(position.x - placed.x) <= MOVE_TOLERANCE && abs(position.y - placed.y) <= MOVE_TOLERANCE) return null
            if (safety.stopAtScreenCorner && pointer.isScreenCorner(position)) return RunOutcome.ScreenCorner
            if (safety.stopWhenPointerMoves && placed != null) return RunOutcome.PointerMoved
            return null
        }

        for (round in 1..rounds) {
            fun report(phase: ScrollClickPhase) = onProgress(ScrollClickProgress(round, rounds, clicks, phase))

            setScrolling(true)
            var heldMillis = 0L
            var readyToClick = false
            var signatureBefore: String? = null
            while (!readyToClick) {
                safetyStop()?.let { return it }
                val page = pages.value
                when {
                    page == null -> {
                        heldMillis = 0
                        report(ScrollClickPhase.WaitingForChrome)
                    }
                    page.video != PageVideo.None -> {
                        // However long it runs. The user may well use the pointer on the player.
                        heldMillis = 0
                        placedAt = null
                        report(if (page.video == PageVideo.Playing) ScrollClickPhase.WatchingVideo else ScrollClickPhase.VideoNeedsPlay)
                    }
                    !page.focused -> {
                        // The user may well move the pointer to bring Chrome forward.
                        heldMillis = 0
                        placedAt = null
                        report(ScrollClickPhase.WaitingForChrome)
                    }
                    !page.atBottom -> {
                        // More content arrived, so this wasn't the end after all.
                        heldMillis = 0
                        report(ScrollClickPhase.Scrolling)
                    }
                    heldMillis >= BOTTOM_HOLD_MILLIS -> {
                        // Decided on a fresh reading, with no wait between it and the click.
                        readyToClick = true
                        signatureBefore = page.signature
                    }
                    else -> {
                        heldMillis += CHECK_MILLIS
                        report(ScrollClickPhase.AtBottom)
                    }
                }
                if (!readyToClick) delay(CHECK_MILLIS)
            }

            setScrolling(false)
            if (!pointer.click(point, PointerButton.Left, 1)) return RunOutcome.Failed
            clicks++
            placedAt = point
            report(ScrollClickPhase.WaitingForPage)
            if (round == rounds) return RunOutcome.Completed

            var waitedMillis = 0L
            while (true) {
                safetyStop()?.let { return it }
                val page = pages.value
                // A video starting means the click did something, whatever else stayed the same.
                if (page != null && page.video != PageVideo.None) break
                if (page != null && page.focused) {
                    if (!page.atBottom || page.signature != signatureBefore) break
                    waitedMillis += CHECK_MILLIS
                    if (waitedMillis >= PAGE_CHANGE_TIMEOUT_MILLIS) return RunOutcome.PageUnchanged
                }
                delay(CHECK_MILLIS)
            }
        }
        return RunOutcome.Completed
    }

    private companion object {
        const val CHECK_MILLIS = 50L

        /** Late-loading content can extend the page, so the bottom has to stay the bottom this long. */
        const val BOTTOM_HOLD_MILLIS = 2_000L

        /** A click that changes nothing for this long won't: stop rather than keep clicking a dead end. */
        const val PAGE_CHANGE_TIMEOUT_MILLIS = 15_000L
        const val MOVE_TOLERANCE = 3
    }
}

internal data class ScrollClickSafety(val stopWhenPointerMoves: Boolean, val stopAtScreenCorner: Boolean)
