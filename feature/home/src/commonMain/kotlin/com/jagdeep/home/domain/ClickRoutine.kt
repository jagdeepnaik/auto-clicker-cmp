package com.jagdeep.home.domain

import com.jagdeep.automation.PointerButton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/** A saved set of clicking instructions. */
@Serializable
data class ClickRoutine(
    val id: String,
    val name: String,
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
)

/** The saved routines and which one Start and the hot key act on. */
data class RoutineLibrary(
    val routines: List<ClickRoutine> = emptyList(),
    val selectedId: String? = null,
) {
    /** The selected routine, or the first one when the selection no longer exists. */
    val selected: ClickRoutine?
        get() = routines.firstOrNull { it.id == selectedId } ?: routines.firstOrNull()
}

interface RoutineRepository {
    /** Null until the saved routines have been read. */
    val library: StateFlow<RoutineLibrary?>

    suspend fun save(routine: ClickRoutine)

    suspend fun delete(id: String)

    suspend fun select(id: String)
}
