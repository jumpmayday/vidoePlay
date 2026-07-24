# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")
(JAVA / "core/download").mkdir(parents=True, exist_ok=True)
(JAVA / "core/database").mkdir(parents=True, exist_ok=True)

(JAVA / "core/database/DownloadTaskEntity.kt").write_text(r'''package com.localplay.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED
}

@Entity(
    tableName = "download_tasks",
    indices = [Index(value = ["mediaUrl"], unique = true)]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUrl: String,
    val pageUrl: String,
    val title: String,
    val fileName: String,
    val isHls: Boolean,
    val treeUri: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val hlsSegmentIndex: Int = 0,
    val hlsSegmentTotal: Int = 0,
    val partialPath: String = "",
    val outputUri: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = when {
            isHls && hlsSegmentTotal > 0 ->
                (hlsSegmentIndex.toFloat() / hlsSegmentTotal).coerceIn(0f, 1f)
            totalBytes > 0L ->
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }
}
''', encoding='utf-8', newline='\n')

(JAVA / "core/database/DownloadTaskDao.kt").write_text(r'''package com.localplay.app.core.database

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
''', encoding='utf-8', newline='\n')

print('dao/entity ok')
