package com.localplay.app.core.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object VideoClipExporter {
    private const val RELATIVE_DIR = "Movies/LocalPlay/Clips"
    private const val BUFFER_SIZE = 1024 * 1024
    private const val MIN_CLIP_MS = 500L

    data class Result(val uri: Uri, val displayName: String)

    suspend fun export(
        context: Context,
        sourceUri: String,
        startMs: Long,
        endMs: Long,
        videoDisplayName: String,
        onProgress: (Float) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val startUs = max(0L, startMs) * 1000L
        val endUs = max(startUs + MIN_CLIP_MS * 1000L, endMs * 1000L)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val base = videoDisplayName.substringBeforeLast('.').ifBlank { "clip" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(40)
        val fileName = "${base}_${stamp}_clip.mp4"

        val pending = createOutput(context, fileName)
        try {
            remux(
                context = context,
                sourceUri = Uri.parse(sourceUri),
                outputFd = pending.pfd.fileDescriptor,
                startUs = startUs,
                endUs = endUs,
                onProgress = onProgress
            )
            finishOutput(context, pending)
            Result(uri = pending.uri, displayName = fileName)
        } catch (e: Exception) {
            abortOutput(context, pending)
            throw e
        }
    }

    private data class PendingOutput(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val file: File? = null
    )

    private fun createOutput(context: Context, fileName: String): PendingOutput {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建剪辑文件")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("无法打开剪辑输出")
            return PendingOutput(uri = uri, pfd = pfd)
        }

        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val folder = File(dir, "LocalPlay/Clips").apply { mkdirs() }
        val file = File(folder, fileName)
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )
        return PendingOutput(uri = Uri.fromFile(file), pfd = pfd, file = file)
    }

    private fun finishOutput(context: Context, pending: PendingOutput) {
        pending.pfd.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(pending.uri, values, null, null)
        } else if (pending.file != null) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, pending.file.absolutePath)
                put(MediaStore.Video.Media.DISPLAY_NAME, pending.file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }
    }

    private fun abortOutput(context: Context, pending: PendingOutput) {
        try {
            pending.pfd.close()
        } catch (_: Exception) {
            // ignore
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.delete(pending.uri, null, null)
        } else {
            pending.file?.delete()
        }
    }

    private fun remux(
        context: Context,
        sourceUri: Uri,
        outputFd: java.io.FileDescriptor,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(context, sourceUri, null)
            val trackCount = extractor.trackCount
            val indexMap = HashMap<Int, Int>(trackCount)
            var hasVideo = false

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/")) hasVideo = true
                indexMap[i] = muxer.addTrack(format)
            }
            if (!hasVideo || indexMap.isEmpty()) {
                throw IOException("源视频无可导出音视频轨")
            }

            muxer.start()
            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            val span = max(1L, endUs - startUs)

            for ((srcIndex, dstIndex) in indexMap) {
                extractor.selectTrack(srcIndex)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var firstPts = -1L

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0L) break
                    if (sampleTime > endUs) break

                    if (firstPts < 0L) firstPts = sampleTime
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = max(0L, sampleTime - firstPts)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(dstIndex, buffer, info)

                    val progress = min(1f, (sampleTime - startUs).toFloat() / span.toFloat())
                    onProgress(progress)
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(srcIndex)
            }
            onProgress(1f)
        } finally {
            try {
                muxer.stop()
            } catch (_: Exception) {
                // stop may fail if nothing written
            }
            muxer.release()
            extractor.release()
        }
    }
}
