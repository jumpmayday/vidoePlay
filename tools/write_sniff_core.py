# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")
(JAVA / "core/sniff").mkdir(parents=True, exist_ok=True)
(JAVA / "feature/sniff").mkdir(parents=True, exist_ok=True)

(JAVA / "core/sniff/SniffModels.kt").write_text(r'''package com.localplay.app.core.sniff

data class SniffedVideo(
    val title: String,
    val pageUrl: String,
    val mediaUrl: String,
    val sourceLabel: String = ""
) {
    val isHls: Boolean
        get() = mediaUrl.contains(".m3u8", ignoreCase = true)

    val suggestedFileName: String
        get() {
            val safe = title
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .trim()
                .ifBlank { "video_${System.currentTimeMillis()}" }
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
''', encoding='utf-8', newline='\n')

print('models ok')
