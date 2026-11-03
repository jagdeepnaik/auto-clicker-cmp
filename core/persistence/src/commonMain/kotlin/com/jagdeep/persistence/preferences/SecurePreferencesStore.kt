package com.jagdeep.persistence.preferences

import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for a small typed model that contains secrets.
 *
 * Implementations must use platform secure storage. There is intentionally no
 * common in-memory or ordinary-preferences fallback: callers must provide a
 * platform implementation or remain unavailable until secure storage is ready.
 */
interface SecurePreferencesStore<T> {
    val data: Flow<T?>

    suspend fun save(value: T)

    suspend fun clear()
}
