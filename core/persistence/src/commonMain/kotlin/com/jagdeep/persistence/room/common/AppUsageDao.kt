package com.jagdeep.persistence.room.common

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {
    @Upsert suspend fun upsert(usage: AppUsageEntity)
    @Query("SELECT * FROM app_usage WHERE id = :id LIMIT 1") fun observe(id: String = AppUsageEntity.SINGLETON_ID): Flow<AppUsageEntity?>
    @Query("SELECT * FROM app_usage WHERE id = :id LIMIT 1") suspend fun get(id: String = AppUsageEntity.SINGLETON_ID): AppUsageEntity?
}
