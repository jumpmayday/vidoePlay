# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")

# Patch ChallengedHttpClient - replace copyTo method
client = (JAVA / "core/sniff/ChallengedHttpClient.kt").read_text(encoding="utf-8")
old = '''    fun copyTo(
        url: String,
        output: OutputStream,
        referer: String? = null,
        onBytes: ((Long, Long?) -> Unit)? = null
    ): Long {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败 HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("空响应")
            val total = body.contentLength().takeIf { it > 0 }
            var read = 0L
            body.source().use { source ->
                val sink = output.sink().buffer()
                while (true) {
                    val count = source.read(sink.buffer, 8_192L)
                    if (count < 0L) break
                    read += count
                    sink.emit()
                    onBytes?.invoke(read, total)
                }
                sink.flush()
            }
            return read
        }
    }
'''

new = '''    data class CopyResult(
        val bytesWritten: Long,
        val totalSize: Long?,
        val httpCode: Int,
        val resumed: Boolean
    )

    fun copyTo(
        url: String,
        output: OutputStream,
        referer: String? = null,
        startByte: Long = 0L,
        onBytes: ((Long, Long?) -> Unit)? = null
    ): CopyResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        if (startByte > 0L) {
            builder.header("Range", "bytes=$startByte-")
        }
        client.newCall(builder.get().build()).execute().use { response ->
            val code = response.code
            if (startByte > 0L && code == 200) {
                // Server ignored Range; caller should restart from zero.
                return CopyResult(0L, null, code, resumed = false)
            }
            if (!response.isSuccessful && code != 206) {
                throw IllegalStateException("下载失败 HTTP $code")
            }
            val body = response.body ?: throw IllegalStateException("空响应")
            val contentLen = body.contentLength().takeIf { it > 0 }
            val totalSize = when {
                code == 206 -> {
                    val range = response.header("Content-Range")
                    // bytes start-end/total
                    range?.substringAfter('/')?.toLongOrNull()?.takeIf { it > 0 }
                        ?: contentLen?.let { it + startByte }
                }
                else -> contentLen
            }
            var read = 0L
            body.source().use { source ->
                val sink = output.sink().buffer()
                while (true) {
                    val count = source.read(sink.buffer, 8_192L)
                    if (count < 0L) break
                    read += count
                    sink.emit()
                    val absolute = if (code == 206) startByte + read else read
                    onBytes?.invoke(absolute, totalSize)
                }
                sink.flush()
            }
            return CopyResult(
                bytesWritten = read,
                totalSize = totalSize,
                httpCode = code,
                resumed = code == 206
            )
        }
    }
'''

if old not in client:
    raise SystemExit('copyTo block not found')
(JAVA / "core/sniff/ChallengedHttpClient.kt").write_text(client.replace(old, new), encoding='utf-8', newline='\n')
print('http client patched')
