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
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

        // Fresh large files: multi-Range parallel (sequential resume stays single-stream).
        if (offset == 0L) {
            try {
                val multi = tryMultiRangeProgressive(current, partial, shouldAbort, onProgress)
                if (multi != null) {
                    current = multi
                    val outputUri = publishFinal(partial, current.fileName, current.treeUri, mimeFor(current))
                    return current.copy(
                        status = DownloadStatus.COMPLETED,
                        outputUri = outputUri.toString(),
                        partialPath = partial.absolutePath,
                        updatedAt = System.currentTimeMillis(),
                        errorMessage = ""
                    )
                }
            } catch (e: DownloadAbortedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "multi-range fallback to single stream", e)
                if (partial.exists()) partial.delete()
                current = current.copy(downloadedBytes = 0L, totalBytes = -1L)
                onProgress(current)
            }
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

    /**
     * Parallel Range download into a pre-sized file. Returns null if server/size unsuitable.
     * Note: mid-file holes may appear until all ranges finish — fine for speed; resume falls back.
     */
    private fun tryMultiRangeProgressive(
        task: DownloadTaskEntity,
        partial: File,
        shouldAbort: () -> Boolean,
        onProgress: (DownloadTaskEntity) -> Unit
    ): DownloadTaskEntity? {
        val total = http.probeContentLength(task.mediaUrl, task.pageUrl.ifBlank { null })
            ?: return null
        if (total < MIN_MULTI_RANGE_BYTES) return null

        val connections = PROGRESSIVE_CONNECTIONS
        val chunk = (total + connections - 1) / connections
        val referer = task.pageUrl.ifBlank { null }
        val downloaded = AtomicLong(0L)
        val failed = AtomicBoolean(false)
        var error: Exception? = null
        var current = task.copy(totalBytes = total, downloadedBytes = 0L)
        val progressLock = Any()

        RandomAccessFile(partial, "rw").use { raf ->
            raf.setLength(total)
            val pool = Executors.newFixedThreadPool(connections)
            try {
                val futures = ArrayList<Future<*>>(connections)
                for (i in 0 until connections) {
                    val start = i * chunk
                    if (start >= total) break
                    val end = minOf(total - 1, start + chunk - 1)
                    futures += pool.submit {
                        try {
                            checkAbort(shouldAbort)
                            val out = RangeOutputStream(raf, start)
                            val result = http.copyTo(
                                url = task.mediaUrl,
                                output = out,
                                referer = referer,
                                startByte = start,
                                endByte = end
                            ) { _, _ ->
                                checkAbort(shouldAbort)
                            }
                            if (result.httpCode == 200 && start > 0L) {
                                throw IllegalStateException("server ignored Range")
                            }
                            val soFar = downloaded.addAndGet(result.bytesWritten)
                            val snap = synchronized(progressLock) {
                                current = current.copy(
                                    downloadedBytes = soFar.coerceAtMost(total),
                                    totalBytes = total,
                                    updatedAt = System.currentTimeMillis()
                                )
                                current
                            }
                            onProgress(snap)
                        } catch (e: DownloadAbortedException) {
                            failed.set(true)
                            throw e
                        } catch (e: Exception) {
                            failed.set(true)
                            synchronized(progressLock) {
                                if (error == null) error = e
                            }
                            throw e
                        }
                    }
                }
                futures.forEach { future ->
                    try {
                        future.get()
                    } catch (e: Exception) {
                        failed.set(true)
                        val cause = (e.cause as? Exception) ?: e
                        if (cause is DownloadAbortedException) throw cause
                        synchronized(progressLock) {
                            if (error == null) error = cause
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }

        if (failed.get()) {
            partial.delete()
            error?.let { throw it }
            return null
        }
        if (partial.length() != total) {
            partial.delete()
            return null
        }
        return current.copy(downloadedBytes = total, totalBytes = total)
    }

    /** Thread-safe OutputStream that writes into a shared RandomAccessFile at a base offset. */
    private class RangeOutputStream(
        private val raf: RandomAccessFile,
        baseOffset: Long
    ) : java.io.OutputStream() {
        private var position = baseOffset

        override fun write(b: Int) {
            synchronized(raf) {
                raf.seek(position)
                raf.write(b)
                position++
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            synchronized(raf) {
                raf.seek(position)
                raf.write(b, off, len)
                position += len.toLong()
            }
        }
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

        downloadHlsParallel(current, partial, segments, shouldAbort) { updated ->
            current = updated
            onProgress(current)
        }

        val outputUri = publishFinal(partial, current.fileName, current.treeUri, "video/mp2t")
        // Keep partial for in-flight 边下边播 readers; cleaned on restart/cancel.
        return current.copy(
            status = DownloadStatus.COMPLETED,
            outputUri = outputUri.toString(),
            partialPath = partial.absolutePath,
            hlsSegmentIndex = segments.size,
            hlsSegmentTotal = segments.size,
            downloadedBytes = partial.length(),
            updatedAt = System.currentTimeMillis(),
            errorMessage = ""
        )
    }

    /**
     * Fetch several HLS segments concurrently into temp files, then append in order
     * so the growing partial stays sequentially readable for 边下边播.
     */
    private fun downloadHlsParallel(
        task: DownloadTaskEntity,
        partial: File,
        segments: List<String>,
        shouldAbort: () -> Boolean,
        onProgress: (DownloadTaskEntity) -> Unit
    ) {
        val referer = task.pageUrl.ifBlank { null }
        val startIndex = task.hlsSegmentIndex
        val parallelism = HLS_SEGMENT_PARALLEL
        val segDir = File(partial.parentFile, "${partial.name}.segs").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val pool = Executors.newFixedThreadPool(parallelism)
        val ready = ConcurrentHashMap<Int, File>()
        val latchByIndex = ConcurrentHashMap<Int, CountDownLatch>()
        var current = task
        try {
            FileOutputStream(partial, startIndex > 0).use { output ->
                var nextToWrite = startIndex
                var fetchCursor = startIndex
                val inflight = ArrayList<Future<*>>()

                fun ensureLatch(index: Int): CountDownLatch {
                    return latchByIndex.getOrPut(index) { CountDownLatch(1) }
                }

                fun submitFetch(index: Int): Future<*> {
                    ensureLatch(index)
                    return pool.submit {
                        checkAbort(shouldAbort)
                        val tmp = File(segDir, "$index.ts")
                        FileOutputStream(tmp).use { out ->
                            http.copyTo(
                                url = segments[index],
                                output = out,
                                referer = referer,
                                startByte = 0L
                            )
                        }
                        ready[index] = tmp
                        ensureLatch(index).countDown()
                    }
                }

                while (nextToWrite < segments.size) {
                    checkAbort(shouldAbort)
                    while (fetchCursor < segments.size &&
                        fetchCursor - nextToWrite < parallelism
                    ) {
                        inflight += submitFetch(fetchCursor)
                        fetchCursor++
                    }

                    val latch = ensureLatch(nextToWrite)
                    while (!latch.await(200L, TimeUnit.MILLISECONDS)) {
                        checkAbort(shouldAbort)
                        // Surface worker exceptions early.
                        inflight.filter { it.isDone }.forEach { done ->
                            done.get()
                            inflight.remove(done)
                        }
                    }
                    inflight.filter { it.isDone }.forEach { done ->
                        done.get()
                        inflight.remove(done)
                    }

                    val segFile = ready.remove(nextToWrite)
                        ?: throw IllegalStateException("分片缺失 index=$nextToWrite")
                    FileInputStream(segFile).use { input -> input.copyTo(output) }
                    output.flush()
                    segFile.delete()
                    latchByIndex.remove(nextToWrite)
                    nextToWrite++
                    current = current.copy(
                        hlsSegmentIndex = nextToWrite,
                        downloadedBytes = partial.length(),
                        updatedAt = System.currentTimeMillis()
                    )
                    onProgress(current)
                }
                inflight.forEach { it.get() }
            }
        } finally {
            pool.shutdownNow()
            segDir.deleteRecursively()
        }
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
        /** Concurrent m3u8 segment fetches per task (ordered append). */
        private const val HLS_SEGMENT_PARALLEL = 6
        /** Parallel HTTP Range connections for large progressive files. */
        private const val PROGRESSIVE_CONNECTIONS = 4
        /** Skip multi-range for small files (overhead not worth it). */
        private const val MIN_MULTI_RANGE_BYTES = 8L * 1024L * 1024L
    }
}
