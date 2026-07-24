# -*- coding: utf-8 -*-
from pathlib import Path

p = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\sniff\ChallengedHttpClient.kt")
t = p.read_text(encoding="utf-8")

old = """            val request = Request.Builder()
                .url(url)
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
"""

new = """            val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
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
"""

if old not in t:
    raise SystemExit("getHtml request block not found")
p.write_text(t.replace(old, new, 1), encoding="utf-8", newline="\n")
print("http client headers patched")

sniffer = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\sniff\VideoSniffer.kt")
print("sniffer has listnew", "listnew" in sniffer.read_text(encoding="utf-8"))
print("sniffer has MacCms", "MacCms" in sniffer.read_text(encoding="utf-8") or "macCms" in sniffer.read_text(encoding="utf-8").lower() or "sniffViaMacCmsApi" in sniffer.read_text(encoding="utf-8"))
