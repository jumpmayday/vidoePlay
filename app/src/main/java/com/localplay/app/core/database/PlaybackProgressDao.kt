package com.localplay.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress")
    suspend fun getAll(): List<PlaybackProgressEntity>

    @Query("SELECT * FROM playback_progress WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM playback_progress")
    suspend fun clearAll()
}
