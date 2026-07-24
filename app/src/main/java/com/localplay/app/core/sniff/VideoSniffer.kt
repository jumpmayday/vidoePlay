package com.localplay.app.core.sniff

import android.util.Base64
import android.util.Log
import org.json.JSONArray
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

        // MacCMS JSON API first for list pages (works even when HTML structure differs).
        // Single detail pages (/v/id-N.html) need the UA cookie gate first — skip cold API.
        val detailVodId = extractDetailVodId(normalized)
        if (detailVodId == null) {
            val apiVideos = sniffViaMacCmsApi(normalized, onStatus)
            if (apiVideos.isNotEmpty()) return apiVideos
        }

        val html = http.getHtml(normalized)
        if (looksLikeBlocked(html)) {
            throw IllegalStateException("站点拒绝访问（可能限制 IP / 地区），请换网络后重试")
        }

        // After challenge cookies exist, try MacCMS detail by vod id.
        if (detailVodId != null) {
            val origin = originOf(normalized)
            if (origin != null) {
                onStatus("拉取影片播放地址…")
                val fromDetailApi = fetchMacCmsDetails(
                    origin = origin,
                    ids = listOf(detailVodId),
                    pageUrl = normalized,
                    onStatus = onStatus
                )
                if (fromDetailApi.isNotEmpty()) return fromDetailApi
            }
        }

        val fromPlayer = extractFromPlayerJson(html, normalized)
        if (fromPlayer.isNotEmpty()) return fromPlayer

        // Detail pages: prefer play-page m3u8 over loose .mp4 strings in HTML.
        val playLinks = extractPlayLinks(html, normalized)
        if (playLinks.isNotEmpty()) {
            onStatus("发现 ${playLinks.size} 个播放源…")
            val fromPlay = resolvePlayPages(
                playLinks = playLinks.distinct().take(8),
                titleHint = extractTitle(html),
                onStatus = onStatus
            )
            if (fromPlay.isNotEmpty()) return fromPlay
        }

        val direct = extractDirectMediaUrls(html, normalized)
        if (direct.isNotEmpty()) return direct

        val details = extractDetailLinks(html, normalized)
        if (details.isNotEmpty()) {
            onStatus("发现 ${details.size} 个影片页，开始解析播放地址…")
            return resolveDetails(details.take(maxDetailPages), onStatus)
        }

        return emptyList()
    }

    private fun sniffViaMacCmsApi(
        pageUrl: String,
        onStatus: (String) -> Unit
    ): List<SniffedVideo> {
        val origin = originOf(pageUrl) ?: return emptyList()
        val typeId = extractListTypeId(pageUrl)
        val endpoints = buildList {
            if (typeId != null) {
                add("$origin/api.php/provide/vod/?ac=list&t=$typeId&pg=1")
                add("$origin/api.php/provide/vod/at/json/?ac=list&t=$typeId&pg=1")
                add("$origin/index.php/ajax/data.html?mid=1&tid=$typeId&page=1&limit=24")
            }
            // Some sites expose recent list without type.
            add("$origin/api.php/provide/vod/?ac=list&pg=1")
        }

        for (endpoint in endpoints) {
            try {
                onStatus("尝试接口嗅探…")
                val body = http.getText(endpoint, referer = pageUrl)
                if (body.isBlank() || looksLikeBlocked(body)) continue
                val videos = parseMacCmsListPayload(body, pageUrl, origin, onStatus)
                if (videos.isNotEmpty()) {
                    onStatus("接口嗅探到 ${videos.size} 个视频")
                    return videos
                }
            } catch (e: Exception) {
                Log.i(TAG, "api endpoint failed: $endpoint (${e.message})")
            }
        }
        return emptyList()
    }

    private fun parseMacCmsListPayload(
        body: String,
        pageUrl: String,
        origin: String,
        onStatus: (String) -> Unit
    ): List<SniffedVideo> {
        val trimmed = body.trim()
        val root = try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONObject().put("list", JSONArray(trimmed))
                else -> return emptyList()
            }
        } catch (_: Exception) {
            return emptyList()
        }

        val list = when {
            root.has("list") -> root.optJSONArray("list")
            root.has("data") && root.opt("data") is JSONArray -> root.optJSONArray("data")
            root.has("data") && root.optJSONObject("data")?.has("list") == true ->
                root.optJSONObject("data")?.optJSONArray("list")
            else -> null
        } ?: return emptyList()

        if (list.length() == 0) return emptyList()

        // If list items already include play urls, parse directly.
        val directFromList = LinkedHashMap<String, SniffedVideo>()
        val ids = mutableListOf<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("vod_id").ifBlank { item.optString("id") }
            val name = item.optString("vod_name").ifBlank { item.optString("name") }
            val playUrlField = item.optString("vod_play_url")
            if (playUrlField.isNotBlank()) {
                parsePlayUrlField(playUrlField, name, pageUrl).forEach {
                    directFromList.putIfAbsent(it.mediaUrl, it)
                }
            } else if (id.isNotBlank()) {
                ids += id
            }
        }
        if (directFromList.isNotEmpty()) return directFromList.values.toList()
        if (ids.isEmpty()) return emptyList()

        onStatus("拉取 ${ids.size.coerceAtMost(20)} 条播放地址…")
        return fetchMacCmsDetails(origin, ids.take(20), pageUrl, onStatus)
    }

    private fun fetchMacCmsDetails(
        origin: String,
        ids: List<String>,
        pageUrl: String,
        onStatus: (String) -> Unit
    ): List<SniffedVideo> {
        if (ids.isEmpty()) return emptyList()
        val detailIds = ids.joinToString(",")
        val detailEndpoints = listOf(
            "$origin/api.php/provide/vod/?ac=detail&ids=$detailIds",
            "$origin/api.php/provide/vod/at/json/?ac=detail&ids=$detailIds"
        )
        for (endpoint in detailEndpoints) {
            try {
                val detailBody = http.getText(endpoint, referer = pageUrl)
                if (detailBody.isBlank() || looksLikeBlocked(detailBody) || detailBody.trimStart().startsWith("<")) {
                    continue
                }
                val detailRoot = JSONObject(detailBody)
                val detailList = detailRoot.optJSONArray("list") ?: continue
                val result = LinkedHashMap<String, SniffedVideo>()
                for (i in 0 until detailList.length()) {
                    val item = detailList.optJSONObject(i) ?: continue
                    val name = item.optString("vod_name")
                    val playUrlField = item.optString("vod_play_url")
                    parsePlayUrlField(playUrlField, name, pageUrl).forEach {
                        result.putIfAbsent(it.mediaUrl, it)
                    }
                }
                if (result.isNotEmpty()) {
                    onStatus("接口解析到 ${result.size} 个地址")
                    return result.values.toList()
                }
            } catch (e: Exception) {
                Log.i(TAG, "detail api failed: $endpoint (${e.message})")
            }
        }
        return emptyList()
    }

    /**
     * MacCMS play field: source$$$source2  and episode$url#episode2$url2
     */
    private fun parsePlayUrlField(
        field: String,
        title: String,
        pageUrl: String
    ): List<SniffedVideo> {
        if (field.isBlank()) return emptyList()
        val result = LinkedHashMap<String, SniffedVideo>()
        val sources = field.split("$$$")
        sources.forEachIndexed { sourceIndex, sourceBlock ->
            sourceBlock.split("#").forEach { episode ->
                val parts = episode.split("$")
                if (parts.size < 2) return@forEach
                val epName = parts.first().trim()
                val media = parts.last().trim().replace("\\/", "/")
                if (!isDirectMedia(media) && !media.startsWith("http")) return@forEach
                val fullTitle = buildString {
                    append(title.ifBlank { "视频" })
                    if (epName.isNotBlank() && epName != title) append(" - ").append(epName)
                }
                result.putIfAbsent(
                    media,
                    SniffedVideo(
                        title = fullTitle,
                        pageUrl = pageUrl,
                        mediaUrl = media,
                        sourceLabel = "线路${sourceIndex + 1}"
                    )
                )
            }
        }
        return result.values.toList()
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
                if (playLinks.isEmpty()) {
                    // Some detail pages embed player json / direct media.
                    extractFromPlayerJson(detailHtml, detailUrl, title).forEach {
                        result.putIfAbsent(it.mediaUrl, it)
                    }
                    extractDirectMediaUrls(detailHtml, detailUrl, title).forEach {
                        result.putIfAbsent(it.mediaUrl, it)
                    }
                    return@forEachIndexed
                }
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

        // titled anchors with common MacCMS / custom SEO detail paths
        val titled = DETAIL_HREF.matcher(html)
        while (titled.find()) {
            val href = absolutize(pageUrl, titled.group(1))
            if (!isLikelyDetailUrl(href)) continue
            val title = cleanText(titled.group(3).orEmpty()).ifBlank {
                titled.group(2).orEmpty().ifBlank {
                    href.substringAfterLast('/').substringBefore('.')
                }
            }
            result.putIfAbsent(href, title)
        }

        val plain = DETAIL_PLAIN.matcher(html)
        while (plain.find()) {
            val href = absolutize(pageUrl, plain.group(1))
            if (!isLikelyDetailUrl(href)) continue
            result.putIfAbsent(href, href.substringAfterLast('/').substringBefore('.'))
        }

        // Generic same-host content cards for listnew pages.
        if (pageUrl.contains("listnew", ignoreCase = true) || pageUrl.contains("/list/", ignoreCase = true)) {
            val any = ANY_ANCHOR.matcher(html)
            while (any.find()) {
                val href = absolutize(pageUrl, any.group(1))
                if (!isSameHost(pageUrl, href)) continue
                if (!isLikelyDetailUrl(href)) continue
                val title = cleanText(any.group(3).orEmpty()).ifBlank {
                    any.group(2).orEmpty()
                }
                if (title.isBlank() && result.containsKey(href)) continue
                result.putIfAbsent(
                    href,
                    title.ifBlank { href.substringAfterLast('/').substringBefore('.') }
                )
            }
        }

        return result.entries.map { it.value to it.key }
    }

    private fun extractPlayLinks(html: String, pageUrl: String): List<String> {
        val result = LinkedHashSet<String>()
        val matcher = PLAY_HREF.matcher(html)
        while (matcher.find()) {
            val href = absolutize(pageUrl, matcher.group(1))
            if (isLikelyPlayUrl(href)) result += href
        }
        return result.toList()
    }

    private fun isLikelyDetailUrl(url: String): Boolean {
        val path = try {
            URI(url).path.orEmpty().lowercase()
        } catch (_: Exception) {
            url.lowercase()
        }
        if (path.contains("listnew") || path.contains("/list/") || path.contains("/type/")) return false
        if (path.contains("/user") || path.contains("/search") || path.contains("/label")) return false
        return DETAIL_PATH.matcher(path).find()
    }

    private fun isLikelyPlayUrl(url: String): Boolean {
        val path = try {
            URI(url).path.orEmpty().lowercase()
        } catch (_: Exception) {
            url.lowercase()
        }
        return PLAY_PATH.matcher(path).find()
    }

    private fun extractListTypeId(pageUrl: String): String? {
        val m = LIST_TYPE_ID.matcher(pageUrl)
        return if (m.find()) m.group(1) else null
    }

    /** `/v/id-205300.html` / `/voddetail/205300.html` style single-vod pages. */
    private fun extractDetailVodId(pageUrl: String): String? {
        val m = DETAIL_VOD_ID.matcher(pageUrl)
        return if (m.find()) m.group(1) else null
    }

    private fun originOf(pageUrl: String): String? {
        return try {
            val uri = URI(pageUrl)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            null
        }
    }

    private fun isSameHost(base: String, other: String): Boolean {
        return try {
            URI(base).host.equals(URI(other).host, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeBlocked(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("403 forbidden") ||
            lower.contains("your ip is not allowed") ||
            lower.contains("access denied")
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

        private val LIST_TYPE_ID: Pattern = Pattern.compile(
            """(?i)/(?:listnew|list|type|show)/(?:id-)?(\d+)(?:\.html|/)?"""
        )

        private val DETAIL_VOD_ID: Pattern = Pattern.compile(
            """(?i)/(?:v/id-|voddetail/|detail/|view/|movie/|video/|vod/|film/)(\d+)(?:\.html|/)?"""
        )

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

        // MacCMS + custom SEO detail pages (incl. huanyuxing /v/id- and cdpop-like routes)
        private val DETAIL_PATH: Pattern = Pattern.compile(
            """(?i)(?:/v/id-\d+\.html|/voddetail/\d+\.html|/detail/\d+\.html|/view/\d+\.html|/show/\d+\.html|/movie/\d+\.html|/video/\d+\.html|/vod/\d+\.html|/html/\d+\.html|/film/\d+\.html)"""
        )
        private val DETAIL_HREF: Pattern = Pattern.compile(
            """(?is)<a[^>]+href=["']([^"']+)["'][^>]*(?:title=["']([^"']*)["'])?[^>]*>(.*?)</a>"""
        )
        private val DETAIL_PLAIN: Pattern = Pattern.compile(
            """href=["']([^"']*(?:/v/id-\d+\.html|/voddetail/\d+\.html|/detail/\d+\.html|/view/\d+\.html|/show/\d+\.html|/movie/\d+\.html|/video/\d+\.html|/vod/\d+\.html|/html/\d+\.html))["']""",
            Pattern.CASE_INSENSITIVE
        )
        private val ANY_ANCHOR: Pattern = Pattern.compile(
            """(?is)<a[^>]+href=["']([^"']+)["'][^>]*(?:title=["']([^"']*)["'])?[^>]*>(.*?)</a>"""
        )

        private val PLAY_PATH: Pattern = Pattern.compile(
            """(?i)(?:/p/id-\d+-\d+-\d+\.html|/vodplay/\d+-\d+-\d+\.html|/play/\d+-\d+-\d+\.html|/video/play/|/play/)"""
        )
        private val PLAY_HREF: Pattern = Pattern.compile(
            """href=["']([^"']*(?:/p/id-\d+-\d+-\d+\.html|/vodplay/\d+-\d+-\d+\.html|/play/\d+-\d+-\d+\.html|/play/[^"']+|/video/play/[^"']+))["']""",
            Pattern.CASE_INSENSITIVE
        )
        private val TITLE: Pattern = Pattern.compile(
            """<title>([^<]+)</title>""",
            Pattern.CASE_INSENSITIVE
        )
    }
}
