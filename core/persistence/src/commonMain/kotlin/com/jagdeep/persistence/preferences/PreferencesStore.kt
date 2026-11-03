package com.jagdeep.persistence.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Reusable asynchronous contract for a small, non-sensitive, typed preference model.
 *
 * Feature-owned models stay in their feature/shared module; this core contract is
 * intentionally independent of any product or feature.
 */
interface PreferencesStore<T> {
    val data: Flow<T>

    suspend fun save(preferences: T)
}

/** Test and unsupported-platform implementation; it does not persist across process restarts. */
class InMemoryPreferencesStore<T>(
    initial: T,
) : PreferencesStore<T> {
    private val mutableData = MutableStateFlow(initial)
    override val data = mutableData.asStateFlow()

    override suspend fun save(preferences: T) {
        mutableData.value = preferences
    }
}

/**
 * Typed adapter over the official KMP AndroidX DataStore Preferences API.
 * Feature modules provide their own model-to-key mapping and never expose keys.
 */
class DataStorePreferencesStore<T>(
    private val dataStore: DataStore<Preferences>,
    private val read: (Preferences) -> T,
    private val write: (value: T, preferences: MutablePreferences) -> Unit,
) : PreferencesStore<T> {
    override val data: Flow<T> = dataStore.data.map(read)

    override suspend fun save(preferences: T) {
        dataStore.edit { mutablePreferences -> write(preferences, mutablePreferences) }
    }
}
