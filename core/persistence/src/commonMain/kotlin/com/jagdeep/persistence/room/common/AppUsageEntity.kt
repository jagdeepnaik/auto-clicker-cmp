package com.jagdeep.persistence.room.common

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    @ColumnInfo(name = "launch_count") val launchCount: Long = 0,
) {
    companion object { const val SINGLETON_ID = "app_usage" }
}
