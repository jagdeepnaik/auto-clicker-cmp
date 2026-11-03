package com.jagdeep.persistence.room.common

import com.jagdeep.persistence.room.AppRoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Reusable persisted application-usage counters. */
data class AppUsageStats(val launchCount: Long = 0)

class AppUsageRepository(private val database: AppRoomDatabase) {
    fun observe(): Flow<AppUsageStats> = database.getAppUsageDao().observe().map { entity ->
        AppUsageStats(launchCount = entity?.launchCount ?: 0)
    }

    suspend fun recordLaunch() {
        val dao = database.getAppUsageDao()
        val current = dao.get()
        dao.upsert(AppUsageEntity(launchCount = (current?.launchCount ?: 0) + 1))
    }
}
