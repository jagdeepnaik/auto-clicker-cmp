package com.jagdeep.persistence.room.transfer

import com.jagdeep.persistence.room.AppRoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

enum class TransferDirection { SENT, RECEIVED }
enum class TransferHistoryStatus { COMPLETED, FAILED, REJECTED, CANCELLED }

data class TransferHistoryRecord(
    val id: String,
    val direction: TransferDirection,
    val peerName: String,
    val fileName: String,
    val sizeBytes: Long,
    val status: TransferHistoryStatus,
    val completedAtMillis: Long,
    val destination: String? = null,
    val errorMessage: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(peerName.isNotBlank() && peerName.length <= 80)
        require(fileName.isNotBlank() && fileName.length <= 1_024)
        require(sizeBytes >= 0 && completedAtMillis >= 0)
        require(destination == null || destination.length <= 2_048)
        require(errorMessage == null || errorMessage.length <= 512)
    }
}

/** Room-backed history boundary. Callers must omit source references and other sensitive data. */
interface TransferHistoryStore {
    fun observe(): Flow<List<TransferHistoryRecord>>
    suspend fun record(value: TransferHistoryRecord)
    suspend fun remove(id: String)

    /** Forgets every record; received files stay where they were saved. */
    suspend fun clear()
}

class TransferHistoryRepository(database: AppRoomDatabase) : TransferHistoryStore {
    private val dao = database.getTransferHistoryDao()

    override fun observe(): Flow<List<TransferHistoryRecord>> = dao.observeAll().map { records -> records.map(TransferHistoryEntity::toRecord) }
    override suspend fun record(value: TransferHistoryRecord) = dao.upsert(value.toEntity())
    override suspend fun remove(id: String) = dao.delete(id)
    override suspend fun clear() = dao.deleteAll()
}

class UnavailableTransferHistoryStore : TransferHistoryStore {
    override fun observe(): Flow<List<TransferHistoryRecord>> = emptyFlow()
    override suspend fun record(value: TransferHistoryRecord) = Unit
    override suspend fun remove(id: String) = Unit
    override suspend fun clear() = Unit
}

private fun TransferHistoryRecord.toEntity() = TransferHistoryEntity(
    id, direction.name, peerName, fileName, sizeBytes, status.name, completedAtMillis, destination, errorMessage,
)

private fun TransferHistoryEntity.toRecord() = TransferHistoryRecord(
    id = id,
    direction = TransferDirection.valueOf(direction),
    peerName = peerName,
    fileName = fileName,
    sizeBytes = sizeBytes,
    status = TransferHistoryStatus.valueOf(status),
    completedAtMillis = completedAtMillis,
    destination = destination,
    errorMessage = errorMessage,
)
