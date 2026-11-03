package com.jagdeep.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

internal var dbPathAndroid: String = ""

fun getRoomDatabaseBuilder(context: Context): RoomDatabase.Builder<AppRoomDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(ROOM_DB_NAME)
    dbPathAndroid = dbFile.absolutePath
    return Room.databaseBuilder<AppRoomDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}

fun initRoomDatabaseForAndroid(context: Context) {
    initRoomDatabase(getRoomDatabaseBuilder(context))
}

actual fun getRoomDatabaseFilePath() = dbPathAndroid