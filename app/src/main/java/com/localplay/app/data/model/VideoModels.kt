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
    NAME, SIZE, DURATION, DATE_MODIFIED
}
