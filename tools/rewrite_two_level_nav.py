# -*- coding: utf-8 -*-
"""Rewrite home as two-level navigation + insets + batch delete."""
from pathlib import Path

ROOT = Path(r"D:\code\stu\videoPlay")
JAVA = ROOT / "app/src/main/java/com/localplay/app"


def w(rel: str, content: str) -> None:
    path = JAVA / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.strip() + "\n", encoding="utf-8", newline="\n")
    print("wrote", path.relative_to(ROOT))


# --- models ---
w(
    "data/model/VideoModels.kt",
    r'''
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
''',
)

# --- repository patch via full rewrite of rebuild + helpers ---
repo = (JAVA / "data/repository/VideoRepository.kt").read_text(encoding="utf-8")
# Remove expandedFolders / toggleFolder and update rebuild + add helpers
repo = repo.replace(
    '''    private var query: String = ""
    /** Whitelist: folders start collapsed so the home page shows directories first. */
    private val expandedFolders = mutableSetOf<String>()
''',
    '''    private var query: String = ""
''',
)
# remove toggleFolder function
import re
repo = re.sub(
    r"\n    fun toggleFolder\(key: String\) \{.*?\n    \}\n",
    "\n",
    repo,
    count=1,
    flags=re.S,
)
# replace findByPath section to add findFolder
if "fun findFolder(" not in repo:
    repo = repo.replace(
        "fun findByPath(path: String): VideoItem? = cachedVideos.firstOrNull { it.path == path }",
        '''fun findByPath(path: String): VideoItem? = cachedVideos.firstOrNull { it.path == path }

    fun findFolder(key: String): FolderGroup? = _folders.value.firstOrNull { it.key == key }

    fun videosInFolder(folderKey: String): List<VideoItem> {
        return cachedVideos.filter { it.folderKey == folderKey }
    }''',
    )
# add deleteVideos after deleteVideo
if "suspend fun deleteVideos" not in repo:
    repo = repo.replace(
        "    private fun rebuildFolders() {",
        '''    suspend fun deleteVideos(videos: List<VideoItem>): Int {
        var deletedCount = 0
        videos.forEach { video ->
            if (deleteVideo(video)) {
                deletedCount++
            }
        }
        return deletedCount
    }

    private fun rebuildFolders() {''',
    )
# replace rebuildFolders body
repo = re.sub(
    r"private fun rebuildFolders\(\) \{.*\}\n\}\s*\Z",
    r'''private fun rebuildFolders() {
        val filtered = cachedVideos.filter { video ->
            query.isBlank() ||
                video.displayName.contains(query, ignoreCase = true) ||
                video.folderName.contains(query, ignoreCase = true)
        }
        val sorted = when (sortOption) {
            SortOption.NAME -> filtered.sortedBy { it.displayName.lowercase() }
            SortOption.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            SortOption.DURATION -> filtered.sortedByDescending { it.durationMs }
            SortOption.DATE_MODIFIED -> filtered.sortedByDescending { it.dateModified }
        }
        _folders.value = sorted
            .groupBy { it.folderKey }
            .entries
            .sortedBy { (_, videos) -> videos.first().folderName.lowercase() }
            .map { (key, videos) ->
                FolderGroup(
                    key = key,
                    name = videos.first().folderName,
                    videos = videos
                )
            }
    }
}
''',
    repo,
    count=1,
    flags=re.S,
)
(JAVA / "data/repository/VideoRepository.kt").write_text(repo, encoding="utf-8", newline="\n")
print("updated VideoRepository")

print("base done")
