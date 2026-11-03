package com.jagdeep.persistence.room.transfer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Non-sensitive, queryable record of a completed or failed transfer. */
@Entity(tableName = "transfer_history")
data class TransferHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "peer_name") val peerName: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "completed_at_millis") val completedAtMillis: Long,
    @ColumnInfo(name = "destination") val destination: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)
