package com.localplay.app.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import com.localplay.app.core.database.AppDatabase
import com.localplay.app.core.database.DownloadStatus
import com.localplay.app.core.database.DownloadTaskEntity
import com.localplay.app.core.download.DownloadService
import com.localplay.app.core.download.ResumableDownloadEngine
import com.localplay.app.core.sniff.SniffedVideo
import kotlinx.coroutines.flow.Flow

class DownloadRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).downloadTaskDao()
    private val engine = ResumableDownloadEngine(appContext)

    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()
    val activeCount: Flow<Int> = dao.observeActiveCount()

    fun defaultDirPath(): String = engine.defaultDirPath()

    suspend fun enqueue(videos: List<SniffedVideo>, treeUri: String?): Int {
        var added = 0
        videos.forEach { video ->
            val existing = dao.findByMediaUrl(video.mediaUrl)
            if (existing != null) {
                when (existing.status) {
                    DownloadStatus.COMPLETED -> Unit
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> Unit
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                        dao.update(
                            existing.copy(
                                status = DownloadStatus.QUEUED,
                                errorMessage = "",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        added++
                    }
                }
                return@forEach
            }
            val id = dao.insert(
                DownloadTaskEntity(
                    mediaUrl = video.mediaUrl,
                    pageUrl = video.pageUrl,
                    title = video.title,
                    fileName = video.suggestedFileName,
                    isHls = video.isHls,
                    treeUri = treeUri.orEmpty(),
                    status = DownloadStatus.QUEUED
                )
            )
            if (id > 0L) added++
        }
        if (added > 0) startService()
        return added
    }

    suspend fun pause(id: Long) {
        val task = dao.getById(id) ?: return
        if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
            dao.update(
                task.copy(
                    status = DownloadStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            val intent = Intent(appContext, DownloadService::class.java).apply {
                action = DownloadService.ACTION_PAUSE
                putExtra(DownloadService.EXTRA_TASK_ID, id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }
    }

    suspend fun resume(id: Long) {
        val task = dao.getById(id) ?: return
        if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
            dao.update(
                task.copy(
                    status = DownloadStatus.QUEUED,
                    errorMessage = "",
                    updatedAt = System.currentTimeMillis()
                )
            )
            startService()
        }
    }

    /**
     * Discard partial progress and enqueue from the beginning.
     */
    suspend fun restart(id: Long) {
        val task = dao.getById(id) ?: return
        if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
            pause(id)
        }
        if (task.partialPath.isNotBlank()) {
            runCatching { java.io.File(task.partialPath).delete() }
        }
        // Also clear any leftover part file by id pattern.
        runCatching {
            val dir = java.io.File(appContext.filesDir, "download_partials")
            dir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
        }
        dao.update(
            task.copy(
                status = DownloadStatus.QUEUED,
                downloadedBytes = 0L,
                totalBytes = -1L,
                hlsSegmentIndex = 0,
                hlsSegmentTotal = 0,
                partialPath = "",
                outputUri = "",
                errorMessage = "",
                updatedAt = System.currentTimeMillis()
            )
        )
        startService()
    }

    suspend fun cancel(id: Long) {
        val task = dao.getById(id) ?: return
        if (task.partialPath.isNotBlank()) {
            runCatching { java.io.File(task.partialPath).delete() }
        }
        dao.deleteById(id)
    }

    suspend fun clearCompleted() {
        val completed = dao.getCompleted()
        completed.forEach { task ->
            if (task.partialPath.isNotBlank()) {
                runCatching { java.io.File(task.partialPath).delete() }
            }
        }
        dao.clearCompleted()
    }

    suspend fun getTask(id: Long): DownloadTaskEntity? = dao.getById(id)

    fun startService() {
        val intent = Intent(appContext, DownloadService::class.java).apply {
            action = DownloadService.ACTION_PROCESS_QUEUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }
}
