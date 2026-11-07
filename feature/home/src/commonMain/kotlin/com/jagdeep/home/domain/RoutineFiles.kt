package com.jagdeep.home.domain

/**
 * Where exported routines are saved and imported ones are read: the system's own Save and Open
 * dialogs on a computer. Each call returns null when the user cancels.
 */
interface RoutineFiles {
    /** Saves [contents] to a file the user chooses, suggesting [suggestedName]. Returns where, or null if cancelled. */
    suspend fun save(suggestedName: String, contents: String): String?

    /** Reads a file the user chooses. Returns its text, or null if cancelled. */
    suspend fun open(): String?

    /** False where there are no files to choose, such as on a phone. */
    val isAvailable: Boolean get() = true
}

/** For platforms without file dialogs. */
object NoRoutineFiles : RoutineFiles {
    override suspend fun save(suggestedName: String, contents: String): String? = null
    override suspend fun open(): String? = null
    override val isAvailable = false
}

/** Thrown by [RoutineFiles] when a chosen file can't be read or written; the message is for the user. */
class RoutineFileException(message: String) : Exception(message)
