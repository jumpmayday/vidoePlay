# -*- coding: utf-8 -*-
import re
import time
import urllib.parse
import urllib.request
from http.cookiejar import Cookie, CookieJar
from pathlib import Path
import base64
import json

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
                time.sleep(0.6)
                continue
            break
        return html


def try_decode_url(raw: str) -> str:
    s = raw
    for _ in range(3):
        try:
            if re.fullmatch(r"[A-Za-z0-9+/=]+", s) and len(s) > 16:
                dec = base64.b64decode(s).decode("utf-8", "ignore")
                if dec.startswith("http") or ".m3u8" in dec or ".mp4" in dec:
                    return dec
                s = dec
            else:
                break
        except Exception:
            break
    return raw


def main():
    client = ChallengedClient()
    play = BASE + "/p/id-205965-2-1.html"
    html = client.get(play)
    Path(r"D:\code\stu\videoPlay\tools\play.html").write_text(html, encoding="utf-8")
    print("play len", len(html))
    # common maccms player var
    for pat in [
        r"var\s+player_aaaa\s*=\s*(\{.*?\});",
        r"player_aaaa\s*=\s*(\{.*?\})",
        r"<script>\s*var\s+player_.*?=\s*(\{.*?\});",
    ]:
        m = re.search(pat, html, re.S)
        print("match", pat[:30], bool(m))
        if m:
            print(m.group(1)[:500])

    urls = re.findall(
        r"https?://[^\s\"'<>]+?\.(?:mp4|m3u8|webm|mkv|flv)(?:\?[^\s\"'<>]*)?",
        html,
        flags=re.I,
    )
    print("direct", urls[:20])

    # any json-like url fields
    for m in re.finditer(r'"(?:url|link|src|file|playurl)"\s*:\s*"([^"]+)"', html, re.I):
        print("field", m.group(0)[:200], "=>", try_decode_url(m.group(1))[:200])

    idx = html.find("player_")
    print("player_ idx", idx)
    if idx >= 0:
        print(html[idx : idx + 500])


if __name__ == "__main__":
    main()
