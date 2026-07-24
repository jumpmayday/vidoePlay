package com.localplay.app.core.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.localplay.app.core.database.DownloadStatus
import com.localplay.app.core.database.DownloadTaskEntity
import com.localplay.app.core.sniff.ChallengedHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadAbortedException : Exception("download aborted")

/**
 * Downloads into a local partial file with resume support, then publishes to the final destination.
 */
class ResumableDownloadEngine(
    private val context: Context,
    private val http: ChallengedHttpClient = ChallengedHttpClient()
) {
    fun ensurePartialFile(task: DownloadTaskEntity): File {
        if (task.partialPath.isNotBlank()) {
            val existing = File(task.partialPath)
            existing.parentFile?.mkdirs()
            return existing
        }
        val dir = File(context.filesDir, "download_partials").apply { mkdirs() }
        return File(dir, "${task.id}_${safeName(task.fileName)}.part")
    }

    fun download(
        task: DownloadTaskEntity,
        shouldAbort: () -> Boolean = { false },
        onProgress: (DownloadTaskEntity) -> Unit
    ): DownloadTaskEntity {
        return if (task.isHls) {
            downloadHls(task, shouldAbort, onProgress)
        } else {
            downloadProgressive(task, shouldAbort, onProgress)
        }
    }

    private fun checkAbort(shouldAbort: () -> Boolean) {
        if (shouldAbort()) throw DownloadAbortedException()
    }


    private fun downloadProgressive(
        task: DownloadTaskEntity,
        shouldAbort: () -> Boolean,
        onProgress: (DownloadTaskEntity) -> Unit
    ): DownloadTaskEntity {
        val partial = ensurePartialFile(task)
        var current = task.copy(
            partialPath = partial.absolutePath,
            status = DownloadStatus.RUNNING,
            updatedAt = System.currentTimeMillis()
        )
        onProgress(current)

        var offset = if (partial.exists()) partial.length().coerceAtLeast(0L) else 0L
        if (offset > 0L) {
            current = current.copy(downloadedBytes = offset)
            onProgress(current)
        }

        var restarted = false
        while (true) {
            checkAbort(shouldAbort)
            FileOutputStream(partial, offset > 0L).use { output ->
                val result = http.copyTo(
                    url = current.mediaUrl,
                    output = output,
                    referer = current.pageUrl.ifBlank { null },
                    startByte = offset
                ) { absolute, total ->
                    checkAbort(shouldAbort)
                    current = current.copy(
                        downloadedBytes = absolute,
                        totalBytes = total ?: current.totalBytes,
                        updatedAt = System.currentTimeMillis()
                    )
                    onProgress(current)
                }
                output.flush()

                if (offset > 0L && result.httpCode == 200 && !restarted) {
                    restarted = true
                } else {
                    current = current.copy(
                        downloadedBytes = if (result.resumed) {
                            offset + result.bytesWritten
                        } else {
                            result.bytesWritten
                        },
                        totalBytes = result.totalSize ?: current.totalBytes
                    )
                    restarted = false
                }
            }
            if (restarted) {
                partial.delete()
                offset = 0L
                current = current.copy(downloadedBytes = 0L, totalBytes = -1L)
                onProgress(current)
                continue
            }
            break
        }

        val outputUri = publishFinal(partial, current.fileName, current.treeUri, mimeFor(current))
        // Keep partial while player may still be reading the growing file; cleaned on restart/cancel.
        return current.copy(
            status = DownloadStatus.COMPLETED,
            outputUri = outputUri.toString(),
            partialPath = partial.absolutePath,
            updatedAt = System.currentTimeMillis(),
            errorMessage = ""
        )
    }

    private fun downloadHls(
        task: DownloadTaskEntity,
        shouldAbort: () -> Boolean,
        onProgress: (DownloadTaskEntity) -> Unit
    ): DownloadTaskEntity {
        var current = task.copy(status = DownloadStatus.RUNNING, updatedAt = System.currentTimeMillis())
        onProgress(current)

        val mediaPlaylistUrl = resolveMediaPlaylist(current.mediaUrl, current.pageUrl)
        val playlist = http.getText(mediaPlaylistUrl, referer = current.pageUrl.ifBlank { null })
        val segments = parseSegments(mediaPlaylistUrl, playlist)
        if (segments.isEmpty()) {
            throw IllegalStateException("m3u8 未找到分片")
        }

        val partial = ensurePartialFile(current)
        current = current.copy(
            partialPath = partial.absolutePath,
            hlsSegmentTotal = segments.size,
            hlsSegmentIndex = current.hlsSegmentIndex.coerceIn(0, segments.size)
        )
        onProgress(current)

        // If restarting HLS from non-zero without file, reset.
        if (current.hlsSegmentIndex > 0 && !partial.exists()) {
            current = current.copy(hlsSegmentIndex = 0, downloadedBytes = 0L)
        }
        if (current.hlsSegmentIndex == 0 && partial.exists()) {
            partial.delete()
        }

        FileOutputStream(partial, current.hlsSegmentIndex > 0).use { output ->
            for (index in current.hlsSegmentIndex until segments.size) {
                checkAbort(shouldAbort)
                http.copyTo(
                    url = segments[index],
                    output = output,
                    referer = current.pageUrl.ifBlank { null },
                    startByte = 0L
                )
                output.flush()
                current = current.copy(
                    hlsSegmentIndex = index + 1,
                    downloadedBytes = partial.length(),
                    updatedAt = System.currentTimeMillis()
                )
                onProgress(current)
            }
        }

        val outputUri = publishFinal(partial, current.fileName, current.treeUri, "video/mp2t")
        // Keep partial for in-flight 边下边播 readers; cleaned on restart/cancel.
        return current.copy(
            status = DownloadStatus.COMPLETED,
            outputUri = outputUri.toString(),
            partialPath = partial.absolutePath,
            hlsSegmentIndex = segments.size,
            hlsSegmentTotal = segments.size,
            updatedAt = System.currentTimeMillis(),
            errorMessage = ""
        )
    }

    private fun publishFinal(
        partial: File,
        fileName: String,
        treeUri: String,
        mime: String
    ): Uri {
        // Always index into MediaStore so home library can discover the file.
        val mediaStoreUri = publishToMediaStore(partial, fileName, mime)

        if (treeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    ?: throw IllegalStateException("下载目录不可用，请在设置中重新选择")
                tree.findFile(fileName)?.delete()
                val created = tree.createFile(mime, fileName)
                    ?: throw IllegalStateException("无法在所选目录创建文件")
                context.contentResolver.openOutputStream(created.uri, "w").use { out ->
                    if (out == null) throw IllegalStateException("无法写入所选目录")
                    FileInputStream(partial).use { input -> input.copyTo(out) }
                }
                // Prefer MediaStore URI for library / player consistency.
            } catch (e: Exception) {
                Log.w(TAG, "SAF copy failed, keep MediaStore file", e)
            }
        }
        return mediaStoreUri
    }

    private fun publishToMediaStore(partial: File, fileName: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/LocalPlay"
                )
                put(MediaStore.Video.Media.SIZE, partial.length())
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建媒体库条目")
            resolver.openOutputStream(uri).use { out ->
                if (out == null) throw IllegalStateException("无法写入媒体库")
                FileInputStream(partial).use { input -> input.copyTo(out) }
            }
            val meta = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.SIZE, partial.length())
                probeDurationMs(uri)?.let { put(MediaStore.Video.Media.DURATION, it) }
            }
            resolver.update(uri, meta, null, null)
            return uri
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "LocalPlay"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建下载目录")
        }
        val target = File(dir, fileName)
        FileInputStream(partial).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        scanFileBlocking(target.absolutePath, mime)
        return Uri.fromFile(target)
    }

    private fun probeDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            Log.i(TAG, "probe duration failed: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun scanFileBlocking(path: String, mime: String) {
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(path),
            arrayOf(mime)
        ) { _, _ -> latch.countDown() }
        latch.await(8, TimeUnit.SECONDS)
    }

    fun defaultDirPath(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Movies/LocalPlay"
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "LocalPlay"
            ).absolutePath
        }
    }

    private fun resolveMediaPlaylist(url: String, referer: String): String {
        val text = http.getText(url, referer = referer.ifBlank { null })
        if (!text.contains("#EXT-X-STREAM-INF")) return url
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var bestUrl: String? = null
        var bestBandwidth = -1
        var pendingBandwidth = 0
        lines.forEach { line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingBandwidth = Regex("""BANDWIDTH=(\d+)""")
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 0
            } else if (!line.startsWith("#")) {
                if (pendingBandwidth >= bestBandwidth) {
                    bestBandwidth = pendingBandwidth
                    bestUrl = absolutize(url, line)
                }
                pendingBandwidth = 0
            }
        }
        return bestUrl ?: url
    }

    private fun parseSegments(playlistUrl: String, playlist: String): List<String> {
        return playlist.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { absolutize(playlistUrl, it) }
    }

    private fun absolutize(baseUrl: String, href: String): String {
        val cleaned = href.trim()
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        val uri = URI(baseUrl)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return cleaned
        return when {
            cleaned.startsWith("//") -> "$scheme:$cleaned"
            cleaned.startsWith("/") -> "$scheme://$host$cleaned"
            else -> baseUrl.substringBeforeLast('/') + "/" + cleaned
        }
    }

    private fun mimeFor(task: DownloadTaskEntity): String {
        return when {
            task.isHls -> "video/mp2t"
            task.mediaUrl.contains(".webm", true) -> "video/webm"
            task.mediaUrl.contains(".mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
        }
    }

    private fun safeName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
    }

    companion object {
        private const val TAG = "ResumableDownload"
    }
}
