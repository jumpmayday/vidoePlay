package com.localplay.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.localplay.app.core.database.AppDatabase
import com.localplay.app.core.database.PlaybackProgressEntity
import com.localplay.app.core.scanner.VideoScanner
import com.localplay.app.data.model.FolderGroup
import com.localplay.app.data.model.SortOption
import com.localplay.app.data.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scanner = VideoScanner(appContext)
    private val progressDao = AppDatabase.get(appContext).playbackProgressDao()

    companion object {
        private const val TAG = "VideoRepository"
    }

    private val _folders = MutableStateFlow<List<FolderGroup>>(emptyList())
    val folders: StateFlow<List<FolderGroup>> = _folders.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(VideoScanner.ScanProgress(0, 0))
    val scanProgress: StateFlow<VideoScanner.ScanProgress> = _scanProgress.asStateFlow()

    private var cachedVideos: List<VideoItem> = emptyList()
    private var sortOption: SortOption = SortOption.DATE_MODIFIED
    private var query: String = ""

    suspend fun refresh(force: Boolean = false) {
        if (_scanning.value) return
        _scanning.value = true
        try {
            val progressMap = progressDao.getAll().associateBy { it.path }
            val scanned = scanner.scan { progress ->
                _scanProgress.value = progress
            }
            cachedVideos = scanned.map { video ->
                val progress = progressMap[video.path]
                if (progress == null) {
                    video
                } else {
                    video.copy(
                        progressMs = progress.positionMs,
                        lastPlayedAt = progress.lastPlayedAt
                    )
                }
            }
            rebuildFolders()
        } finally {
            _scanning.value = false
        }
    }

    fun setSort(option: SortOption) {
        sortOption = option
        rebuildFolders()
    }

    fun setQuery(value: String) {
        query = value.trim()
        rebuildFolders()
    }


    fun findByPath(path: String): VideoItem? = cachedVideos.firstOrNull { it.path == path }

    fun findFolder(key: String): FolderGroup? = _folders.value.firstOrNull { it.key == key }

    fun videosInFolder(folderKey: String): List<VideoItem> {
        return cachedVideos.filter { it.folderKey == folderKey }
    }

    fun siblingsInFolder(path: String): List<VideoItem> {
        val current = findByPath(path) ?: return emptyList()
        return cachedVideos
            .filter { it.folderKey == current.folderKey }
            .sortedBy { it.displayName.lowercase() }
    }

    suspend fun saveProgress(
        path: String,
        positionMs: Long,
        durationMs: Long,
        sizeBytes: Long,
        modifiedAt: Long,
        speed: Float
    ) {
        progressDao.upsert(
            PlaybackProgressEntity(
                path = path,
                positionMs = positionMs,
                durationMs = durationMs,
                sizeBytes = sizeBytes,
                modifiedAt = modifiedAt,
                speed = speed,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
        cachedVideos = cachedVideos.map { video ->
            if (video.path == path) {
                video.copy(progressMs = positionMs, lastPlayedAt = System.currentTimeMillis())
            } else {
                video
            }
        }
        rebuildFolders()
    }

    suspend fun clearProgress(path: String) {
        progressDao.deleteByPath(path)
        cachedVideos = cachedVideos.map { video ->
            if (video.path == path) video.copy(progressMs = 0L, lastPlayedAt = 0L) else video
        }
        rebuildFolders()
    }

    suspend fun clearAllProgress() {
        progressDao.clearAll()
        cachedVideos = cachedVideos.map { it.copy(progressMs = 0L, lastPlayedAt = 0L) }
        rebuildFolders()
    }

    suspend fun deleteVideo(video: VideoItem): Boolean {
        val uri = Uri.parse(video.uri)
        return try {
            val deleted = appContext.contentResolver.delete(uri, null, null) > 0
            if (deleted) {
                progressDao.deleteByPath(video.path)
                cachedVideos = cachedVideos.filterNot { it.path == video.path }
                rebuildFolders()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Direct delete may require user confirmation via createDeleteRequest.
                MediaStore.createDeleteRequest(appContext.contentResolver, listOf(uri))
                android.util.Log.i(TAG, "deleteVideo requires system confirmation: ${video.path}")
            }
            deleted
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "deleteVideo security denied: ${video.path}", e)
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "deleteVideo failed: ${video.path}", e)
            false
        }
    }

    suspend fun deleteVideos(videos: List<VideoItem>): Int {
        var deletedCount = 0
        videos.forEach { video ->
            if (deleteVideo(video)) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun rebuildFolders() {
        val filtered = cachedVideos.filter { video ->
            query.isBlank() ||
                video.displayName.contains(query, ignoreCase = true) ||
                video.folderName.contains(query, ignoreCase = true)
        }
        val sorted = when (sortOption) {
            SortOption.NAME -> filtered.sortedBy { it.displayName.lowercase() }
            SortOption.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            SortOption.DURATION -> filtered.sortedByDescending { it.durationMs }
            SortOption.DATE_MODIFIED -> filtered.sortedByDescending { it.dateModified }
        }
        _folders.value = sorted
            .groupBy { it.folderKey }
            .entries
            .sortedBy { (_, videos) -> videos.first().folderName.lowercase() }
            .map { (key, videos) ->
                FolderGroup(
                    key = key,
                    name = videos.first().folderName,
                    videos = videos
                )
            }
    }
}
