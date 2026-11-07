package com.jagdeep.home.domain

import com.jagdeep.automation.PointerButton

enum class IntervalUnit(val label: String, val millis: Long) {
    Milliseconds("Milliseconds", 1),
    Seconds("Seconds", 1_000),
    Minutes("Minutes", 60_000),
}

enum class TargetMode { AtPointer, AtPoints }

enum class LimitMode { UntilStopped, Clicks, Minutes }

data class PointDraft(val x: String = "", val y: String = "")

/** What the editor shows: numbers stay text until saved, so half-typed values are never lost. */
data class RoutineDraft(
    val kind: RoutineKind = RoutineKind.Click,
    val name: String = "",
    val targetMode: TargetMode = TargetMode.AtPointer,
    val points: List<PointDraft> = emptyList(),
    val button: PointerButton = PointerButton.Left,
    val doubleClick: Boolean = false,
    val interval: String = "1",
    val intervalUnit: IntervalUnit = IntervalUnit.Seconds,
    val randomExtraMillis: String = "0",
    val limitMode: LimitMode = LimitMode.UntilStopped,
    val limitClicks: String = "100",
    val limitMinutes: String = "10",
    val onlyInApp: String? = null,
    val startDelaySeconds: String = "3",
    // Chrome routines. A scroll and click routine uses the first of [points] as its spot.
    val words: String = DEFAULT_WORDS.joinToString(", "),
    val pixelsPerSecond: Int = DEFAULT_PIXELS_PER_SECOND,
    val soundOnVideoEnd: Boolean = true,
    val rounds: String = DEFAULT_SCROLL_CLICK_ROUNDS.toString(),
    val watchVideos: Boolean = true,
)

/** Field-level problems; a null field is fine. */
data class DraftErrors(
    val name: String? = null,
    val points: String? = null,
    val interval: String? = null,
    val randomExtra: String? = null,
    val limit: String? = null,
    val startDelay: String? = null,
    val words: String? = null,
    val rounds: String? = null,
) {
    val hasAny: Boolean get() = listOf(name, points, interval, randomExtra, limit, startDelay, words, rounds).any { it != null }
}

internal const val MIN_INTERVAL_MILLIS = 10L
internal const val MAX_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
private const val MAX_NAME_LENGTH = 60
private const val MAX_RANDOM_EXTRA_MILLIS = 60L * 60 * 1000
private const val MAX_LIMIT_CLICKS = 1_000_000_000L
private const val MAX_LIMIT_MINUTES = 7L * 24 * 60
private const val MAX_START_DELAY_SECONDS = 60
private const val MAX_COORDINATE = 100_000

fun Routine.toDraft(): RoutineDraft = when (this) {
    is ClickRoutine -> toDraft()
    is ScrollClickRoutine -> RoutineDraft(
        kind = RoutineKind.ScrollClick,
        name = name,
        points = listOf(PointDraft(point.x.toString(), point.y.toString())),
        pixelsPerSecond = pixelsPerSecond,
        rounds = rounds.toString(),
        watchVideos = watchVideos,
    )
    is PageHelperRoutine -> RoutineDraft(
        kind = RoutineKind.PageHelper,
        name = name,
        words = words.joinToString(", "),
        pixelsPerSecond = pixelsPerSecond,
        soundOnVideoEnd = soundOnVideoEnd,
    )
}

fun ClickRoutine.toDraft(): RoutineDraft {
    // Shows the interval in the largest unit that keeps it a whole number.
    val unit = IntervalUnit.entries.last { intervalMillis % it.millis == 0L }
    return RoutineDraft(
        name = name,
        targetMode = if (target is ClickTarget.AtPoints) TargetMode.AtPoints else TargetMode.AtPointer,
        points = (target as? ClickTarget.AtPoints)?.points.orEmpty().map { PointDraft(it.x.toString(), it.y.toString()) },
        button = button,
        doubleClick = clicksPerAction == 2,
        interval = (intervalMillis / unit.millis).toString(),
        intervalUnit = unit,
        randomExtraMillis = randomExtraMillis.toString(),
        limitMode = when (limit) {
            RunLimit.UntilStopped -> LimitMode.UntilStopped
            is RunLimit.Clicks -> LimitMode.Clicks
            is RunLimit.Duration -> LimitMode.Minutes
        },
        limitClicks = (limit as? RunLimit.Clicks)?.count?.toString() ?: RoutineDraft().limitClicks,
        limitMinutes = (limit as? RunLimit.Duration)?.let { (it.millis / 60_000).toString() } ?: RoutineDraft().limitMinutes,
        onlyInApp = onlyInApp,
        startDelaySeconds = startDelaySeconds.toString(),
    )
}

