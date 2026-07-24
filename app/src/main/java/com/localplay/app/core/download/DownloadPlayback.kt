package com.localplay.app.core.download

import android.content.Context
import com.localplay.app.core.database.DownloadStatus
import com.localplay.app.core.database.DownloadTaskEntity
import com.localplay.app.data.model.VideoItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object DownloadPlayback {
    const val PATH_PREFIX = "download://"
    const val FOLDER_KEY = "folder:downloads"
    const val FOLDER_NAME = "下载"
    private const val MIN_PARTIAL_BYTES = 64L * 1024L
    private val refererByPath = ConcurrentHashMap<String, String>()
    private val growingFileByPath = ConcurrentHashMap<String, File>()
    private val mediaUrlByPath = ConcurrentHashMap<String, String>()

    fun pathFor(taskId: Long): String = PATH_PREFIX + taskId

    fun taskIdFromPath(path: String): Long? {
        if (!path.startsWith(PATH_PREFIX)) return null
        return path.removePrefix(PATH_PREFIX).toLongOrNull()
    }

    fun refererForPath(path: String): String? = refererByPath[path]

    fun growingFileFor(path: String): File? = growingFileByPath[path]

    fun mediaUrlFor(path: String): String? = mediaUrlByPath[path]

    fun canPlayWhileDownloading(task: DownloadTaskEntity): Boolean {
        return when (task.status) {
            DownloadStatus.RUNNING, DownloadStatus.PAUSED, DownloadStatus.QUEUED ->
                task.mediaUrl.isNotBlank()
            DownloadStatus.COMPLETED ->
                task.outputUri.isNotBlank() || task.mediaUrl.isNotBlank()
            DownloadStatus.FAILED -> task.mediaUrl.isNotBlank()
        }
    }

    /**
     * Incomplete: prefer local growing partial (边下边播追文件增长);
     * if partial too small, fall back to remote mediaUrl.
     * Completed: prefer MediaStore / outputUri.
     */
    fun resolvePlayUri(context: Context, task: DownloadTaskEntity): String {
        val path = pathFor(task.id)
        mediaUrlByPath[path] = task.mediaUrl
        growingFileByPath.remove(path)

        if (task.status == DownloadStatus.COMPLETED && task.outputUri.isNotBlank()) {
            return task.outputUri
        }

        val partial = task.partialPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (partial != null && partial.exists() && partial.length() >= MIN_PARTIAL_BYTES) {
            growingFileByPath[path] = partial
            // File path for GrowingFileDataSource (not FileProvider content uri)
            return UriFile.asUri(partial)
        }

        // No local bytes yet — stream remote while download catches up / starts writing.
        return task.mediaUrl
    }

    fun toVideoItem(context: Context, task: DownloadTaskEntity): VideoItem {
        val path = pathFor(task.id)
        refererOf(task)?.let { refererByPath[path] = it }
        val playUri = resolvePlayUri(context, task)
        val growing = growingFileByPath.containsKey(path)
        return VideoItem(
            id = -task.id,
            uri = playUri,
            path = path,
            displayName = task.title.ifBlank { task.fileName },
            folderName = FOLDER_NAME,
            folderKey = FOLDER_KEY,
            durationMs = 0L,
            sizeBytes = task.downloadedBytes.coerceAtLeast(0L),
            width = 0,
            height = 0,
            mimeType = when {
                growing -> "video/mp4" // container probed from stream; TS also ok via extractor
                task.isHls || playUri.contains(".m3u8", true) -> "application/x-mpegURL"
                playUri.contains(".webm", true) -> "video/webm"
                else -> "video/mp4"
            },
            dateModified = task.updatedAt
        )
    }

    fun refererOf(task: DownloadTaskEntity): String? =
        task.pageUrl.takeIf { it.startsWith("http") }

    private object UriFile {
        fun asUri(file: File): String = android.net.Uri.fromFile(file).toString()
    }
}
