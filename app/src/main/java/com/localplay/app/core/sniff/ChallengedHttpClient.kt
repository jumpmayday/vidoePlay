package com.localplay.app.core.sniff

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * HTTP client that can pass simple JS cookie gates and Volcano Engine UA challenges
 * (document.cookie reload + ge_ua_p sum POST) used by some video sites.
 */
class ChallengedHttpClient(
    private val client: OkHttpClient = defaultClient()
) {
    fun getHtml(url: String, maxRounds: Int = 24): String {
        repeat(maxRounds) {
            val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .apply {
                    if (!origin.isNullOrBlank()) {
                        header("Referer", "$origin/")
                    }
                }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 403) {
                    throw IllegalStateException("站点拒绝访问(403)，可能限制 IP/地区，请换网络后重试")
                }
                if (!response.isSuccessful && body.isBlank()) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val setCookie = COOKIE_JS.matcher(body)
                if (setCookie.find()) {
                    applyDocumentCookie(url, setCookie.group(1))
                    return@repeat
                }

                if (trySolveUaChallenge(url, body)) {
                    Thread.sleep(700L)
                    return@repeat
                }

                return body
            }
        }
        throw IllegalStateException("页面安全校验次数过多，请稍后重试或换直接视频链接")
    }

    data class CopyResult(
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
                // Server ignored Range; discard body so caller can restart cleanly.
                response.body?.close()
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

    fun getText(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("请求失败 HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun applyDocumentCookie(pageUrl: String, cookieAssign: String) {
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return
        val first = cookieAssign.split(";").firstOrNull().orEmpty()
        val idx = first.indexOf('=')
        if (idx <= 0) return
        val name = first.substring(0, idx).trim()
        val value = first.substring(idx + 1).trim()
        val cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .domain(httpUrl.host)
            .path("/")
            .build()
        MemoryCookieJar.put(httpUrl, cookie)
    }

    private fun trySolveUaChallenge(pageUrl: String, html: String): Boolean {
        val cpk = matchGroup(CPK, html) ?: return false
        val step = matchGroup(STEP, html) ?: return false
        val nonce = matchGroup(NONCE, html)?.toIntOrNull() ?: return false
        val httpUrl = pageUrl.toHttpUrlOrNull() ?: return false
        val cookieVal = MemoryCookieJar.loadForRequest(httpUrl)
            .firstOrNull { it.name == cpk }
            ?.value
            ?: return false

        var sum = 12345
        cookieVal.forEachIndexed { index, ch ->
            if (ch.isLetterOrDigit()) {
                sum += ch.code * (nonce + index)
            }
        }
        val body = FormBody.Builder()
            .add("sum", sum.toString())
            .add("nonce", nonce.toString())
            .build()
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-GE-UA-Step", step)
            .header("Referer", pageUrl)
            .post(body)
            .build()
        client.newCall(request).execute().use { /* consume */ }
        return true
    }

    private fun matchGroup(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private val COOKIE_JS: Pattern =
            Pattern.compile("""document\.cookie\s*=\s*"([^"]+)"""")
        private val CPK: Pattern = Pattern.compile("""var\s+cpk\s*=\s*"([^"]+)"""")
        private val STEP: Pattern = Pattern.compile("""var\s+step\s*=\s*"([^"]+)"""")
        private val NONCE: Pattern = Pattern.compile("""var\s+nonce\s*=\s*(\d+)""")

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(MemoryCookieJar)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}

object MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val hostMap = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { hostMap[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host]?.values?.toList().orEmpty()
    }

    fun put(url: HttpUrl, cookie: Cookie) {
        val hostMap = store.getOrPut(url.host) { ConcurrentHashMap() }
        hostMap[cookie.name] = cookie
    }
}