fun RoutineDraft.validate(): DraftErrors = when (kind) {
    RoutineKind.Click -> validateClick()
    RoutineKind.ScrollClick -> DraftErrors(
        name = nameError(),
        points = when {
            points.isEmpty() -> "Pick the spot to click."
            points.first().x.coordinate() == null || points.first().y.coordinate() == null -> "The spot needs a whole-number X and Y."
            else -> null
        },
        rounds = rounds.trim().toIntOrNull().let {
            if (it == null || it !in MIN_SCROLL_CLICK_ROUNDS..MAX_SCROLL_CLICK_ROUNDS) "Use between $MIN_SCROLL_CLICK_ROUNDS and $MAX_SCROLL_CLICK_ROUNDS rounds." else null
        },
    )
    RoutineKind.PageHelper -> DraftErrors(
        name = nameError(),
        words = if (parseWords(words).isEmpty()) "Add at least one word." else null,
    )
}

private fun RoutineDraft.nameError(): String? = when {
    name.isBlank() -> "Enter a name."
    name.trim().length > MAX_NAME_LENGTH -> "Use $MAX_NAME_LENGTH characters or fewer."
    else -> null
}

private fun RoutineDraft.validateClick(): DraftErrors {
    val intervalMillis = interval.trim().toLongOrNull()?.let { it * intervalUnit.millis }
    return DraftErrors(
        name = nameError(),
        points = when {
            targetMode != TargetMode.AtPoints -> null
            points.isEmpty() -> "Add at least one point."
            points.any { it.x.coordinate() == null || it.y.coordinate() == null } -> "Each point needs a whole-number X and Y."
            else -> null
        },
        interval = when {
            intervalMillis == null -> "Enter a whole number."
            intervalMillis < MIN_INTERVAL_MILLIS -> "Use at least $MIN_INTERVAL_MILLIS milliseconds."
            intervalMillis > MAX_INTERVAL_MILLIS -> "Use 24 hours or less."
            else -> null
        },
        randomExtra = when (randomExtraMillis.trim().toLongOrNull()) {
            null -> "Enter a whole number of milliseconds."
            !in 0..MAX_RANDOM_EXTRA_MILLIS -> "Use between 0 and $MAX_RANDOM_EXTRA_MILLIS."
            else -> null
        },
        limit = when (limitMode) {
            LimitMode.UntilStopped -> null
            LimitMode.Clicks -> limitClicks.trim().toLongOrNull().let { if (it == null || it !in 1..MAX_LIMIT_CLICKS) "Use between 1 and $MAX_LIMIT_CLICKS clicks." else null }
            LimitMode.Minutes -> limitMinutes.trim().toLongOrNull().let { if (it == null || it !in 1..MAX_LIMIT_MINUTES) "Use between 1 and $MAX_LIMIT_MINUTES minutes." else null }
        },
        startDelay = startDelaySeconds.trim().toIntOrNull().let { if (it == null || it !in 0..MAX_START_DELAY_SECONDS) "Use between 0 and $MAX_START_DELAY_SECONDS seconds." else null },
    )
}

/** Builds the routine from a draft that has passed [validate]. */
fun RoutineDraft.toRoutine(id: String): Routine = when (kind) {
    RoutineKind.Click -> toClickRoutine(id)
    RoutineKind.ScrollClick -> ScrollClickRoutine(
        id = id,
        name = name.trim(),
        point = points.first().let { ClickPoint(it.x.trim().toInt(), it.y.trim().toInt()) },
        rounds = rounds.trim().toInt(),
        pixelsPerSecond = pixelsPerSecond.coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND),
        watchVideos = watchVideos,
    )
    RoutineKind.PageHelper -> PageHelperRoutine(
        id = id,
        name = name.trim(),
        words = parseWords(words),
        pixelsPerSecond = pixelsPerSecond.coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND),
        soundOnVideoEnd = soundOnVideoEnd,
    )
}

private fun RoutineDraft.toClickRoutine(id: String): ClickRoutine = ClickRoutine(
    id = id,
    name = name.trim(),
    target = when (targetMode) {
        TargetMode.AtPointer -> ClickTarget.AtPointer
        TargetMode.AtPoints -> ClickTarget.AtPoints(points.map { ClickPoint(it.x.trim().toInt(), it.y.trim().toInt()) })
    },
    button = button,
    clicksPerAction = if (doubleClick) 2 else 1,
    intervalMillis = interval.trim().toLong() * intervalUnit.millis,
    randomExtraMillis = randomExtraMillis.trim().toLong(),
    limit = when (limitMode) {
        LimitMode.UntilStopped -> RunLimit.UntilStopped
        LimitMode.Clicks -> RunLimit.Clicks(limitClicks.trim().toLong())
        LimitMode.Minutes -> RunLimit.Duration(limitMinutes.trim().toLong() * 60_000)
    },
    onlyInApp = onlyInApp,
    startDelaySeconds = startDelaySeconds.trim().toInt(),
)

private fun String.coordinate(): Int? = trim().toIntOrNull()?.takeIf { it in -MAX_COORDINATE..MAX_COORDINATE }
