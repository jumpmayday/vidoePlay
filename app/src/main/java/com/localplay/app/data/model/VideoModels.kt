package com.localplay.app.data.model

data class VideoItem(
    val id: Long,
    val uri: String,
    val path: String,
    val displayName: String,
    val folderName: String,
    val folderKey: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val dateModified: Long,
    val progressMs: Long = 0L,
    val lastPlayedAt: Long = 0L
) {
    val formatLabel: String
        get() = displayName.substringAfterLast('.', "VID").uppercase().take(5)

    val progressRatio: Float
        get() = if (durationMs <= 0L) 0f else (progressMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val isCompleted: Boolean
        get() = progressRatio >= 0.95f

    val canResume: Boolean
        get() = progressMs > 0L && !isCompleted
}

data class FolderGroup(
    val key: String,
    val name: String,
    val videos: List<VideoItem>
) {
    val videoCount: Int get() = videos.size
    val coverUri: String? get() = videos.firstOrNull()?.uri
}

enum class SortOption {
    /** Prefer 第N集 / SxxExx / Exx extracted from filename. */
    EPISODE,
    NAME,
    SIZE,
    DURATION,
    DATE_MODIFIED
}

fun SortOption.label(): String = when (this) {
    SortOption.EPISODE -> "按集数"
    SortOption.NAME -> "按名称"
    SortOption.SIZE -> "按大小"
    SortOption.DURATION -> "按时长"
    SortOption.DATE_MODIFIED -> "按修改时间"
}

/**
 * Extract episode ordinal from titles like 第05集 / S01E02 / EP03 / E7.
 * Returns [Int.MAX_VALUE] when no episode marker is found.
 */
fun episodeOrdinal(name: String): Int {
    val normalized = name
        .replace('０', '0').replace('１', '1').replace('２', '2').replace('３', '3')
        .replace('４', '4').replace('５', '5').replace('６', '6').replace('７', '7')
        .replace('８', '8').replace('９', '9')
    val patterns = listOf(
        // Prefer explicit 第N集 / 第N话 — must win over loose "E10" patterns.
        Regex("""第\s*0*(\d+)\s*[集话期回]"""),
        Regex("""(?i)(?:^|[^a-z0-9])s\d{1,2}\s*[_.\-\s]*e\s*0*(\d+)(?:[^a-z0-9]|$)"""),
        Regex("""(?i)(?:^|[^a-z0-9])(?:ep|episode)\s*0*(\d+)(?:[^a-z0-9]|$)"""),
        Regex("""(?i)(?:^|[^a-z0-9])e\s*0*(\d{1,3})(?:[^a-z0-9]|$)"""),
        // Bare trailing episode numbers: name_10.ts / name-10.mp4
        Regex("""(?:^|[_\-\s.])0*(\d{1,3})(?:\.[a-z0-9]{2,4})$""", RegexOption.IGNORE_CASE)
    )
    for (p in patterns) {
        val m = p.find(normalized) ?: continue
        val n = m.groupValues.getOrNull(1)?.toIntOrNull()
        if (n != null && n in 1..9999) return n
    }
    return Int.MAX_VALUE
}

/** Compare strings so "第10集" sorts after "第5集" even without episode tags. */
fun naturalCompare(a: String, b: String): Int {
    val ra = Regex("""\d+|\D+""")
    val pa = ra.findAll(a.lowercase()).map { it.value }.toList()
    val pb = ra.findAll(b.lowercase()).map { it.value }.toList()
    val n = minOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa[i]
        val y = pb[i]
        val cmp = if (x[0].isDigit() && y[0].isDigit()) {
            x.toBigInteger().compareTo(y.toBigInteger())
        } else {
            x.compareTo(y)
        }
        if (cmp != 0) return cmp
    }
    return pa.size.compareTo(pb.size)
}

fun List<VideoItem>.sortedByOption(option: SortOption): List<VideoItem> {
    return when (option) {
        SortOption.EPISODE -> sortedWith(
            compareBy<VideoItem> { episodeOrdinal(it.displayName) }
                .thenComparing { a, b -> naturalCompare(a.displayName, b.displayName) }
        )
        SortOption.NAME -> sortedWith { a, b -> naturalCompare(a.displayName, b.displayName) }
        SortOption.SIZE -> sortedByDescending { it.sizeBytes }
        SortOption.DURATION -> sortedByDescending { it.durationMs }
        SortOption.DATE_MODIFIED -> sortedByDescending { it.dateModified }
    }
}
