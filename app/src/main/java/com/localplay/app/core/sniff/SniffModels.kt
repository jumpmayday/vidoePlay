package com.localplay.app.core.sniff

data class SniffedVideo(
    val title: String,
    val pageUrl: String,
    val mediaUrl: String,
    val sourceLabel: String = "",
    /** Normalized episode tag such as 第01集; empty for movies / single files. */
    val episodeLabel: String = ""
) {
    val isHls: Boolean
        get() = mediaUrl.contains(".m3u8", ignoreCase = true)

    val suggestedFileName: String
        get() {
            val base = title
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifBlank { "video_${System.currentTimeMillis()}" }
            // Ensure episode appears in filename when known (helps TV downloads).
            val withEp = if (episodeLabel.isNotBlank() && !base.contains(episodeLabel)) {
                "${base}_$episodeLabel"
            } else {
                base
            }
            val safe = withEp.replace(' ', '_').take(120)
            val ext = when {
                isHls -> "ts"
                mediaUrl.contains(".webm", true) -> "webm"
                mediaUrl.contains(".mkv", true) -> "mkv"
                mediaUrl.contains(".flv", true) -> "flv"
                else -> "mp4"
            }
            return "$safe.$ext"
        }
}

sealed class SniffProgress {
    data object Idle : SniffProgress()
    data class Working(val message: String) : SniffProgress()
    data class Done(val items: List<SniffedVideo>) : SniffProgress()
    data class Error(val message: String) : SniffProgress()
}

data class DownloadProgress(
    val currentIndex: Int,
    val total: Int,
    val title: String,
    val fraction: Float,
    val message: String
)
