package com.localplay.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class DownloadConverters {
    @TypeConverter
    fun toStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(value: DownloadStatus): String = value.name
}

@Database(
    entities = [PlaybackProgressEntity::class, DownloadTaskEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(DownloadConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mediaUrl TEXT NOT NULL,
                        pageUrl TEXT NOT NULL,
                        title TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        isHls INTEGER NOT NULL,
                        treeUri TEXT NOT NULL,
                        status TEXT NOT NULL,
                        downloadedBytes INTEGER NOT NULL,
                        totalBytes INTEGER NOT NULL,
                        hlsSegmentIndex INTEGER NOT NULL,
                        hlsSegmentTotal INTEGER NOT NULL,
                        partialPath TEXT NOT NULL,
                        outputUri TEXT NOT NULL,
                        errorMessage TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_download_tasks_mediaUrl ON download_tasks(mediaUrl)"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "localplay.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
