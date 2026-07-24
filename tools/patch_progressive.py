# -*- coding: utf-8 -*-
from pathlib import Path
import re

p = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\download\ResumableDownloadEngine.kt")
t = p.read_text(encoding="utf-8")

# Remove unused imports
t = t.replace("import android.util.Log\n", "")
t = t.replace("import java.io.RandomAccessFile\n", "")

new_method = '''
    private fun downloadProgressive(
        task: DownloadTaskEntity,
        onProgress: (DownloadTaskEntity) -> Unit
    ): DownloadTaskEntity {
        val partial = ensurePartialFile(task)
        var current = task.copy(
            partialPath = partial.absolutePath,
            status = DownloadStatus.RUNNING,
            updatedAt = System.currentTimeMillis()
        )
        onProgress(current)

        var offset = if (partial.exists()) partial.length().coerceAtLeast(0L) else 0L
        if (offset > 0L) {
            current = current.copy(downloadedBytes = offset)
            onProgress(current)
        }

        var restarted = false
        while (true) {
            FileOutputStream(partial, offset > 0L).use { output ->
                val result = http.copyTo(
                    url = current.mediaUrl,
                    output = output,
                    referer = current.pageUrl.ifBlank { null },
                    startByte = offset
                ) { absolute, total ->
                    current = current.copy(
                        downloadedBytes = absolute,
                        totalBytes = total ?: current.totalBytes,
                        updatedAt = System.currentTimeMillis()
                    )
                    onProgress(current)
                }
                output.flush()

                if (offset > 0L && result.httpCode == 200 && !restarted) {
                    restarted = true
                } else {
                    current = current.copy(
                        downloadedBytes = if (result.resumed) {
                            offset + result.bytesWritten
                        } else {
                            result.bytesWritten
                        },
                        totalBytes = result.totalSize ?: current.totalBytes
                    )
                    restarted = false
                }
            }
            if (restarted) {
                partial.delete()
                offset = 0L
                current = current.copy(downloadedBytes = 0L, totalBytes = -1L)
                onProgress(current)
                continue
            }
            break
        }

        val outputUri = publishFinal(partial, current.fileName, current.treeUri, mimeFor(current))
        partial.delete()
        return current.copy(
            status = DownloadStatus.COMPLETED,
            outputUri = outputUri.toString(),
            partialPath = "",
            updatedAt = System.currentTimeMillis(),
            errorMessage = ""
        )
    }
'''

t2, n = re.subn(
    r"    private fun downloadProgressive\([\s\S]*?\n    private fun downloadHls\(",
    new_method + "\n    private fun downloadHls(",
    t,
    count=1,
)
if n != 1:
    raise SystemExit(f"progressive replace failed: {n}")

# remove unused TAG companion if present
t2 = re.sub(
    r"\n    companion object \{\n        private const val TAG = \"ResumableDownload\"\n    \}\n",
    "\n",
    t2,
)

p.write_text(t2, encoding="utf-8", newline="\n")
print("progressive fixed")
