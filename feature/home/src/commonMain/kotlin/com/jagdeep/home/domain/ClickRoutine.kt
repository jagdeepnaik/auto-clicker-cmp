package com.jagdeep.home.domain

import com.jagdeep.automation.PointerButton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The kinds of routine the Routines page can create. Names are saved, so keep them stable. */
enum class RoutineKind(val label: String, val description: String) {
    Click("Click routine", "Clicks where you choose, in any app."),
    ScrollClick("Scroll and click", "Scrolls a Chrome page to the bottom, plays any videos, clicks your spot, and repeats."),
    PageHelper("Page helper", "Highlights the next button on a Chrome page. You press a key to click it."),
}

/** Something the Routines page can save and run. Only one routine runs at a time. */
@Serializable
sealed interface Routine {
    val id: String
    val name: String
    val kind: RoutineKind
}

/** Where a routine clicks. */
@Serializable
sealed interface ClickTarget {
    /** Wherever the pointer is at each click. */
    @Serializable
    @SerialName("pointer")
    data object AtPointer : ClickTarget

    /** Each point in turn, then back to the first. */
    @Serializable
    @SerialName("points")
    data class AtPoints(val points: List<ClickPoint>) : ClickTarget
}

@Serializable
data class ClickPoint(val x: Int, val y: Int)

/** When a routine ends by itself. */
@Serializable
sealed interface RunLimit {
    @Serializable
    @SerialName("none")
    data object UntilStopped : RunLimit

    @Serializable
    @SerialName("clicks")
    data class Clicks(val count: Long) : RunLimit

    @Serializable
    @SerialName("duration")
    data class Duration(val millis: Long) : RunLimit
}

/** Clicks at the pointer or at saved points, in any app. */
@Serializable
@SerialName(CLICK_ROUTINE_TYPE)
data class ClickRoutine(
    override val id: String,
    override val name: String,
    val target: ClickTarget = ClickTarget.AtPointer,
    val button: PointerButton = PointerButton.Left,
    /** 1 for a single click, 2 for a double click. */
    val clicksPerAction: Int = 1,
    val intervalMillis: Long = 1_000,
    /** Up to this much is added to each wait, at random. */
    val randomExtraMillis: Long = 0,
    val limit: RunLimit = RunLimit.UntilStopped,
    /** Clicks only while this app is in front (for example Google Chrome); null means any app. */
    val onlyInApp: String? = null,
    /** Time to get the pointer and the right window in place after pressing Start. */
    val startDelaySeconds: Int = 3,
) : Routine {
    override val kind: RoutineKind get() = RoutineKind.Click
}

/** Scrolls a Chrome page to the bottom, stopping for videos, clicks [point], and repeats. */
@Serializable
@SerialName("scrollClick")
data class ScrollClickRoutine(
    override val id: String,
    override val name: String,
    val point: ClickPoint,
    val rounds: Int = DEFAULT_SCROLL_CLICK_ROUNDS,
    val pixelsPerSecond: Int = DEFAULT_PIXELS_PER_SECOND,
    val watchVideos: Boolean = true,
) : Routine {
    override val kind: RoutineKind get() = RoutineKind.ScrollClick
}

/** Highlights the next button on a Chrome page; the user's key press clicks it. */
@Serializable
@SerialName("pageHelper")
data class PageHelperRoutine(
    override val id: String,
    override val name: String,
    /** Button words in priority order. */
    val words: List<String> = DEFAULT_WORDS,
    val pixelsPerSecond: Int = DEFAULT_PIXELS_PER_SECOND,
    val soundOnVideoEnd: Boolean = true,
) : Routine {
    override val kind: RoutineKind get() = RoutineKind.PageHelper
}

/** Saved before routine types existed, a routine has no type and is a click routine. */
internal const val CLICK_ROUTINE_TYPE = "click"

val DEFAULT_WORDS = listOf("Next", "Continue", "Play", "Watch video")
const val MIN_PIXELS_PER_SECOND = 10
const val MAX_PIXELS_PER_SECOND = 400
const val DEFAULT_PIXELS_PER_SECOND = 60
const val MIN_SCROLL_CLICK_ROUNDS = 1
const val MAX_SCROLL_CLICK_ROUNDS = 9_999
const val DEFAULT_SCROLL_CLICK_ROUNDS = 20

/** "Next, Continue , , Play" becomes the words in that order. */
fun parseWords(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

/** The saved routines and which one Start and the hot key act on. */
data class RoutineLibrary(
    val routines: List<Routine> = emptyList(),
    val selectedId: String? = null,
) {
    /** The selected routine, or the first one when the selection no longer exists. */
    val selected: Routine?
        get() = routines.firstOrNull { it.id == selectedId } ?: routines.firstOrNull()
}

interface RoutineRepository {
    /** Null until the saved routines have been read. */
    val library: StateFlow<RoutineLibrary?>

    suspend fun save(routine: Routine)

    suspend fun delete(id: String)

    suspend fun select(id: String)
}
