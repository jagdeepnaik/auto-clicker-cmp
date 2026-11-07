package com.jagdeep.home.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What an import found: the routines to add, and how many were left out because they couldn't be used. */
data class ImportedRoutines(val routines: List<Routine>, val skipped: Int)

/**
 * The routine file format: readable JSON naming itself, with each routine in the same shape the app
 * saves. Reading is strict about what it adds: every routine must pass the same checks as the editor,
 * so a damaged or hand-edited file can't create one the editor would refuse, such as a 0 ms interval.
 */
object RoutineFileCodec {
    const val FORMAT = "autoclicker-routines"
    const val VERSION = 1
    const val FILE_SUFFIX = ".autoclicker.json"

    /** Larger than any real set of routines; stops a stray huge file being read at all. */
    const val MAX_FILE_CHARACTERS = 2_000_000
    private const val MAX_ROUTINES = 1_000

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun encode(routines: List<Routine>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("routines", JsonArray(routines.map { json.encodeToJsonElement(Routine.serializer(), it) }))
        },
    )

    /** Returns null when the text isn't a routine file at all. */
    fun decode(text: String): ImportedRoutines? {
        if (text.length > MAX_FILE_CHARACTERS) return null
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) return null
        val entries = (root["routines"] as? JsonArray) ?: return null
        val usable = entries.take(MAX_ROUTINES).mapNotNull { element ->
            decodeRoutine(element, json)?.takeIf { !it.toDraft().validate().hasAny }
        }
        return ImportedRoutines(usable, skipped = entries.size - usable.size)
    }
}

/** One saved routine; one saved before there were kinds has no type and is a click routine. */
internal fun decodeRoutine(element: JsonElement, json: Json): Routine? {
    val saved = element as? JsonObject ?: return null
    val typed = if ("type" in saved) saved else JsonObject(saved + ("type" to JsonPrimitive(CLICK_ROUTINE_TYPE)))
    return runCatching { json.decodeFromJsonElement(Routine.serializer(), typed) }.getOrNull()
}
