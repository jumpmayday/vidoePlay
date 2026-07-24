# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")

(JAVA / "core/sniff/ChallengedHttpClient.kt").write_text(r'''package com.localplay.app.core.sniff

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * HTTP client that can pass simple JS cookie gates and Volcano Engine UA challenges
 * (document.cookie reload + ge_ua_p sum POST) used by some video sites.
 */
class ChallengedHttpClient(
    private val client: OkHttpClient = defaultClient()
) {
    fun getHtml(url: String, maxRounds: Int = 24): String {
        repeat(maxRounds) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful && body.isBlank()) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val setCookie = COOKIE_JS.matcher(body)
                if (setCookie.find()) {
                    applyDocumentCookie(url, setCookie.group(1))
                    return@repeat
                }

                if (trySolveUaChallenge(url, body)) {
                    Thread.sleep(700L)
                    return@repeat
                }

                return body
            }
        }
        throw IllegalStateException("页面安全校验次数过多，请稍后重试或换直接视频链接")
    }

    fun copyTo(
        url: String,
        output: OutputStream,
        referer: String? = null,
        onBytes: ((Long, Long?) -> Unit)? = null
    ): Long {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败 HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("空响应")
            val total = body.contentLength().takeIf { it > 0 }
            var read = 0L
            body.source().use { source ->
                val sink = output.sink().buffer()
                while (true) {
                    val count = source.read(sink.buffer, 8_192L)
                    if (count < 0L) break
                    read += count
                    sink.emit()
                    onBytes?.invoke(read, total)
                }
                sink.flush()
            }
            return read
        }
    }

    fun getText(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("请求失败 HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun applyDocumentCookie(pageUrl: String, cookieAssign: String) {
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return
        val first = cookieAssign.split(";").firstOrNull().orEmpty()
        val idx = first.indexOf('=')
        if (idx <= 0) return
        val name = first.substring(0, idx).trim()
        val value = first.substring(idx + 1).trim()
        val cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .domain(httpUrl.host)
            .path("/")
            .build()
        MemoryCookieJar.put(httpUrl, cookie)
    }

    private fun trySolveUaChallenge(pageUrl: String, html: String): Boolean {
        val cpk = matchGroup(CPK, html) ?: return false
        val step = matchGroup(STEP, html) ?: return false
        val nonce = matchGroup(NONCE, html)?.toIntOrNull() ?: return false
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return false
        val cookieVal = MemoryCookieJar.loadForRequest(httpUrl)
            .firstOrNull { it.name == cpk }
            ?.value
            ?: return false

        var sum = 12345
        cookieVal.forEachIndexed { index, ch ->
            if (ch.isLetterOrDigit()) {
                sum += ch.code * (nonce + index)
            }
        }
        val body = FormBody.Builder()
            .add("sum", sum.toString())
            .add("nonce", nonce.toString())
            .build()
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-GE-UA-Step", step)
            .header("Referer", pageUrl)
            .post(body)
            .build()
        client.newCall(request).execute().use { /* consume */ }
        return true
    }

    private fun matchGroup(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private val COOKIE_JS: Pattern =
            Pattern.compile("""document\.cookie\s*=\s*"([^"]+)"""")
        private val CPK: Pattern = Pattern.compile("""var\s+cpk\s*=\s*"([^"]+)"""")
        private val STEP: Pattern = Pattern.compile("""var\s+step\s*=\s*"([^"]+)"""")
        private val NONCE: Pattern = Pattern.compile("""var\s+nonce\s*=\s*(\d+)""")

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(MemoryCookieJar)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}

object MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val hostMap = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { hostMap[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host]?.values?.toList().orEmpty()
    }

    fun put(url: HttpUrl, cookie: Cookie) {
        val hostMap = store.getOrPut(url.host) { ConcurrentHashMap() }
        hostMap[cookie.name] = cookie
    }
}
''', encoding='utf-8', newline='\n')

