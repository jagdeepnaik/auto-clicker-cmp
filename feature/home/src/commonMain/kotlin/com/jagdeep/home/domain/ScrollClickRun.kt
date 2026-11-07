package com.jagdeep.home.domain

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

enum class ScrollClickPhase { ReadingPage, WaitingForChrome, Scrolling, WatchingVideo, VideoNeedsPlay, AtBottom, ClickingSteps, WaitingForPage }

/** [step] of [steps] is the step being clicked, or about to be, while [phase] is ClickingSteps. */
data class ScrollClickProgress(
    val round: Int,
    val rounds: Int,
    val clicks: Long,
    val phase: ScrollClickPhase,
    val step: Int = 0,
    val steps: Int = 0,
)

/**
 * Scrolls to the bottom, stopping for any video until it ends, then clicks its steps in order, each
 * after its own wait, waits for the page to change, and repeats. Safety checks run throughout every wait, and it never clicks unless
 * Chrome is in front.
 */
internal class ScrollClickRun(
    private val pointer: PointerDevice,
    private val access: InputAccessGate,
    /** Null while the run may go on; otherwise why it can't (Chrome closed, helper turned off). */
    private val interruption: () -> RunOutcome?,
) {
    suspend fun execute(
        steps: List<ClickPoint>,
        rounds: Int,
        safety: ScrollClickSafety,
        pages: StateFlow<PageReading?>,
        setScrolling: (Boolean) -> Unit,
        texts: TextClicker? = null,
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
            fun report(phase: ScrollClickPhase, step: Int = 0) = onProgress(ScrollClickProgress(round, rounds, clicks, phase, step, steps.size))

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
                        report(ScrollClickPhase.ReadingPage)
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
            for ((index, step) in steps.withIndex()) {
                val number = index + 1
                report(ScrollClickPhase.ClickingSteps, number)
                // Each step's wait counts from the click before it; the first from reaching the bottom.
                var waited = 0L
                while (waited < (step.delayMillis ?: 0)) {
                    safetyStop()?.let { return it }
                    if (pages.value?.focused != false) waited += CHECK_MILLIS
                    delay(CHECK_MILLIS)
                }
                // Never into another app: with Chrome away, wait for it to come back.
                while (pages.value?.focused == false) {
                    safetyStop()?.let { return it }
                    placedAt = null
                    report(ScrollClickPhase.WaitingForChrome, number)
                    delay(CHECK_MILLIS)
                }
                safetyStop()?.let { return it }
                val text = step.text?.takeIf { it.isNotBlank() }
                if (text != null && texts != null) {
                    // Chrome finds the button wherever it is; it may appear only after a video ends.
                    val request = texts.request(text)
                    var looked = 0L
                    while (texts.clicked.value < request) {
                        safetyStop()?.let {
                            texts.clear()
                            return it
                        }
                        if (pages.value?.focused != false) looked += CHECK_MILLIS
                        if (looked >= BUTTON_WAIT_MILLIS) {
                            texts.clear()
                            return RunOutcome.ButtonNotFound
                        }
                        delay(CHECK_MILLIS)
                    }
                    texts.clear()
                } else {
                    val at = ScreenPoint(step.x, step.y)
                    if (!pointer.click(at, step.button ?: PointerButton.Left, 1)) return RunOutcome.Failed
                    placedAt = at
                }
                clicks++
                report(ScrollClickPhase.ClickingSteps, number)
            }
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

        /** How long a button step waits for its button to appear, counting only while Chrome is in front. */
        const val BUTTON_WAIT_MILLIS = 30_000L

        /** A click that changes nothing for this long won't: stop rather than keep clicking a dead end. */
        const val PAGE_CHANGE_TIMEOUT_MILLIS = 15_000L
        const val MOVE_TOLERANCE = 3
    }
}

/** Asks Chrome to click a button by its text; [clicked] rises to a request's number once that click is done. */
internal interface TextClicker {
    val clicked: StateFlow<Long>

    /** Returns the request's number. */
    fun request(text: String): Long

    fun clear()
}

internal data class ScrollClickSafety(val stopWhenPointerMoves: Boolean, val stopAtScreenCorner: Boolean)
