# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")

(JAVA / "core/sniff/VideoSniffer.kt").write_text(r'''package com.localplay.app.core.sniff

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.regex.Pattern

class VideoSniffer(
    private val http: ChallengedHttpClient = ChallengedHttpClient()
) {
    fun sniff(
        pageUrl: String,
        maxDetailPages: Int = 20,
        onStatus: (String) -> Unit = {}
    ): List<SniffedVideo> {
        val normalized = normalizeUrl(pageUrl)
        onStatus("正在打开页面…")

        if (isDirectMedia(normalized)) {
            return listOf(
                SniffedVideo(
                    title = fileNameFromUrl(normalized),
                    pageUrl = normalized,
                    mediaUrl = normalized,
                    sourceLabel = "直链"
                )
            )
        }

        val html = http.getHtml(normalized)
        val fromPlayer = extractFromPlayerJson(html, normalized)
        if (fromPlayer.isNotEmpty()) return fromPlayer

        val direct = extractDirectMediaUrls(html, normalized)
        if (direct.isNotEmpty()) return direct

        val details = extractDetailLinks(html, normalized)
        if (details.isNotEmpty()) {
            onStatus("发现 ${details.size} 个影片页，开始解析播放地址…")
            return resolveDetails(details.take(maxDetailPages), onStatus)
        }

        val playLinks = extractPlayLinks(html, normalized)
        if (playLinks.isNotEmpty()) {
            onStatus("发现 ${playLinks.size} 个播放页…")
            return resolvePlayPages(playLinks, extractTitle(html), onStatus)
        }

        return emptyList()
    }

    private fun resolveDetails(
        details: List<Pair<String, String>>,
        onStatus: (String) -> Unit
    ): List<SniffedVideo> {
        val result = LinkedHashMap<String, SniffedVideo>()
        details.forEachIndexed { index, (title, detailUrl) ->
            onStatus("解析影片 ${index + 1}/${details.size}：$title")
            try {
                val detailHtml = http.getHtml(detailUrl)
                val playLinks = extractPlayLinks(detailHtml, detailUrl)
                if (playLinks.isEmpty()) return@forEachIndexed
                resolvePlayPages(
                    playLinks = playLinks.take(2),
                    titleHint = title.ifBlank { extractTitle(detailHtml) },
                    onStatus = {}
                ).forEach { video -> result.putIfAbsent(video.mediaUrl, video) }
            } catch (e: Exception) {
                Log.w(TAG, "resolve detail failed: $detailUrl", e)
            }
        }
        return result.values.toList()
    }

    private fun resolvePlayPages(
        playLinks: List<String>,
        titleHint: String,
        onStatus: (String) -> Unit
    ): List<SniffedVideo> {
        val result = LinkedHashMap<String, SniffedVideo>()
        playLinks.forEachIndexed { index, playUrl ->
            onStatus("读取播放源 ${index + 1}/${playLinks.size}")
            try {
                val html = http.getHtml(playUrl)
                extractFromPlayerJson(html, playUrl, titleHint).forEach {
                    result.putIfAbsent(it.mediaUrl, it)
                }
                extractDirectMediaUrls(html, playUrl, titleHint).forEach {
                    result.putIfAbsent(it.mediaUrl, it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolve play failed: $playUrl", e)
            }
        }
        return result.values.toList()
    }

    private fun extractFromPlayerJson(
        html: String,
        pageUrl: String,
        titleHint: String = ""
    ): List<SniffedVideo> {
        val matcher = PLAYER_JSON.matcher(html)
        if (!matcher.find()) return emptyList()
        return try {
            val json = JSONObject(matcher.group(1))
            val encrypt = json.optInt("encrypt", 0)
            val media = decodePlayerUrl(encrypt, json.optString("url"))
            if (media.isBlank() || (!isDirectMedia(media) && !media.startsWith("http"))) {
                emptyList()
            } else {
                val vodName = json.optJSONObject("vod_data")?.optString("vod_name").orEmpty()
                listOf(
                    SniffedVideo(
                        title = vodName.ifBlank { titleHint.ifBlank { extractTitle(html) } },
                        pageUrl = pageUrl,
                        mediaUrl = media,
                        sourceLabel = json.optString("from").ifBlank { "播放器" }
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse player json failed", e)
            emptyList()
        }
    }

    private fun extractDirectMediaUrls(
        html: String,
        pageUrl: String,
        titleHint: String = ""
    ): List<SniffedVideo> {
        val found = LinkedHashMap<String, SniffedVideo>()
        val matcher = DIRECT_MEDIA.matcher(html)
        while (matcher.find()) {
            val media = absolutize(pageUrl, matcher.group().replace("\\/", "/"))
            if (!isDirectMedia(media)) continue
            found.putIfAbsent(
                media,
                SniffedVideo(
                    title = titleHint.ifBlank { extractTitle(html) }.ifBlank { fileNameFromUrl(media) },
                    pageUrl = pageUrl,
                    mediaUrl = media,
                    sourceLabel = "页面直链"
                )
            )
        }
        val srcMatcher = SRC_ATTR.matcher(html)
        while (srcMatcher.find()) {
            val media = absolutize(pageUrl, srcMatcher.group(1))
            if (!isDirectMedia(media)) continue
            found.putIfAbsent(
                media,
                SniffedVideo(
                    title = titleHint.ifBlank { extractTitle(html) }.ifBlank { fileNameFromUrl(media) },
                    pageUrl = pageUrl,
                    mediaUrl = media,
                    sourceLabel = "标签源"
                )
            )
        }
        return found.values.toList()
    }

    private fun extractDetailLinks(html: String, pageUrl: String): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()
        val titled = DETAIL_HREF.matcher(html)
        while (titled.find()) {
            val href = absolutize(pageUrl, titled.group(1))
            val title = cleanText(titled.group(2)).ifBlank {
                href.substringAfterLast('/').substringBefore('.')
            }
            result.putIfAbsent(href, title)
        }
        val plain = DETAIL_PLAIN.matcher(html)
        while (plain.find()) {
            val href = absolutize(pageUrl, plain.group(1))
            result.putIfAbsent(href, href.substringAfterLast('/').substringBefore('.'))
        }
        return result.entries.map { it.value to it.key }
    }

    private fun extractPlayLinks(html: String, pageUrl: String): List<String> {
        val result = LinkedHashSet<String>()
        val matcher = PLAY_HREF.matcher(html)
        while (matcher.find()) {
            result += absolutize(pageUrl, matcher.group(1))
        }
        return result.toList()
    }

    private fun decodePlayerUrl(encrypt: Int, raw: String): String {
        val url = raw.replace("\\/", "/")
        return try {
            when (encrypt) {
                1 -> URLDecoder.decode(url, "UTF-8")
                2 -> String(Base64.decode(url, Base64.DEFAULT), Charsets.UTF_8)
                else -> url
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode player url failed", e)
            url
        }
    }

    private fun extractTitle(html: String): String {
        val matcher = TITLE.matcher(html)
        if (!matcher.find()) return ""
        return cleanText(matcher.group(1))
            .substringBefore('-')
            .substringBefore('_')
            .trim()
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        return MEDIA_EXT.any { lower.contains(it) }
    }

    private fun fileNameFromUrl(url: String): String {
        return try {
            URI(url).path.substringAfterLast('/').ifBlank { "video" }
        } catch (_: Exception) {
            "video"
        }
    }

    private fun absolutize(baseUrl: String, href: String): String {
        val cleaned = href.trim().replace("\\/", "/")
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        val uri = try {
            URI(baseUrl)
        } catch (_: Exception) {
            return cleaned
        }
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return cleaned
        return when {
            cleaned.startsWith("//") -> "$scheme:$cleaned"
            cleaned.startsWith("/") -> "$scheme://$host$cleaned"
            else -> baseUrl.substringBeforeLast('/') + "/" + cleaned
        }
    }

    companion object {
        private const val TAG = "VideoSniffer"
        private val MEDIA_EXT = listOf(".m3u8", ".mp4", ".webm", ".mkv", ".flv", ".ts")
        private val PLAYER_JSON: Pattern = Pattern.compile(
            """player_aaaa\s*=\s*(\{.*?\})\s*</script>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
        private val DIRECT_MEDIA: Pattern = Pattern.compile(
            """https?://[^\s"'<>]+?\.(?:mp4|m3u8|webm|mkv|flv)(?:\?[^\s"'<>]*)?""",
            Pattern.CASE_INSENSITIVE
        )
        private val SRC_ATTR: Pattern = Pattern.compile(
            """(?i)<(?:video|source)[^>]+src=["']([^"']+)["']"""
        )
        private val DETAIL_HREF: Pattern = Pattern.compile(
            """(?is)<a[^>]+href=["']([^"']+/v/id-\d+\.html)["'][^>]*>(.*?)</a>"""
        )
        private val DETAIL_PLAIN: Pattern = Pattern.compile(
            """href=["']([^"']*/v/id-\d+\.html)["']""",
            Pattern.CASE_INSENSITIVE
        )
        private val PLAY_HREF: Pattern = Pattern.compile(
            """href=["']([^"']*/p/id-\d+-\d+-\d+\.html)["']""",
            Pattern.CASE_INSENSITIVE
        )
        private val TITLE: Pattern = Pattern.compile(
            """<title>([^<]+)</title>""",
            Pattern.CASE_INSENSITIVE
        )
    }
}
''', encoding='utf-8', newline='\n')

(JAVA / "core/sniff/VideoDownloader.kt").write_text(r'''package com.localplay.app.core.sniff

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

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
            target.stream.close()
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
            target.stream.close()
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
            val existing = tree.findFile(fileName)
            existing?.delete()
            val created = tree.createFile(mime, fileName.substringBeforeLast('.'))
                ?: throw IllegalStateException("无法在所选目录创建文件")
            val stream = context.contentResolver.openOutputStream(created.uri, "w")
                ?: throw IllegalStateException("无法写入所选目录")
            return OutputTarget(created.uri, stream)
        }

        val dir = defaultDir()
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建默认下载目录")
        }
        val file = File(dir, fileName)
        return OutputTarget(Uri.fromFile(file), FileOutputStream(file))
    }

    fun defaultDir(): File {
        val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(movies, "LocalPlayDownloads")
    }

    fun defaultDirPath(): String = defaultDir().absolutePath

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

    private data class OutputTarget(val uri: Uri, val stream: OutputStream)

    companion object {
        private const val TAG = "VideoDownloader"
    }
}
''', encoding='utf-8', newline='\n')

print('downloader ok')
