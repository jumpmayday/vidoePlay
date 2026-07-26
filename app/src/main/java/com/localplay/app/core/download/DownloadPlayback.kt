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

    fun hlsIndexFile(context: Context, taskId: Long): File =
        File(context.applicationContext.filesDir, "hls_downloads/$taskId/index.m3u8")

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
     * HLS: always prefer local decrypted m3u8 package (handles discontinuities).
     * While downloading HLS, stream the remote playlist instead of the growing concat
     * (concatenated .ts often stops around the first PCR break ~17s).
     * Progressive: prefer local growing partial when large enough.
     */
    fun resolvePlayUri(context: Context, task: DownloadTaskEntity): String {
        val path = pathFor(task.id)
        mediaUrlByPath[path] = task.mediaUrl
        growingFileByPath.remove(path)

        if (task.isHls) {
            val index = hlsIndexFile(context, task.id)
            if (index.exists() && index.length() > 0L) {
                return UriFile.asUri(index)
            }
            // In-progress / incomplete: play remote HLS (ExoPlayer handles discontinuities).
            return task.mediaUrl
        }

        if (task.status == DownloadStatus.COMPLETED && task.outputUri.isNotBlank()) {
            return task.outputUri
        }

        val partial = task.partialPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (partial != null && partial.exists() && partial.length() >= MIN_PARTIAL_BYTES) {
            growingFileByPath[path] = partial
            return UriFile.asUri(partial)
        }

        return task.mediaUrl
    }

    fun toVideoItem(context: Context, task: DownloadTaskEntity): VideoItem {
        val path = pathFor(task.id)
        refererOf(task)?.let { refererByPath[path] = it }
        val playUri = resolvePlayUri(context, task)
        val growing = growingFileByPath.containsKey(path)
        val localHls = task.isHls && playUri.contains(".m3u8", ignoreCase = true) &&
            !playUri.startsWith("http")
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
                localHls || (task.isHls && playUri.contains(".m3u8", true)) ->
                    "application/x-mpegURL"
                growing -> "video/mp4"
                task.isHls -> "video/mp2t"
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
