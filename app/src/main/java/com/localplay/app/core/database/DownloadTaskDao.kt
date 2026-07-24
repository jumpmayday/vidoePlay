package com.localplay.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query(
        """
        SELECT * FROM download_tasks
        WHERE status IN ('QUEUED', 'RUNNING')
        ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END, createdAt ASC
        LIMIT 1
        """
    )
    suspend fun nextActive(): DownloadTaskEntity?

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('QUEUED', 'RUNNING')")
    fun observeActiveCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("SELECT * FROM download_tasks WHERE mediaUrl = :mediaUrl LIMIT 1")
    suspend fun findByMediaUrl(mediaUrl: String): DownloadTaskEntity?
}
