package com.localplay.app.core.database

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
