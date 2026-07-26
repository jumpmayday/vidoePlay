package com.localplay.app.core.sniff

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

        val showTitle = extractShowTitle(html).ifBlank { extractTitle(html) }

        // Thunder / direct episode downloads on detail pages (often already episode-labeled).
        val thunder = extractThunderEpisodes(html, normalized, showTitle)
        if (thunder.isNotEmpty()) {
            onStatus("校验迅雷分集直链…")
            val verified = verifyVideosParallel(thunder, onStatus)
            if (verified.isNotEmpty()) {
                onStatus("迅雷直链可用 ${verified.size} 集")
                return verified.sortedWith(compareBy({ episodeSortKey(it.episodeLabel) }, { it.title }))
            }
        }

        // TV / multi-source playlists: pick one reachable线路, keep all episodes.
        val seriesEps = extractPlayEpisodes(html, normalized)
        if (seriesEps.isNotEmpty()) {
            onStatus("发现 ${seriesEps.groupBy { it.sourceIndex }.size} 条线路，正在筛选可用源…")
            val series = resolveBestSeriesSource(seriesEps, showTitle, onStatus)
            if (series.isNotEmpty()) return series
        }

        val playLinks = extractPlayLinks(html, normalized)
        if (playLinks.isNotEmpty()) {
            onStatus("发现 ${playLinks.size} 个播放页…")
            val fromPlay = resolvePlayPages(
                playLinks = playLinks.distinct().take(6),
                titleHint = showTitle,
                onStatus = onStatus
            )
            if (fromPlay.isNotEmpty()) return fromPlay
        }

        val direct = extractDirectMediaUrls(html, normalized, showTitle)
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
                    onStatus("接口解析到 ${result.size} 个地址，正在校验…")
                    val verified = verifyVideosParallel(result.values.toList(), onStatus)
                    val series = preferOneSourcePerEpisode(verified)
                    if (series.isNotEmpty()) return series
                    if (verified.isNotEmpty()) return verified
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
                val episodeLabel = normalizeEpisodeLabel(epName)
                val fullTitle = buildEpisodeTitle(
                    showTitle = title.ifBlank { "视频" },
                    episodeLabel = episodeLabel.ifBlank { epName }
                )
                result.putIfAbsent(
                    media,
                    SniffedVideo(
                        title = fullTitle,
                        pageUrl = pageUrl,
                        mediaUrl = media,
                        sourceLabel = "线路${sourceIndex + 1}",
                        episodeLabel = episodeLabel
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
        val done = AtomicInteger(0)
        val total = playLinks.size
        val collected = mapParallel(playLinks) { playUrl ->
            val n = done.incrementAndGet()
            onStatus("读取播放源 $n/$total")
            try {
                val html = http.getHtml(playUrl)
                extractFromPlayerJson(html, playUrl, titleHint) +
                    extractDirectMediaUrls(html, playUrl, titleHint)
            } catch (e: Exception) {
                Log.w(TAG, "resolve play failed: $playUrl", e)
                emptyList()
            }
        }.flatten()
        return verifyVideosParallel(collected, onStatus)
    }

    private data class PlayEpisode(
        val sourceIndex: Int,
        val sourceId: Int,
        val sourceName: String,
        val preferred: Boolean,
        val episodeId: Int,
        val episodeLabel: String,
        val playUrl: String
    )

    /**
     * Parse `#play` tabs + `#playlist .videolist` pairs (huanyuxing / similar MacCMS skins).
     */
    private fun extractPlayEpisodes(html: String, pageUrl: String): List<PlayEpisode> {
        val tabHtml = matchGroup(PLAY_UL, html) ?: return emptyList()
        val sourceNames = mutableListOf<Pair<String, Boolean>>()
        val li = PLAY_LI.matcher(tabHtml)
        while (li.find()) {
            val attrs = li.group(1).orEmpty()
            val preferred = attrs.contains("this", ignoreCase = true)
            val name = cleanText(li.group(2).orEmpty()).ifBlank { "线路${sourceNames.size + 1}" }
            sourceNames += name to preferred
        }
        if (sourceNames.isEmpty()) return emptyList()

        val lists = mutableListOf<String>()
        val listMatcher = VIDEO_LIST.matcher(html)
        while (listMatcher.find()) {
            lists += listMatcher.group(1).orEmpty()
        }
        if (lists.isEmpty()) return emptyList()

        val result = ArrayList<PlayEpisode>()
        val count = minOf(sourceNames.size, lists.size)
        for (index in 0 until count) {
            val (sourceName, preferred) = sourceNames[index]
            val anchor = PLAY_EP_ANCHOR.matcher(lists[index])
            while (anchor.find()) {
                val href = absolutize(pageUrl, anchor.group(1).orEmpty())
                val sourceId = anchor.group(3)?.toIntOrNull() ?: (index + 1)
                val episodeId = anchor.group(4)?.toIntOrNull() ?: continue
                val titleAttr = cleanText(anchor.group(5).orEmpty())
                val inner = cleanText(anchor.group(6).orEmpty())
                val episodeLabel = normalizeEpisodeLabel(inner.ifBlank { titleAttr })
                    .ifBlank { "第${episodeId.toString().padStart(2, '0')}集" }
                result += PlayEpisode(
                    sourceIndex = index,
                    sourceId = sourceId,
                    sourceName = sourceName,
                    preferred = preferred,
                    episodeId = episodeId,
                    episodeLabel = episodeLabel,
                    playUrl = href
                )
            }
        }
        return result.distinctBy { "${it.sourceIndex}-${it.episodeId}-${it.playUrl}" }
    }

    private fun extractThunderEpisodes(
        html: String,
        pageUrl: String,
        showTitle: String
    ): List<SniffedVideo> {
        val block = matchGroup(DOWN_LIST, html) ?: html
        val result = LinkedHashMap<String, SniffedVideo>()
        val matcher = THUNDER_EP.matcher(block)
        while (matcher.find()) {
            val epRaw = cleanText(matcher.group(1).orEmpty())
            val media = absolutize(pageUrl, matcher.group(2).orEmpty().replace("\\/", "/"))
            if (!isDirectMedia(media)) continue
            val episodeLabel = normalizeEpisodeLabel(epRaw)
                .ifBlank { guessEpisodeFromUrl(media) }
            result.putIfAbsent(
                media,
                SniffedVideo(
                    title = buildEpisodeTitle(showTitle, episodeLabel),
                    pageUrl = pageUrl,
                    mediaUrl = media,
                    sourceLabel = "迅雷下载",
                    episodeLabel = episodeLabel
                )
            )
        }
        return result.values.toList()
    }

    private fun resolveBestSeriesSource(
        episodes: List<PlayEpisode>,
        showTitle: String,
        onStatus: (String) -> Unit
    ): List<SniffedVideo> {
        val bySource = episodes.groupBy { it.sourceIndex }
            .toSortedMap()
        if (bySource.isEmpty()) return emptyList()

        data class Probe(
            val sourceIndex: Int,
            val sourceName: String,
            val preferred: Boolean,
            val sample: PlayEpisode,
            val mediaUrl: String,
            val from: String,
            val ok: Boolean
        )

        // Probe first episode of each source in parallel.
        val probes = mapParallel(bySource.entries.toList()) { (index, eps) ->
            val sample = eps.minByOrNull { it.episodeId } ?: return@mapParallel null
            try {
                val playHtml = http.getHtml(sample.playUrl)
                val parsed = extractPlayerMedia(playHtml) ?: return@mapParallel Probe(
                    sourceIndex = index,
                    sourceName = sample.sourceName,
                    preferred = sample.preferred,
                    sample = sample,
                    mediaUrl = "",
                    from = "",
                    ok = false
                )
                val ok = http.probeMediaOk(parsed.first, sample.playUrl)
                val durationOk = if (ok && parsed.first.contains(".m3u8", ignoreCase = true)) {
                    http.probeHlsDurationSec(parsed.first, sample.playUrl) >= MIN_HLS_DURATION_SEC
                } else {
                    ok
                }
                Probe(
                    sourceIndex = index,
                    sourceName = sample.sourceName,
                    preferred = sample.preferred,
                    sample = sample,
                    mediaUrl = parsed.first,
                    from = parsed.second,
                    ok = durationOk
                )
            } catch (e: Exception) {
                Log.i(TAG, "probe source ${sample.sourceName} failed: ${e.message}")
                Probe(
                    sourceIndex = index,
                    sourceName = sample.sourceName,
                    preferred = sample.preferred,
                    sample = sample,
                    mediaUrl = "",
                    from = "",
                    ok = false
                )
            }
        }.filterNotNull()

        val good = probes.filter { it.ok }
        if (good.isEmpty()) {
            onStatus("播放线路暂不可用")
            return emptyList()
        }

        val best = good.maxByOrNull { score ->
            var s = scoreSource(score.from, score.mediaUrl)
            // Prefer marked tab only when it also scored well; dead "this" lines (bfzy 404) lose.
            if (score.preferred) s += 5
            s
        } ?: good.first()

        onStatus("选用线路「${best.sourceName}」，解析分集…")
        val selectedEps = bySource[best.sourceIndex]
            ?.sortedBy { it.episodeId }
            .orEmpty()
        if (selectedEps.isEmpty()) return emptyList()

        val done = AtomicInteger(0)
        val total = selectedEps.size
        val videos = mapParallel(selectedEps) { ep ->
            val n = done.incrementAndGet()
            onStatus("解析 ${best.sourceName} $n/$total · ${ep.episodeLabel}")
            try {
                val playHtml = http.getHtml(ep.playUrl)
                val media = extractPlayerMedia(playHtml)?.first
                    ?: return@mapParallel null
                if (!http.probeMediaOk(media, ep.playUrl)) return@mapParallel null
                SniffedVideo(
                    title = buildEpisodeTitle(showTitle, ep.episodeLabel),
                    pageUrl = ep.playUrl,
                    mediaUrl = media,
                    sourceLabel = best.sourceName,
                    episodeLabel = ep.episodeLabel
                )
            } catch (e: Exception) {
                Log.w(TAG, "episode resolve failed: ${ep.playUrl}", e)
                null
            }
        }.filterNotNull()

        return videos.sortedWith(compareBy({ episodeSortKey(it.episodeLabel) }, { it.title }))
    }

    private fun extractPlayerMedia(html: String): Pair<String, String>? {
        val matcher = PLAYER_JSON.matcher(html)
        if (!matcher.find()) return null
        return try {
            val json = JSONObject(matcher.group(1))
            val encrypt = json.optInt("encrypt", 0)
            val media = decodePlayerUrl(encrypt, json.optString("url"))
            if (media.isBlank() || (!isDirectMedia(media) && !media.startsWith("http"))) {
                null
            } else {
                media to json.optString("from")
            }
        } catch (e: Exception) {
            Log.w(TAG, "extract player media failed", e)
            null
        }
    }

    private fun verifyVideosParallel(
        videos: List<SniffedVideo>,
        onStatus: (String) -> Unit
    ): List<SniffedVideo> {
        if (videos.isEmpty()) return emptyList()
        if (videos.size == 1) {
            return if (http.probeMediaOk(videos[0].mediaUrl, videos[0].pageUrl)) videos else emptyList()
        }
        val done = AtomicInteger(0)
        val total = videos.size
        return mapParallel(videos) { video ->
            val n = done.incrementAndGet()
            if (n == 1 || n == total || n % 3 == 0) {
                onStatus("校验媒体 $n/$total")
            }
            if (http.probeMediaOk(video.mediaUrl, video.pageUrl)) video else null
        }.filterNotNull()
    }

    /** Keep one working media per episode (prefer higher score source labels). */
    private fun preferOneSourcePerEpisode(videos: List<SniffedVideo>): List<SniffedVideo> {
        if (videos.isEmpty()) return emptyList()
        val grouped = videos.groupBy {
            it.episodeLabel.ifBlank { it.title }
        }
        if (grouped.size <= 1 && videos.size <= 2) return videos
        return grouped.values.map { group ->
            group.maxByOrNull { scoreSource(it.sourceLabel, it.mediaUrl) } ?: group.first()
        }.sortedWith(compareBy({ episodeSortKey(it.episodeLabel) }, { it.title }))
    }

    private fun scoreSource(from: String, mediaUrl: String): Int {
        var score = 0
        val f = from.lowercase()
        val u = mediaUrl.lowercase()
        if (f.contains("1080") || u.contains("1080")) score += 30
        if (f.contains("ff") || f.contains("wj") || f.contains("zuida")) score += 15
        if (f.contains("zy") && !f.contains("bfzy")) score += 10
        if (u.contains(".m3u8")) score += 5
        // 暴风源 often uses Chinese path segments that 404; demote hard.
        if (f.contains("bfzy") || u.contains("rrcdnbf") || u.contains("bfzy")) score -= 40
        if (u.contains("404") || f.contains("lz")) score -= 10
        // Non-ASCII path frequently breaks on some CDNs.
        if (mediaUrl.any { it.code > 0x7F }) score -= 8
        return score
    }

    private fun <T, R> mapParallel(
        items: List<T>,
        parallelism: Int = SNIFF_PARALLEL,
        block: (T) -> R
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        if (items.size == 1) return listOf(block(items.first()))
        val pool = Executors.newFixedThreadPool(parallelism.coerceAtMost(items.size).coerceAtLeast(1))
        return try {
            val futures = items.map { item ->
                pool.submit<R> { block(item) }
            }
            futures.map { it.get(45, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun extractFromPlayerJson(
        html: String,
        pageUrl: String,
        titleHint: String = "",
        episodeLabel: String = ""
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
                val show = vodName.ifBlank { titleHint.ifBlank { extractTitle(html) } }
                val ep = episodeLabel.ifBlank { guessEpisodeFromUrl(media) }
                listOf(
                    SniffedVideo(
                        title = buildEpisodeTitle(show, ep),
                        pageUrl = pageUrl,
                        mediaUrl = media,
                        sourceLabel = json.optString("from").ifBlank { "播放器" },
                        episodeLabel = ep
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

    private fun extractShowTitle(html: String): String {
        matchGroup(OG_NAME, html)?.let { name ->
            val cleaned = cleanText(name)
            if (cleaned.isNotBlank()) return cleaned
        }
        matchGroup(H1_TITLE, html)?.let { h1 ->
            val cleaned = cleanText(h1)
            if (cleaned.isNotBlank()) return cleaned
        }
        return extractTitle(html)
    }

    private fun buildEpisodeTitle(showTitle: String, episodeLabel: String): String {
        val show = showTitle.trim().ifBlank { "视频" }
        val ep = episodeLabel.trim()
        if (ep.isBlank()) return show
        return if (show.contains(ep)) show else "$show $ep"
    }

    private fun normalizeEpisodeLabel(raw: String): String {
        val text = cleanText(raw)
        if (text.isBlank()) return ""
        val m = EPISODE_NUM.matcher(text)
        if (m.find()) {
            val num = m.group(1)?.toIntOrNull() ?: m.group(2)?.toIntOrNull()
            if (num != null) return "第${num.toString().padStart(2, '0')}集"
            return text.take(16)
        }
        if (text.contains("集") || text.contains("期") || text.contains("话")) {
            return text.take(16)
        }
        return ""
    }

    private fun guessEpisodeFromUrl(url: String): String {
        val m = EPISODE_NUM.matcher(url)
        if (m.find()) {
            val num = m.group(1)?.toIntOrNull() ?: m.group(2)?.toIntOrNull() ?: return ""
            return "第${num.toString().padStart(2, '0')}集"
        }
        return ""
    }

    private fun episodeSortKey(label: String): Int {
        val m = EPISODE_NUM.matcher(label)
        if (!m.find()) return Int.MAX_VALUE
        return m.group(1)?.toIntOrNull() ?: m.group(2)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun matchGroup(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
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
        private const val SNIFF_PARALLEL = 6
        /** Reject preview / broken HLS variants under ~1 minute. */
        private const val MIN_HLS_DURATION_SEC = 60.0
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

        private val PLAY_UL: Pattern = Pattern.compile(
            """(?is)<ul[^>]*\bid=["']play["'][^>]*>(.*?)</ul>"""
        )
        private val PLAY_LI: Pattern = Pattern.compile(
            """(?is)<li([^>]*)>(.*?)</li>"""
        )
        private val VIDEO_LIST: Pattern = Pattern.compile(
            """(?is)<div[^>]*class=["'][^"']*\bvideolist\b[^"']*["'][^>]*>(.*?)</div>"""
        )
        private val PLAY_EP_ANCHOR: Pattern = Pattern.compile(
            """(?is)<a[^>]+href=["']([^"']*/p/id-(\d+)-(\d+)-(\d+)\.html)["'][^>]*(?:title=["']([^"']*)["'])?[^>]*>(.*?)</a>"""
        )
        private val DOWN_LIST: Pattern = Pattern.compile(
            """(?is)<div[^>]*\bid=["']downlist["'][^>]*>(.*?)</div>\s*</div>"""
        )
        private val THUNDER_EP: Pattern = Pattern.compile(
            """(?is)<p>\s*(第[^<]*?)\s*<a[^>]+href=["'](https?://[^"']+\.(?:mp4|mkv|flv)[^"']*)["']"""
        )
        private val OG_NAME: Pattern = Pattern.compile(
            """(?is)itemprop=["']name["'][^>]*content=["']([^"']+)["']"""
        )
        private val H1_TITLE: Pattern = Pattern.compile(
            """(?is)<h1[^>]*>(.*?)</h1>"""
        )
        private val EPISODE_NUM: Pattern = Pattern.compile(
            """(?i)第\s*0*(\d+)\s*[集话期]|[_\-\s]0*(\d{1,3})(?=\.(?:mp4|m3u8|mkv|ts|html)|$)"""
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
