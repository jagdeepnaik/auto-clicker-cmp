package com.jagdeep.activity.data

import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Past runs as one JSON document in an ordinary preference store; keeps only the newest ones. */
class PreferencesRunHistoryStore(
    private val store: PreferencesStore<String>,
    private val maxRecords: Int = MAX_RECORDS,
) : RunHistoryStore {
    private val lock = Mutex()

    override val records: Flow<List<RunRecord>> = store.data.map(::decode)

    override suspend fun add(record: RunRecord) = lock.withLock {
        store.save(encode((listOf(record) + decode(store.data.first())).take(maxRecords)))
    }

    override suspend fun clear() = lock.withLock { store.save(encode(emptyList())) }

    private fun encode(records: List<RunRecord>): String = json.encodeToString(serializer, records)

    private fun decode(raw: String): List<RunRecord> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    private companion object {
        const val MAX_RECORDS = 200
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(RunRecord.serializer())
    }
}
