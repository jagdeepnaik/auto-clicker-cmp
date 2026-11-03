package com.jagdeep.persistence.room

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

internal var dbPathIos: String = ""

fun getRoomDatabaseBuilder(): RoomDatabase.Builder<AppRoomDatabase> {
    val dbFilePath = documentDirectory() + "/" + ROOM_DB_NAME
    dbPathIos = dbFilePath
    return Room.databaseBuilder<AppRoomDatabase>(
        name = dbFilePath,
    )
}

fun initRoomDatabaseForIos() {
    initRoomDatabase(getRoomDatabaseBuilder())
}

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}

actual fun getRoomDatabaseFilePath() = dbPathIos