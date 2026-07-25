package com.localplay.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.localplay.app.core.database.AppDatabase
import com.localplay.app.core.database.PlaybackProgressEntity
import com.localplay.app.core.download.DownloadPlayback
import com.localplay.app.core.scanner.VideoScanner
import com.localplay.app.data.model.FolderGroup
import com.localplay.app.data.model.SortOption
import com.localplay.app.data.model.VideoItem
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VideoRepository(
    context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val appContext = context.applicationContext
    private val scanner = VideoScanner(appContext)
    private val db = AppDatabase.get(appContext)
    private val progressDao = db.playbackProgressDao()
    private val downloadDao = db.downloadTaskDao()
    private val refreshMutex = Mutex()

    @Volatile
    private var pendingForceRefresh = false

    private val _folders = MutableStateFlow<List<FolderGroup>>(emptyList())
    val folders: StateFlow<List<FolderGroup>> = _folders.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(VideoScanner.ScanProgress(0, 0))
    val scanProgress: StateFlow<VideoScanner.ScanProgress> = _scanProgress.asStateFlow()

    private var cachedVideos: List<VideoItem> = emptyList()
    private var sortOption: SortOption = SortOption.DATE_MODIFIED
    private var query: String = ""

    /**
     * App launch: scan at least once per calendar day (and when cache is empty).
     */
    suspend fun refreshOnLaunch() {
        val today = LocalDate.now().toString()
        val settings = settingsRepository.settings.first()
        if (settings.lastScanDay != today || cachedVideos.isEmpty()) {
            refresh(force = true)
        }
    }

    /**
     * Manual / forced rescan of MediaStore.
     * Concurrent force requests are queued so the refresh button is never dropped.
     */
    suspend fun refresh(force: Boolean = false) {
        if (_scanning.value) {
            if (force) {
                pendingForceRefresh = true
            }
            return
        }
        doRefresh()
        while (pendingForceRefresh) {
            pendingForceRefresh = false
            doRefresh()
        }
    }

    private suspend fun doRefresh() {
        refreshMutex.withLock {
            _scanning.value = true
            try {
                val settings = settingsRepository.settings.first()
                val minSizeBytes = settings.minSizeMb.toLong().coerceAtLeast(0L) * 1024L * 1024L
                val minDurationMs = settings.minDurationSec.toLong().coerceAtLeast(0L) * 1000L
                val progressMap = progressDao.getAll().associateBy { it.path }
                val scanned = scanner.scan(
                    minSizeBytes = minSizeBytes,
                    minDurationMs = minDurationMs
                ) { progress ->
                    _scanProgress.value = progress
                }
                val merged = mergeCompletedDownloads(scanned)
                cachedVideos = merged.map { video ->
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
                settingsRepository.setLastScanDay(LocalDate.now().toString())
                Log.i(TAG, "scan done: ${cachedVideos.size} videos")
            } catch (e: Exception) {
                Log.e(TAG, "scan failed", e)
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * Force library refresh after a download completes (immediate + delayed for MediaStore probe).
     */
    suspend fun refreshAfterDownload() {
        refresh(force = true)
        delay(1_500L)
        refresh(force = true)
    }

    private suspend fun mergeCompletedDownloads(scanned: List<VideoItem>): List<VideoItem> {
        val completed = downloadDao.getCompleted()
        if (completed.isEmpty()) return scanned
        val existingUris = scanned.map { it.uri }.toHashSet()
        val existingNames = scanned.map { it.displayName.lowercase() }.toHashSet()
        val extras = completed.mapNotNull { task ->
            if (task.outputUri.isBlank()) return@mapNotNull null
            if (task.outputUri in existingUris) return@mapNotNull null
            val name = task.title.ifBlank { task.fileName }
            // Avoid duplicate cards when MediaStore already indexed same file under another URI.
            if (name.lowercase() in existingNames &&
                scanned.any { it.folderName.equals("LocalPlay", true) || it.path.contains("/LocalPlay/", true) }
            ) {
                // Still skip only exact filename match in LocalPlay.
                if (scanned.any { it.displayName.equals(task.fileName, true) || it.displayName.equals(name, true) }) {
                    return@mapNotNull null
                }
            }
            VideoItem(
                id = -task.id,
                uri = task.outputUri,
                path = DownloadPlayback.pathFor(task.id),
                displayName = name,
                folderName = DownloadPlayback.FOLDER_NAME,
                folderKey = DownloadPlayback.FOLDER_KEY,
                durationMs = 0L,
                sizeBytes = task.downloadedBytes.coerceAtLeast(0L),
                width = 0,
                height = 0,
                mimeType = if (task.isHls) "video/mp2t" else "video/mp4",
                dateModified = task.updatedAt
            )
        }
        return scanned + extras
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

    /**
     * Resolve a playable item for library paths or download://{taskId} (边下边播 / 已完成).
     */
    suspend fun resolvePlayable(path: String): VideoItem? {
        val taskId = DownloadPlayback.taskIdFromPath(path)
        if (taskId != null) {
            val task = downloadDao.getById(taskId) ?: return findByPath(path)
            val item = DownloadPlayback.toVideoItem(appContext, task)
            val progress = progressDao.getAll().firstOrNull { it.path == path }
            val withProgress = if (progress == null) {
                item
            } else {
                item.copy(progressMs = progress.positionMs, lastPlayedAt = progress.lastPlayedAt)
            }
            upsertCached(withProgress)
            return withProgress
        }
        return findByPath(path)
    }

    fun registerPlayable(item: VideoItem) {
        upsertCached(item)
    }

    private fun upsertCached(item: VideoItem) {
        cachedVideos = listOf(item) + cachedVideos.filterNot { it.path == item.path }
        rebuildFolders()
    }

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
                MediaStore.createDeleteRequest(appContext.contentResolver, listOf(uri))
                Log.i(TAG, "deleteVideo requires system confirmation: ${video.path}")
            }
            deleted
        } catch (e: SecurityException) {
            Log.w(TAG, "deleteVideo security denied: ${video.path}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "deleteVideo failed: ${video.path}", e)
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

    sealed class DeleteResult {
        data class Completed(val deleted: Int) : DeleteResult()
        data class NeedsPermission(
            val intentSender: android.content.IntentSender,
            val videos: List<VideoItem>
        ) : DeleteResult()
    }

    /**
     * Delete media, falling back to a system confirmation dialog for files this app does not
     * own (required on Android 10+). Directly-deletable files (e.g. app downloads) are removed
     * immediately; the rest are returned as an [IntentSender] the UI must launch.
     */
    suspend fun deleteVideosWithPrompt(videos: List<VideoItem>): DeleteResult {
        if (videos.isEmpty()) return DeleteResult.Completed(0)
        val resolver = appContext.contentResolver
        val needsPermission = mutableListOf<VideoItem>()
        val recoverableSenders = mutableListOf<android.content.IntentSender>()
        var deleted = 0

        videos.forEach { video ->
            val uri = try {
                Uri.parse(video.uri)
            } catch (_: Exception) {
                null
            }
            if (uri == null) {
                removeFromCache(video.path)
                return@forEach
            }
            try {
                if (resolver.delete(uri, null, null) > 0) {
                    progressDao.deleteByPath(video.path)
                    removeFromCache(video.path)
                    deleted++
                } else {
                    needsPermission += video
                }
            } catch (security: SecurityException) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                    security is android.app.RecoverableSecurityException
                ) {
                    recoverableSenders += security.userAction.actionIntent.intentSender
                    needsPermission += video
                } else {
                    needsPermission += video
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteVideosWithPrompt failed: ${video.path}", e)
            }
        }

        if (deleted > 0) rebuildFolders()

        if (needsPermission.isEmpty()) {
            return DeleteResult.Completed(deleted)
        }

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val uris = needsPermission.mapNotNull {
                    try {
                        Uri.parse(it.uri)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (uris.isEmpty()) {
                    DeleteResult.Completed(deleted)
                } else {
                    val pending = MediaStore.createDeleteRequest(resolver, uris)
                    DeleteResult.NeedsPermission(pending.intentSender, needsPermission)
                }
            }
            recoverableSenders.isNotEmpty() ->
                DeleteResult.NeedsPermission(recoverableSenders.first(), needsPermission)
            else -> DeleteResult.Completed(deleted)
        }
    }

    /** Finalize deletions the user approved via the system dialog. */
    suspend fun onSystemDeleteConfirmed(videos: List<VideoItem>) {
        videos.forEach { video ->
            progressDao.deleteByPath(video.path)
            removeFromCache(video.path)
        }
        rebuildFolders()
        // Re-sync with MediaStore so any partially-applied deletions are reflected.
        refresh(force = true)
    }

    private fun removeFromCache(path: String) {
        cachedVideos = cachedVideos.filterNot { it.path == path }
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

    companion object {
        private const val TAG = "VideoRepository"
    }
}
