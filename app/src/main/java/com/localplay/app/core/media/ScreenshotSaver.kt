package com.localplay.app.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.ui.PlayerView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object ScreenshotSaver {
    private const val RELATIVE_DIR = "Pictures/LocalPlay/Screenshots"

    data class Result(val uri: Uri, val bitmap: Bitmap)

    suspend fun captureAndSave(
        context: Context,
        playerView: PlayerView,
        videoUri: String,
        videoDisplayName: String,
        positionMs: Long
    ): Result = withContext(Dispatchers.Main) {
        val bitmap = captureFromPlayerView(playerView)
            ?: withContext(Dispatchers.IO) {
                captureWithRetriever(context, videoUri, positionMs)
            }
            ?: throw IllegalStateException("无法捕获当前画面")

        val uri = withContext(Dispatchers.IO) {
            savePng(context, bitmap, videoDisplayName)
        }
        Result(uri = uri, bitmap = bitmap)
    }

    private suspend fun captureFromPlayerView(playerView: PlayerView): Bitmap? {
        val width = playerView.width
        val height = playerView.height
        if (width <= 0 || height <= 0) return null

        val surfaceView = findSurfaceView(playerView)
        if (surfaceView != null) {
            return pixelCopySurface(surfaceView, width, height)
        }

        val textureView = findTextureView(playerView)
        if (textureView != null) {
            return textureView.bitmap
        }
        return null
    }

    private suspend fun pixelCopySurface(
        surfaceView: SurfaceView,
        width: Int,
        height: Int
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    cont.resume(bitmap)
                } else {
                    bitmap.recycle()
                    cont.resume(null)
                }
            }, surfaceView.handler)
        } catch (e: Exception) {
            bitmap.recycle()
            cont.resume(null)
        }
    }

    private fun findSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findSurfaceView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findTextureView(root: View): TextureView? {
        if (root is TextureView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextureView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun captureWithRetriever(
        context: Context,
        videoUri: String,
        positionMs: Long
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(videoUri))
            retriever.getFrameAtTime(
                positionMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun savePng(context: Context, bitmap: Bitmap, videoDisplayName: String): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val base = videoDisplayName.substringBeforeLast('.').ifBlank { "video" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(40)
        val fileName = "${base}_$stamp.png"
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建截图文件")
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("PNG 写入失败")
                }
            } ?: throw IOException("无法打开截图输出流")
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            return uri
        }

        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val folder = java.io.File(dir, "LocalPlay/Screenshots").apply { mkdirs() }
        val file = java.io.File(folder, fileName)
        file.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("PNG 写入失败")
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATA, file.absolutePath)
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(file)
    }
}
