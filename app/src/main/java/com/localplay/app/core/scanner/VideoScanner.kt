package com.localplay.app.core.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.localplay.app.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoScanner(private val context: Context) {

    data class ScanProgress(val scanned: Int, val totalHint: Int)

    suspend fun scan(
        minSizeBytes: Long = DEFAULT_MIN_SIZE_BYTES,
        minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        val result = mutableListOf<VideoItem>()
        var scanned = 0

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val total = cursor.count

            while (cursor.moveToNext()) {
                scanned++
                if (scanned % 20 == 0) {
                    onProgress?.invoke(ScanProgress(scanned, total))
                }

                val size = cursor.getLong(sizeCol)
                val duration = cursor.getLong(durationCol)
                if (size < minSizeBytes || duration < minDurationMs) {
                    continue
                }

                val path = cursor.getString(dataCol).orEmpty()
                if (shouldSkipPath(path)) {
                    continue
                }

                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val displayName = cursor.getString(nameCol).orEmpty().ifBlank {
                    path.substringAfterLast(File.separatorChar)
                }
                val parentPath = if (path.isNotBlank()) {
                    path.substringBeforeLast(File.separatorChar, "")
                } else {
                    ""
                }
                val parentName = parentPath.substringAfterLast(File.separatorChar, "")
                val bucketId = cursor.getLong(bucketIdCol)
                val bucketName = cursor.getString(bucketCol)?.takeIf { it.isNotBlank() }.orEmpty()
                val folderName = when {
                    bucketName.isNotBlank() -> bucketName
                    parentName.isNotBlank() -> parentName
                    else -> "其他"
                }
                val folderKey = when {
                    bucketId != 0L -> "bucket:$bucketId"
                    parentPath.isNotBlank() -> "path:$parentPath"
                    else -> "name:$folderName"
                }

                result += VideoItem(
                    id = id,
                    uri = uri,
                    path = path,
                    displayName = displayName,
                    folderName = folderName,
                    folderKey = folderKey,
                    durationMs = duration,
                    sizeBytes = size,
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol),
                    mimeType = cursor.getString(mimeCol).orEmpty(),
                    dateModified = cursor.getLong(modifiedCol) * 1000L
                )
            }
            onProgress?.invoke(ScanProgress(scanned, total))
        }
        result
    }

    private fun shouldSkipPath(path: String): Boolean {
        if (path.isBlank()) return false
        val lower = path.lowercase()
        return lower.contains("/android/data/") ||
            lower.contains("/android/obb/") ||
            lower.contains("/.")
    }

    companion object {
        const val DEFAULT_MIN_SIZE_BYTES = 10L * 1024L * 1024L
        const val DEFAULT_MIN_DURATION_MS = 5_000L
    }
}
