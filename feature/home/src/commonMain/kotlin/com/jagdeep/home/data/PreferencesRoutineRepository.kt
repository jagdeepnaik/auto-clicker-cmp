package com.jagdeep.home.data

import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.RoutineLibrary
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.persistence.preferences.PreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Routines as one JSON document in an ordinary (non-sensitive) preference store: names, screen
 * positions and timings. Writes are serialised. A first launch starts with one ready-to-use routine.
 */
class PreferencesRoutineRepository(
    private val store: PreferencesStore<String>,
    scope: CoroutineScope,
) : RoutineRepository {
    private val lock = Mutex()

    override val library: StateFlow<RoutineLibrary?> = store.data
        .map { decode(it).toLibrary() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun save(routine: ClickRoutine) = update { data ->
        val replaced = data.routines.map { if (it.id == routine.id) routine else it }
        val routines = if (data.routines.any { it.id == routine.id }) replaced else data.routines + routine
        // A new or edited routine is the one the user wants to run next.
        data.copy(routines = routines, selectedId = routine.id)
    }

    override suspend fun delete(id: String) = update { data ->
        data.copy(routines = data.routines.filterNot { it.id == id }, selectedId = data.selectedId.takeIf { it != id })
    }

    override suspend fun select(id: String) = update { it.copy(selectedId = id) }

    private suspend fun update(change: (RoutineData) -> RoutineData) = lock.withLock {
        store.save(json.encodeToString(RoutineData.serializer(), change(decode(store.data.first()))))
    }

    private fun decode(raw: String): RoutineData =
        if (raw.isBlank()) firstLaunch() else runCatching { json.decodeFromString(RoutineData.serializer(), raw) }.getOrDefault(firstLaunch())

    private fun firstLaunch() = RoutineData(routines = listOf(ClickRoutine(id = FIRST_ROUTINE_ID, name = "Click at the pointer")), selectedId = FIRST_ROUTINE_ID)

    @Serializable
    private data class RoutineData(
        val routines: List<ClickRoutine> = emptyList(),
        val selectedId: String? = null,
    ) {
        fun toLibrary() = RoutineLibrary(routines, selectedId)
    }

    private companion object {
        const val FIRST_ROUTINE_ID = "routine-first"
        val json = Json { ignoreUnknownKeys = true }
    }
}
