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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        val media = parseMedia(mediaPlaylistUrl, playlist)
        val segments = media.segments
        if (segments.isEmpty()) {
            throw IllegalStateException("m3u8 未找到分片")
        }
        if (media.totalDurationSec < 60.0 && segments.size < 20) {
            throw IllegalStateException(
                "播放列表过短（约 ${media.totalDurationSec.toInt()} 秒），可能是预览源，请换线路重试"
            )
        }

        val partial = ensurePartialFile(current)
        val hlsDir = hlsPackageDir(current.id).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
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

        downloadHlsParallel(current, partial, hlsDir, media, shouldAbort) { updated ->
            current = updated
            onProgress(current)
        }

        writeLocalHlsPlaylist(hlsDir, media)

        // fMP4 HLS (EXT-X-MAP) is an MP4 container; classic HLS is MPEG-TS.
        val hlsMime = if (media.initUrl != null) "video/mp4" else "video/mp2t"
        val durationMs = (media.totalDurationSec * 1000.0).toLong().coerceAtLeast(0L)
        val outputUri = publishFinal(
            partial = partial,
            fileName = current.fileName,
            treeUri = current.treeUri,
            mime = hlsMime,
            durationMs = durationMs
        )
        // Keep partial for legacy readers; playback prefers local HLS package (index.m3u8).
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

    fun hlsPackageDir(taskId: Long): File {
        return File(context.filesDir, "hls_downloads/$taskId")
    }

    private fun writeLocalHlsPlaylist(hlsDir: File, media: HlsMedia) {
        // Segments are stored already decrypted, so no EXT-X-KEY is needed.
        val targetDuration = media.segments.maxOfOrNull { it.durationSec }?.let {
            kotlin.math.ceil(it).toInt().coerceAtLeast(1)
        } ?: 8
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        sb.appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        sb.appendLine("#EXT-X-TARGETDURATION:$targetDuration")
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        if (media.initUrl != null && File(hlsDir, "init.mp4").exists()) {
            sb.appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
        }
        media.segments.forEachIndexed { index, seg ->
            if (seg.discontinuityBefore) {
                sb.appendLine("#EXT-X-DISCONTINUITY")
            }
            sb.appendLine("#EXTINF:${String.format(java.util.Locale.US, "%.3f", seg.durationSec)},")
            sb.appendLine(segmentFileName(index))
        }
        sb.appendLine("#EXT-X-ENDLIST")
        File(hlsDir, "index.m3u8").writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun segmentFileName(index: Int): String =
        "seg_%05d.ts".format(index)

    /**
     * Fetch several HLS segments concurrently into temp files, then append in order
     * so the growing partial stays sequentially readable. Also persist each segment into
     * [hlsDir] for local m3u8 playback (concatenated .ts often stops ~17s at discontinuities).
     */
    private fun downloadHlsParallel(
        task: DownloadTaskEntity,
        partial: File,
        hlsDir: File,
        media: HlsMedia,
        shouldAbort: () -> Boolean,
        onProgress: (DownloadTaskEntity) -> Unit
    ) {
        val segments = media.segments
        val referer = task.pageUrl.ifBlank { null }
        val startIndex = task.hlsSegmentIndex
        val parallelism = HLS_SEGMENT_PARALLEL
        val segDir = File(partial.parentFile, "${partial.name}.segs").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val pool = Executors.newFixedThreadPool(parallelism)
        val ready = ConcurrentHashMap<Int, File>()
        val latchByIndex = ConcurrentHashMap<Int, CountDownLatch>()
        var current = task
        try {
            FileOutputStream(partial, startIndex > 0).use { output ->
                // fMP4 init segment must precede the media segments.
                if (startIndex == 0 && media.initUrl != null) {
                    checkAbort(shouldAbort)
                    val initBytes = http.getBytes(media.initUrl, referer)
                    File(hlsDir, "init.mp4").writeBytes(initBytes)
                    output.write(initBytes)
                    output.flush()
                }
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
                        val seg = segments[index]
                        var bytes = http.getBytes(seg.url, referer)
                        if (seg.keyUri != null && seg.iv != null) {
                            val keyBytes = keyCache.getOrPut(seg.keyUri) {
                                http.getBytes(seg.keyUri, referer)
                            }
                            bytes = decryptAes128(bytes, keyBytes, seg.iv)
                        }
                        val tmp = File(segDir, "$index.ts")
                        FileOutputStream(tmp).use { out -> out.write(bytes) }
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
                    // Keep a durable copy for local HLS playback.
                    segFile.copyTo(File(hlsDir, segmentFileName(nextToWrite)), overwrite = true)
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
        mime: String,
        durationMs: Long = -1L
    ): Uri {
        // Always index into MediaStore so home library can discover the file.
        val mediaStoreUri = publishToMediaStore(partial, fileName, mime, durationMs)

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

    private fun publishToMediaStore(
        partial: File,
        fileName: String,
        mime: String,
        durationMs: Long = -1L
    ): Uri {
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
            val probed = durationMs.takeIf { it > 0L } ?: probeDurationMs(uri)
            val meta = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.SIZE, partial.length())
                if (probed != null && probed > 0L) {
                    put(MediaStore.Video.Media.DURATION, probed)
                }
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

    private data class HlsSeg(
        val url: String,
        val keyUri: String?,
        val iv: ByteArray?,
        val durationSec: Double,
        val discontinuityBefore: Boolean
    )

    private data class HlsMedia(
        val initUrl: String?,
        val segments: List<HlsSeg>
    ) {
        val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
    }

    /**
     * Parse a media playlist, resolving AES-128 encryption keys/IVs and the optional fMP4
     * init segment. Encrypted segments carry the resolved key URI + IV so the downloader can
     * decrypt them (otherwise the merged .ts is unplayable garbage).
     */
    private fun parseMedia(playlistUrl: String, playlist: String): HlsMedia {
        var seq = 0L
        var curKeyUri: String? = null
        var curKeyIvHex: String? = null
        var initUrl: String? = null
        var pendingDuration = 0.0
        var pendingDisc = false
        val segs = ArrayList<HlsSeg>()

        playlist.lines().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',')
                        .toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-DISCONTINUITY") -> {
                    pendingDisc = true
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD")?.uppercase()
                    if (method == null || method == "NONE") {
                        curKeyUri = null
                        curKeyIvHex = null
                    } else {
                        curKeyUri = attr(line, "URI")?.let { absolutize(playlistUrl, it) }
                        curKeyIvHex = attr(line, "IV")
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    initUrl = attr(line, "URI")?.let { absolutize(playlistUrl, it) }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = absolutize(playlistUrl, line)
                    val keyUri = curKeyUri
                    val iv = if (keyUri != null) {
                        curKeyIvHex?.let { hexToBytes(it) } ?: seqToIv(seq)
                    } else {
                        null
                    }
                    segs += HlsSeg(
                        url = url,
                        keyUri = keyUri,
                        iv = iv,
                        durationSec = pendingDuration,
                        discontinuityBefore = pendingDisc
                    )
                    pendingDuration = 0.0
                    pendingDisc = false
                    seq++
                }
            }
        }
        return HlsMedia(initUrl, segs)
    }

    private fun attr(line: String, name: String): String? {
        val m = Regex("""$name=("([^"]*)"|[^,\s]+)""").find(line) ?: return null
        return (m.groupValues[2].ifEmpty { m.groupValues[1] }).trim()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        val padded = if (clean.length % 2 == 0) clean else "0$clean"
        return ByteArray(padded.length / 2) { i ->
            padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun seqToIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        var value = seq
        for (i in 15 downTo 8) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(key.copyOf(16), "AES")
        val ivSpec = IvParameterSpec(iv.copyOf(16))
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            // Some CDNs pre-pad to a block boundary; retry without padding stripping.
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val aligned = if (data.size % 16 == 0) data else data.copyOf(data.size - data.size % 16)
            cipher.doFinal(aligned)
        }
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
