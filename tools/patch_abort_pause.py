# -*- coding: utf-8 -*-
from pathlib import Path
import re

engine = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\download\ResumableDownloadEngine.kt")
t = engine.read_text(encoding="utf-8")

# Add exception class before class
if "class DownloadAbortedException" not in t:
    t = t.replace(
        "/**\n * Downloads into a local partial file with resume support, then publishes to the final destination.\n */\nclass ResumableDownloadEngine(",
        "class DownloadAbortedException : Exception(\"download aborted\")\n\n"
        "/**\n * Downloads into a local partial file with resume support, then publishes to the final destination.\n */\nclass ResumableDownloadEngine(",
    )

t = t.replace(
    """    fun download(
        task: DownloadTaskEntity,
        onProgress: (DownloadTaskEntity) -> Unit
    ): DownloadTaskEntity {
        return if (task.isHls) downloadHls(task, onProgress) else downloadProgressive(task, onProgress)
    }
""",
    """    fun download(
        task: DownloadTaskEntity,
        shouldAbort: () -> Boolean = { false },
        onProgress: (DownloadTaskEntity) -> Unit
    ): DownloadTaskEntity {
        return if (task.isHls) {
            downloadHls(task, shouldAbort, onProgress)
        } else {
            downloadProgressive(task, shouldAbort, onProgress)
        }
    }

    private fun checkAbort(shouldAbort: () -> Boolean) {
        if (shouldAbort()) throw DownloadAbortedException()
    }
""",
)

t = t.replace(
    "private fun downloadProgressive(\n        task: DownloadTaskEntity,\n        onProgress: (DownloadTaskEntity) -> Unit\n    )",
    "private fun downloadProgressive(\n        task: DownloadTaskEntity,\n        shouldAbort: () -> Boolean,\n        onProgress: (DownloadTaskEntity) -> Unit\n    )",
)
t = t.replace(
    "private fun downloadHls(\n        task: DownloadTaskEntity,\n        onProgress: (DownloadTaskEntity) -> Unit\n    )",
    "private fun downloadHls(\n        task: DownloadTaskEntity,\n        shouldAbort: () -> Boolean,\n        onProgress: (DownloadTaskEntity) -> Unit\n    )",
)

# Insert checkAbort in progressive loop before copyTo
if "checkAbort(shouldAbort)" not in t:
    t = t.replace(
        "        while (true) {\n            FileOutputStream(partial, offset > 0L).use { output ->",
        "        while (true) {\n            checkAbort(shouldAbort)\n            FileOutputStream(partial, offset > 0L).use { output ->",
    )
    t = t.replace(
        "                ) { absolute, total ->\n                    current = current.copy(\n                        downloadedBytes = absolute,",
        "                ) { absolute, total ->\n                    checkAbort(shouldAbort)\n                    current = current.copy(\n                        downloadedBytes = absolute,",
        1,  # only first in progressive - but HLS has similar - do replace_all carefully
    )

# For HLS for loop
t = t.replace(
    "            for (index in current.hlsSegmentIndex until segments.size) {\n                http.copyTo(",
    "            for (index in current.hlsSegmentIndex until segments.size) {\n                checkAbort(shouldAbort)\n                http.copyTo(",
)

engine.write_text(t, encoding="utf-8", newline="\n")
print("abort checks:", t.count("checkAbort"))

# Fix service notification icon + abort + pause set
svc = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\download\DownloadService.kt")
s = svc.read_text(encoding="utf-8")
s = s.replace(
    "import kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock\n",
    "import kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock\nimport java.util.concurrent.ConcurrentHashMap\n",
)
if "pausedIds" not in s:
    s = s.replace(
        "    private val engine by lazy { ResumableDownloadEngine(applicationContext) }\n",
        "    private val engine by lazy { ResumableDownloadEngine(applicationContext) }\n"
        "    private val pausedIds = ConcurrentHashMap.newKeySet<Long>()\n",
    )
s = s.replace("R.drawable.ic_launcher_foreground", "R.drawable.ic_launcher")
s = s.replace(
    """        dao.update(
            task.copy(
                status = DownloadStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )
        )
        updateNotification("已暂停：${task.title}", (task.progressFraction * 100).toInt(), false)
""",
    """        pausedIds.add(id)
        dao.update(
            task.copy(
                status = DownloadStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )
        )
        updateNotification("已暂停：${task.title}", (task.progressFraction * 100).toInt(), false)
""",
)

old_process_try = """        try {
            latest = engine.download(latest) { progress ->
                scope.launch {
                    // Only persist if still RUNNING (may have been paused)
                    val current = dao.getById(progress.id)
                    if (current?.status == DownloadStatus.PAUSED) return@launch
                    dao.update(progress.copy(status = DownloadStatus.RUNNING))
                    updateNotification(
                        "正在下载：${progress.title}",
                        (progress.progressFraction * 100).toInt(),
                        true
                    )
                }
            }
            // Recheck pause before complete
            val current = dao.getById(task.id)
            if (current?.status == DownloadStatus.PAUSED) {
                // Keep partial progress already written by engine callbacks; mark paused with last known bytes
                return
            }
            dao.update(latest)
            updateNotification("下载完成：${latest.title}", 100, false)
            runCatching {
                LocalPlayApp.instance.videoRepository.refresh(force = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "task failed id=${task.id}", e)
            val current = dao.getById(task.id)
            if (current?.status == DownloadStatus.PAUSED) return
            dao.update(
                (current ?: task).copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message.orEmpty(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            updateNotification("下载失败：${task.title}", 0, false)
        }
"""

new_process_try = """        try {
            latest = engine.download(
                task = latest,
                shouldAbort = { pausedIds.contains(task.id) }
            ) { progress ->
                scope.launch {
                    val current = dao.getById(progress.id)
                    if (current?.status == DownloadStatus.PAUSED || pausedIds.contains(task.id)) {
                        return@launch
                    }
                    dao.update(progress.copy(status = DownloadStatus.RUNNING))
                    updateNotification(
                        "正在下载：${progress.title}",
                        (progress.progressFraction * 100).toInt(),
                        true
                    )
                }
            }
            if (pausedIds.contains(task.id)) {
                val current = dao.getById(task.id) ?: return
                dao.update(current.copy(status = DownloadStatus.PAUSED))
                return
            }
            dao.update(latest)
            updateNotification("下载完成：${latest.title}", 100, false)
            runCatching {
                LocalPlayApp.instance.videoRepository.refresh(force = true)
            }
        } catch (e: DownloadAbortedException) {
            val current = dao.getById(task.id) ?: return
            dao.update(
                current.copy(
                    status = DownloadStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            updateNotification("已暂停：${current.title}", (current.progressFraction * 100).toInt(), false)
        } catch (e: Exception) {
            Log.e(TAG, "task failed id=${task.id}", e)
            if (pausedIds.contains(task.id)) return
            val current = dao.getById(task.id)
            dao.update(
                (current ?: task).copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message.orEmpty(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            updateNotification("下载失败：${task.title}", 0, false)
        } finally {
            pausedIds.remove(task.id)
        }
"""

if old_process_try not in s:
    raise SystemExit("process try block not found")
s = s.replace(old_process_try, new_process_try)
svc.write_text(s, encoding="utf-8", newline="\n")
print("service patched")