(JAVA / "core/sniff/VideoSniffer.kt").write_text(r'''package com.localplay.app.core.sniff

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.LinkedHashMap
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
        if (fromPlayer.isNotEmpty()) {
            return fromPlayer
        }

        val direct = extractDirectMediaUrls(html, normalized)
        if (direct.isNotEmpty()) {
            return direct
        }

        // MacCMS style: listing -> /v/ detail -> /p/ play
        val details = extractDetailLinks(html, normalized)
        if (details.isNotEmpty()) {
            onStatus("发现 ${details.size} 个影片页，开始解析播放地址…")
            return resolveDetails(details.take(maxDetailPages), onStatus)
        }

        val playLinks = extractPlayLinks(html, normalized)
        if (playLinks.isNotEmpty()) {
            onStatus("发现 ${playLinks.size} 个播放页…")
            return resolvePlayPages(playLinks, titleHint = extractTitle(html), onStatus = onStatus)
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
                val videos = resolvePlayPages(
                    playLinks = playLinks.take(2),
                    titleHint = title.ifBlank { extractTitle(detailHtml) },
                    onStatus = {}
                )
                videos.forEach { video ->
                    result.putIfAbsent(video.mediaUrl, video)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "resolve detail failed: $detailUrl", e)
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
                extractFromPlayerJson(html, playUrl, titleHint).forEach { video ->
                    result.putIfAbsent(video.mediaUrl, video)
                }
                extractDirectMediaUrls(html, playUrl, titleHint).forEach { video ->
                    result.putIfAbsent(video.mediaUrl, video)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "resolve play failed: $playUrl", e)
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
            val rawUrl = json.optString("url")
            val media = decodePlayerUrl(encrypt, rawUrl)
            if (media.isBlank() || (!isDirectMedia(media) && !media.startsWith("http"))) {
                emptyList()
            } else {
                val vodName = json.optJSONObject("vod_data")?.optString("vod_name").orEmpty()
                val from = json.optString("from")
                listOf(
                    SniffedVideo(
                        title = vodName.ifBlank { titleHint.ifBlank { extractTitle(html) } },
                        pageUrl = pageUrl,
                        mediaUrl = media,
                        sourceLabel = from.ifBlank { "播放器" }
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "parse player json failed", e)
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
        // <source src> / <video src>
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
        val matcher = DETAIL_HREF.matcher(html)
        while (matcher.find()) {
            val href = absolutize(pageUrl, matcher.group(1))
            if (!DETAIL_PATH.matcher(href).find()) continue
            val title = cleanText(matcher.group(2)).ifBlank {
                // try nearby title from title attr earlier in tag - fallback id
                href.substringAfterLast('/').substringBefore('.')
            }
            result.putIfAbsent(href, title)
        }
        // also plain /v/id-xxx.html anchors without text capture
        val plain = Pattern.compile("""href=["']([^"']*/v/id-\d+\.html)["']""", Pattern.CASE_INSENSITIVE)
            .matcher(html)
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
        var url = raw.replace("\\/", "/")
        return try {
            when (encrypt) {
                1 -> URLDecoder.decode(url, "UTF-8")
                2 -> String(Base64.decode(url, Base64.DEFAULT), Charsets.UTF_8)
                else -> url
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "decode player url failed", e)
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
        } catch (e: Exception) {
            "video"
        }
    }

    private fun absolutize(baseUrl: String, href: String): String {
        val cleaned = href.trim().replace("\\/", "/")
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        val base = baseUrl.toHttpUrlSafe() ?: return cleaned
        return if (cleaned.startsWith("//")) {
            base.scheme + ":" + cleaned
        } else if (cleaned.startsWith("/")) {
            "${base.scheme}://${base.host}$cleaned"
        } else {
            val parent = baseUrl.substringBeforeLast('/') + "/"
            parent + cleaned
        }
    }

    private fun String.toHttpUrlSafe(): URI? = try {
        URI(this)
    } catch (e: Exception) {
        null
    }

    private data class HostParts(val scheme: String, val host: String)

    private val URI.schemeHost: HostParts
        get() = HostParts(scheme ?: "https", host ?: "")

    // helpers to keep absolutize readable
    private val URI.scheme: String?
        get() = this.scheme
    private val URI.host: String?
        get() = this.host

    companion object {
        private const val TAG = "VideoSniffer"
        private val MEDIA_EXT = listOf(".m3u8", ".mp4", ".webm", ".mkv", ".flv", ".ts")
        private val PLAYER_JSON: Pattern =
            Pattern.compile("""player_aaaa\s*=\s*(\{.*?\})\s*</script>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        private val DIRECT_MEDIA: Pattern =
            Pattern.compile(
                """https?://[^\s"'<>]+?\.(?:mp4|m3u8|webm|mkv|flv)(?:\?[^\s"'<>]*)?""",
                Pattern.CASE_INSENSITIVE
            )
        private val SRC_ATTR: Pattern =
            Pattern.compile(
                """(?i)<(?:video|source)[^>]+src=["']([^"']+)["']"""
            )
        private val DETAIL_HREF: Pattern =
            Pattern.compile(
                """(?is)<a[^>]+href=["']([^"']+/v/id-\d+\.html)["'][^>]*>(.*?)</a>"""
            )
        private val DETAIL_PATH: Pattern =
            Pattern.compile("""/v/id-\d+\.html""", Pattern.CASE_INSENSITIVE)
        private val PLAY_HREF: Pattern =
            Pattern.compile(
                """href=["']([^"']*/p/id-\d+-\d+-\d+\.html)["']""",
                Pattern.CASE_INSENSITIVE
            )
        private val TITLE: Pattern =
            Pattern.compile("""<title>([^<]+)</title>""", Pattern.CASE_INSENSITIVE)
    }
}

private fun URI.schemeHostPair(): Pair<String, String> = (scheme ?: "https") to (host ?: "")
''', encoding='utf-8', newline='\n')

print('sniffer written')
