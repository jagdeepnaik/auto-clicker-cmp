package com.jagdeep.persistence.room.transfer

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {
    @Upsert suspend fun upsert(record: TransferHistoryEntity)

    @Query("SELECT * FROM transfer_history ORDER BY completed_at_millis DESC")
    fun observeAll(): Flow<List<TransferHistoryEntity>>

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transfer_history")
    suspend fun deleteAll()
}
