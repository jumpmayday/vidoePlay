# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")

(JAVA / "core/sniff/ChallengedHttpClient.kt").write_text(r'''package com.localplay.app.core.sniff

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpCookie
import java.net.URI
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
        var currentUrl = url
        repeat(maxRounds) {
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful && body.isBlank()) {
                    throw IllegalStateException("HTTP ${response.code}")
                }

                val setCookie = COOKIE_JS.matcher(body)
                if (setCookie.find()) {
                    applyDocumentCookie(currentUrl, setCookie.group(1))
                    return@repeat
                }

                if (trySolveUaChallenge(currentUrl, body)) {
                    Thread.sleep(700L)
                    return@repeat
                }

                return body
            }
        }
        throw IllegalStateException("页面安全校验次数过多，请稍后重试或换直接视频链接")
    }

    fun downloadBytes(
        url: String,
        referer: String? = null,
        onBytes: ((Long, Long?) -> Unit)? = null
    ): ByteArray {
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
            val source = body.source()
            val buffer = okio.Buffer()
            var read = 0L
            while (true) {
                val count = source.read(buffer, 8_192L)
                if (count < 0L) break
                read += count
                onBytes?.invoke(read, total)
            }
            return buffer.readByteArray()
        }
    }

    fun openStream(
        url: String,
        referer: String? = null
    ): Pair<okhttp3.Response, Long?> {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        val response = client.newCall(builder.get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("下载失败 HTTP ${response.code}")
        }
        val length = response.body?.contentLength()?.takeIf { it > 0 }
        return response to length
    }

    private fun applyDocumentCookie(pageUrl: String, cookieAssign: String) {
        val first = cookieAssign.split(";").firstOrNull().orEmpty()
        val idx = first.indexOf('=')
        if (idx <= 0) return
        val name = first.substring(0, idx).trim()
        val value = first.substring(idx + 1).trim()
        val host = URI(pageUrl).host ?: return
        val cookie = HttpCookie(name, value).apply {
            path = "/"
            domain = host
        }
        // OkHttp JavaNetCookieJar stores via CookieManager below.
        cookieManager.cookieStore.add(URI("https://$host/"), cookie)
    }

    private fun trySolveUaChallenge(pageUrl: String, html: String): Boolean {
        val cpk = matchGroup(CPK, html) ?: return false
        val step = matchGroup(STEP, html) ?: return false
        val nonce = matchGroup(NONCE, html)?.toIntOrNull() ?: return false
        val host = URI(pageUrl).host ?: return false
        val cookieVal = cookieManager.cookieStore.get(URI("https://$host/"))
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

        private val cookieManager = java.net.CookieManager(
            null,
            java.net.CookiePolicy.ACCEPT_ALL
        ).also {
            java.net.CookieHandler.setDefault(it)
        }

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(okhttp3.JavaNetCookieJar(cookieManager))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
''', encoding='utf-8', newline='\n')

print('client ok')
