# -*- coding: utf-8 -*-
from pathlib import Path

Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\sniff\VideoDownloader.kt").write_text(r'''package com.localplay.app.core.sniff

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI

class VideoDownloader(
    private val context: Context,
    private val http: ChallengedHttpClient = ChallengedHttpClient()
) {
    fun download(
        video: SniffedVideo,
        treeUri: String?,
        onProgress: (Float, String) -> Unit
    ): Uri {
        return if (video.isHls) {
            downloadHls(video, treeUri, onProgress)
        } else {
            downloadProgressive(video, treeUri, onProgress)
        }
    }

    private fun downloadProgressive(
        video: SniffedVideo,
        treeUri: String?,
        onProgress: (Float, String) -> Unit
    ): Uri {
        val target = openOutput(video.suggestedFileName, treeUri, mimeFor(video))
        try {
            http.copyTo(
                url = video.mediaUrl,
                output = target.stream,
                referer = video.pageUrl
            ) { read, total ->
                val fraction = if (total != null && total > 0) {
                    (read.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    0f
                }
                onProgress(fraction, "下载中 ${formatBytes(read)}")
            }
            target.stream.flush()
        } finally {
            target.finish()
        }
        onProgress(1f, "完成")
        return target.uri
    }

    private fun downloadHls(
        video: SniffedVideo,
        treeUri: String?,
        onProgress: (Float, String) -> Unit
    ): Uri {
        onProgress(0f, "解析播放列表…")
        val mediaPlaylistUrl = resolveMediaPlaylist(video.mediaUrl, video.pageUrl)
        val playlist = http.getText(mediaPlaylistUrl, referer = video.pageUrl)
        val segments = parseSegments(mediaPlaylistUrl, playlist)
        if (segments.isEmpty()) {
            throw IllegalStateException("m3u8 未找到分片")
        }
        val target = openOutput(video.suggestedFileName, treeUri, "video/mp2t")
        try {
            segments.forEachIndexed { index, segmentUrl ->
                http.copyTo(
                    url = segmentUrl,
                    output = target.stream,
                    referer = video.pageUrl
                )
                val fraction = ((index + 1).toFloat() / segments.size).coerceIn(0f, 1f)
                onProgress(fraction, "分片 ${index + 1}/${segments.size}")
            }
            target.stream.flush()
        } finally {
            target.finish()
        }
        onProgress(1f, "完成")
        return target.uri
    }

    private fun resolveMediaPlaylist(url: String, referer: String): String {
        val text = http.getText(url, referer = referer)
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

    private fun openOutput(fileName: String, treeUri: String?, mime: String): OutputTarget {
        if (!treeUri.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: throw IllegalStateException("下载目录不可用，请在设置中重新选择")
            tree.findFile(fileName)?.delete()
            val created = tree.createFile(mime, fileName)
                ?: throw IllegalStateException("无法在所选目录创建文件")
            val stream = context.contentResolver.openOutputStream(created.uri, "w")
                ?: throw IllegalStateException("无法写入所选目录")
            return OutputTarget(created.uri, stream, null)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/LocalPlay"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建媒体库条目")
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法写入媒体库")
            return OutputTarget(uri, stream) {
                val done = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
        }

        val dir = legacyPublicDir()
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建下载目录")
        }
        val file = File(dir, fileName)
        return OutputTarget(Uri.fromFile(file), FileOutputStream(file), null)
    }

    fun defaultDirPath(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Movies/LocalPlay"
        } else {
            legacyPublicDir().absolutePath
        }
    }

    private fun legacyPublicDir(): File {
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        return File(movies, "LocalPlay")
    }

    private fun mimeFor(video: SniffedVideo): String {
        return when {
            video.isHls -> "video/mp2t"
            video.mediaUrl.contains(".webm", true) -> "video/webm"
            video.mediaUrl.contains(".mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
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

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "${value}B"
        val kb = value / 1024.0
        if (kb < 1024) return String.format("%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1fMB", mb)
        return String.format("%.2fGB", mb / 1024.0)
    }

    private class OutputTarget(
        val uri: Uri,
        val stream: OutputStream,
        private val onFinished: (() -> Unit)?
    ) {
        fun finish() {
            stream.close()
            onFinished?.invoke()
        }
    }
}
''', encoding='utf-8', newline='\n')

Path(r"D:\code\stu\videoPlay\tools\write_settings_download.py").read_text(encoding='utf-8')
import subprocess, sys
subprocess.check_call([sys.executable, r"D:\code\stu\videoPlay\tools\write_settings_download.py"])
subprocess.check_call([sys.executable, r"D:\code\stu\videoPlay\tools\write_downloader_mediastore.py"])
print('done run settings+note mediastore already written above')
