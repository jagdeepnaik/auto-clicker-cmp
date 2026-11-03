package com.jagdeep.persistence.room

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jagdeep.common.config.AppConfig
import com.jagdeep.logger.Log
import com.jagdeep.persistence.room.common.AppUsageDao
import com.jagdeep.persistence.room.common.AppUsageEntity
import com.jagdeep.persistence.room.transfer.TransferHistoryDao
import com.jagdeep.persistence.room.transfer.TransferHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal const val ROOM_DB_NAME = "${AppConfig.APP_NAME_LOWERCASE}.db"

@Database(
    entities = [
        AppUsageEntity::class,
        TransferHistoryEntity::class,
    ],
    version = 1,
)
@ConstructedBy(AppRoomDatabaseConstructor::class)
abstract class AppRoomDatabase : RoomDatabase() {
    abstract fun getAppUsageDao(): AppUsageDao
    abstract fun getTransferHistoryDao(): TransferHistoryDao
}

// The Room compiler generates the `actual` implementations.
@Suppress("KotlinNoActualForExpect")
expect object AppRoomDatabaseConstructor : RoomDatabaseConstructor<AppRoomDatabase> {
    override fun initialize(): AppRoomDatabase
}

fun initRoomDatabase(builder: RoomDatabase.Builder<AppRoomDatabase>) {
    RoomDatabaseHolder.database = builder
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                super.onCreate(connection)
                Log.i("InitRoomDatabase : onCreate", "InitRoomDatabase")
            }

            override fun onOpen(connection: SQLiteConnection) {
                super.onOpen(connection)
                RoomDatabaseHolder.databaseOnOpen = true
                Log.i("InitRoomDatabase : onOpen", "InitRoomDatabase")
                Log.i(
                    "InitRoomDatabase : Name: ${RoomDatabaseHolder.DATABASE_NAME}, Path: ${RoomDatabaseHolder.databaseFilePath}",
                    "InitRoomDatabase"
                )
            }

            override fun onDestructiveMigration(connection: SQLiteConnection) {
                super.onDestructiveMigration(connection)
                Log.i("InitRoomDatabase : onDestructiveMigration", "InitRoomDatabase")
            }
        })
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

object RoomDatabaseHolder {
    const val DATABASE_NAME = ROOM_DB_NAME
    var database: AppRoomDatabase? = null
    var databaseOnOpen: Boolean = false
    val databaseFilePath = getRoomDatabaseFilePath()
}

expect fun getRoomDatabaseFilePath(): String


/*** Room Docs - 05-12-2025

Note :- Minification and obfuscation
If the project is minified or obfuscated then you must include the following ProGuard rule
so that Room can find the generated implementation of the database definition:

-keep class * extends androidx.room.RoomDatabase { <init>(); }

------------------------------------------------------------------------------------------

When you declare an expect object with the interface RoomDatabaseConstructor, the Room compiler
generates the actual implementations. Android Studio might issue the following warning, which
you can suppress with @Suppress("KotlinNoActualForExpect"):

------------------------------------------------------------------------------------------

Select a SQLite driver
The previous code snippet calls the setDriver builder function to define what SQLite driver
the Room database should use. These drivers differ based on the target platform. The previous
code snippets use the BundledSQLiteDriver. This is the recommended driver that includes SQLite
compiled from source, which provides the most consistent and up-to-date version of SQLite
across all platforms.

If you want to use the OS-provided SQLite, use the setDriver API in the platform-specific
source sets that specify a platform-specific driver. See Driver implementations for
descriptions of available driver implementations. You can use either of the following:

AndroidSQLiteDriver in androidMain
NativeSQLiteDriver in iosMain
To use NativeSQLiteDriver, you need to provide a linker option -lsqlite3 so that the
iOS app dynamically links with the system SQLite.
 ***/
