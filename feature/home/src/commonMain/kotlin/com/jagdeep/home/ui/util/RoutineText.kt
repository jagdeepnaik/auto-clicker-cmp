package com.jagdeep.home.ui.util

import com.jagdeep.automation.PointerButton
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.ClickTarget
import com.jagdeep.home.domain.PageHelperRoutine
import com.jagdeep.home.domain.PauseReason
import com.jagdeep.home.domain.Routine
import com.jagdeep.home.domain.ScrollClickPhase
import com.jagdeep.home.domain.ScrollClickProgress
import com.jagdeep.home.domain.ScrollClickRoutine
import com.jagdeep.home.domain.RunLimit
import com.jagdeep.shared.history.RunRecord

/** One line describing a routine, starting with its kind: "Click routine · Left click every 1 s · at the pointer". */
internal fun routineSummary(routine: Routine): String = "${routine.kind.label} · " + when (routine) {
    is ClickRoutine -> clickSummary(routine)
    is ScrollClickRoutine -> buildList {
        add("at ${routine.point.x}, ${routine.point.y}")
        add(if (routine.rounds == 1) "1 round" else "${routine.rounds} rounds")
        if (routine.watchVideos) add("stops for videos")
    }.joinToString(" · ")
    is PageHelperRoutine -> routine.words.joinToString(", ")
}

/** "Left click every 1 s · at the pointer · in Google Chrome". */
private fun clickSummary(routine: ClickRoutine): String = buildList {
    val kind = if (routine.clicksPerAction == 2) "double click" else "click"
    add("${routine.button.label()} $kind every ${intervalLabel(routine.intervalMillis)}")
    add(
        when (val target = routine.target) {
            ClickTarget.AtPointer -> "at the pointer"
            is ClickTarget.AtPoints -> if (target.points.size == 1) "1 point" else "${target.points.size} points"
        },
    )
    when (val limit = routine.limit) {
        RunLimit.UntilStopped -> Unit
        is RunLimit.Clicks -> add(if (limit.count == 1L) "1 click" else "${limit.count} clicks")
        is RunLimit.Duration -> add("for ${intervalLabel(limit.millis)}")
    }
    routine.onlyInApp?.let { add("only in $it") }
}.joinToString(" · ")

internal fun intervalLabel(millis: Long): String = when {
    millis % 60_000 == 0L -> "${millis / 60_000} min"
    millis % 1_000 == 0L -> "${millis / 1_000} s"
    else -> "$millis ms"
}

internal fun pauseLabel(reason: PauseReason): String = when (reason) {
    is PauseReason.WaitingForApp -> "Paused until ${reason.app} is in front"
    PauseReason.OverOwnWindow -> "Paused: the click would land on this window"
}

internal fun scrollClickDetail(progress: ScrollClickProgress): String = when (progress.phase) {
    ScrollClickPhase.ReadingPage -> "Reading the page. If this stays, refresh the page in Chrome."
    ScrollClickPhase.WaitingForChrome -> "Bring Chrome to the front to continue. ${clicksLabel(progress.clicks)} so far."
    ScrollClickPhase.Scrolling -> "Scrolling to the bottom. ${clicksLabel(progress.clicks)} so far."
    ScrollClickPhase.WatchingVideo -> "Playing a video. It carries on when the video ends."
    ScrollClickPhase.VideoNeedsPlay -> "Press play on the video in Chrome. It carries on when the video ends."
    ScrollClickPhase.AtBottom -> "Reached the bottom. Clicking soon."
    ScrollClickPhase.WaitingForPage -> "Clicked. Waiting for the page to change. ${clicksLabel(progress.clicks)} so far."
}

internal fun clicksLabel(clicks: Long): String = if (clicks == 1L) "1 click" else "$clicks clicks"

internal fun lastRunLabel(record: RunRecord): String = "Last run: ${clicksLabel(record.clicks)}. ${record.outcome.label}."

private fun PointerButton.label() = when (this) {
    PointerButton.Left -> "Left"
    PointerButton.Right -> "Right"
    PointerButton.Middle -> "Middle"
}
