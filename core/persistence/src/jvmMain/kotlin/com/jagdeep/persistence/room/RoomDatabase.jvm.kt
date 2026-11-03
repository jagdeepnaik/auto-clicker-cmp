package com.jagdeep.persistence.room

import androidx.room.Room
import androidx.room.RoomDatabase
import com.jagdeep.common.config.AppConfig
import java.io.File

internal var dbPathJvm: String = ""

fun getRoomDatabaseBuilder(): RoomDatabase.Builder<AppRoomDatabase> {
    // val dbFile = File(System.getProperty("java.io.tmpdir"), DB_NAME)
    val baseDir = getAppDataDirectory()
    val dbFile = File(baseDir, ROOM_DB_NAME)
    dbPathJvm = dbFile.absolutePath
    return Room.databaseBuilder<AppRoomDatabase>(
        name = dbFile.absolutePath,
    )
}

fun initRoomDatabaseForJvm() {
    initRoomDatabase(getRoomDatabaseBuilder())
}

private fun getAppDataDirectory(): File {
    val os = System.getProperty("os.name").lowercase()

    val basePath = when {
        os.contains("mac") ->
            File(
                System.getProperty("user.home"),
                "Library/Application Support/" + AppConfig.APP_NAME
            )

        os.contains("win") ->
            File(System.getenv("APPDATA"), AppConfig.APP_NAME)

        else -> // Linux / others
            File(System.getProperty("user.home"), ".local/share/" + AppConfig.APP_NAME)
    }

    if (!basePath.exists()) basePath.mkdirs()
    return basePath
}

actual fun getRoomDatabaseFilePath() = dbPathJvm