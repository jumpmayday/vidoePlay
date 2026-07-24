# -*- coding: utf-8 -*-
import re
import time
import urllib.parse
import urllib.request
from http.cookiejar import Cookie, CookieJar
from pathlib import Path

BASE = "https://www.huanyuxing.com"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
)


class ChallengedClient:
    def __init__(self):
        self.cj = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cj)
        )
        self.opener.addheaders = [("User-Agent", UA), ("Accept", "text/html,*/*")]

    def _cookie(self, name, value, domain="www.huanyuxing.com"):
        self.cj.set_cookie(
            Cookie(
                0,
                name,
                value,
                None,
                False,
                domain,
                True,
                False,
                "/",
                True,
                False,
                None,
                False,
                None,
                None,
                {},
            )
        )

    def get(self, url: str, max_rounds: int = 25) -> str:
        html = ""
        for _ in range(max_rounds):
            with self.opener.open(url, timeout=30) as resp:
                html = resp.read().decode("utf-8", "replace")
            m = re.search(r'document\.cookie\s*=\s*"([^"]+)"', html)
            if m:
                first = m.group(1).split(";")[0]
                name, value = first.split("=", 1)
                self._cookie(name, value)
                continue
            cpk_m = re.search(r'var\s+cpk\s*=\s*"([^"]+)"', html)
            step_m = re.search(r'var\s+step\s*=\s*"([^"]+)"', html)
            nonce_m = re.search(r'var\s+nonce\s*=\s*(\d+)', html)
            if cpk_m and step_m and nonce_m:
                cpk = cpk_m.group(1)
                step = step_m.group(1)
                nonce = int(nonce_m.group(1))
                cookie_val = next((c.value for c in self.cj if c.name == cpk), None)
                if not cookie_val:
                    break
                total = 12345
                for i, ch in enumerate(cookie_val):
                    if ch.isalnum():
                        total += ord(ch) * (nonce + i)
                body = urllib.parse.urlencode({"sum": total, "nonce": nonce}).encode()
                req = urllib.request.Request(
                    url,
                    data=body,
                    method="POST",
                    headers={
                        "User-Agent": UA,
                        "Content-type": "application/x-www-form-urlencoded",
                        "X-GE-UA-Step": step,
                        "Referer": url,
                    },
                )
                with self.opener.open(req, timeout=30) as resp:
                    resp.read()
                time.sleep(0.8)
                continue
            break
        return html


def main():
    client = ChallengedClient()
    detail = BASE + "/v/id-205965.html"
    html = client.get(detail)
    Path(r"D:\code\stu\videoPlay\tools\detail.html").write_text(html, encoding="utf-8")
    print("detail len", len(html))
    for pat in [
        r"https?://[^\s\"'<>]+?\.(?:mp4|m3u8|webm|mkv|flv)(?:\?[^\s\"'<>]*)?",
        r"""["']url["']\s*:\s*["']([^"']+)["']""",
        r"""player_aaaa\s*=\s*(\{.*?\})""",
        r"""<script[^>]*>\s*var\s+player_[^=]+=\s*(\{.*?\});""",
    ]:
        found = re.findall(pat, html, flags=re.I | re.S)
        print("PAT", pat[:40], "->", len(found))
        for item in found[:10]:
            print(" ", item[:200] if isinstance(item, str) else item)

    # dump around player
    for key in ["player_aaaa", "m3u8", "encrypt", "MacPlayer", "playurl", "url"]:
        idx = html.find(key)
        print(key, idx)
        if idx >= 0:
            print(html[idx : idx + 300])
            print("---")


if __name__ == "__main__":
    main()
