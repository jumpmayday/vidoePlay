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
    val patterns = listOf(
        Regex("""第\s*0*(\d+)\s*[集话期]"""),
        Regex("""(?i)s\d{1,2}\s*e\s*0*(\d+)"""),
        Regex("""(?i)(?:^|[_\-\s.\[(])(?:ep|e)\s*0*(\d+)(?:$|[_\-\s.\])])"""),
        Regex("""(?i)episode\s*0*(\d+)""")
    )
    for (p in patterns) {
        val m = p.find(name) ?: continue
        val n = m.groupValues.getOrNull(1)?.toIntOrNull()
        if (n != null) return n
    }
    return Int.MAX_VALUE
}

fun List<VideoItem>.sortedByOption(option: SortOption): List<VideoItem> {
    return when (option) {
        SortOption.EPISODE -> sortedWith(
            compareBy<VideoItem> { episodeOrdinal(it.displayName) }
                .thenBy { it.displayName.lowercase() }
        )
        SortOption.NAME -> sortedBy { it.displayName.lowercase() }
        SortOption.SIZE -> sortedByDescending { it.sizeBytes }
        SortOption.DURATION -> sortedByDescending { it.durationMs }
        SortOption.DATE_MODIFIED -> sortedByDescending { it.dateModified }
    }
}
