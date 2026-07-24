# -*- coding: utf-8 -*-
import re
import time
import urllib.parse
import urllib.request
from http.cookiejar import Cookie, CookieJar
from pathlib import Path

URL = "https://www.cdpop.org/listnew/36.html"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
)
OUT = Path(r"D:\code\stu\videoPlay\tools\cdpop_list.html")


class Client:
    def __init__(self):
        self.cj = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cj)
        )
        self.opener.addheaders = [("User-Agent", UA), ("Accept", "text/html,*/*")]

    def _cookie(self, name, value, domain):
        self.cj.set_cookie(
            Cookie(
                0, name, value, None, False, domain, True, False, "/", True,
                False, None, False, None, None, {},
            )
        )

    def get(self, url, rounds=20):
        html = ""
        for i in range(rounds):
            with self.opener.open(url, timeout=30) as resp:
                html = resp.read().decode("utf-8", "replace")
            print("GET", i, "len", len(html))
            m = re.search(r'document\.cookie\s*=\s*"([^"]+)"', html)
            if m:
                first = m.group(1).split(";")[0]
                name, value = first.split("=", 1)
                host = urllib.parse.urlparse(url).hostname
                self._cookie(name, value, host)
                continue
            cpk = re.search(r'var\s+cpk\s*=\s*"([^"]+)"', html)
            step = re.search(r'var\s+step\s*=\s*"([^"]+)"', html)
            nonce = re.search(r'var\s+nonce\s*=\s*(\d+)', html)
            if cpk and step and nonce:
                host = urllib.parse.urlparse(url).hostname
                cpk_name = cpk.group(1)
                cookie_val = next((c.value for c in self.cj if c.name == cpk_name), None)
                if not cookie_val:
                    break
                n = int(nonce.group(1))
                total = 12345
                for idx, ch in enumerate(cookie_val):
                    if ch.isalnum():
                        total += ord(ch) * (n + idx)
                body = urllib.parse.urlencode({"sum": total, "nonce": n}).encode()
                req = urllib.request.Request(
                    url,
                    data=body,
                    method="POST",
                    headers={
                        "User-Agent": UA,
                        "Content-type": "application/x-www-form-urlencoded",
                        "X-GE-UA-Step": step.group(1),
                        "Referer": url,
                    },
                )
                with self.opener.open(req, timeout=30) as resp:
                    resp.read()
                time.sleep(0.6)
                continue
            break
        return html


def main():
    client = Client()
    html = client.get(URL)
    OUT.write_text(html, encoding="utf-8")
    print("saved", OUT, "len", len(html))
    print(html[:1500])
    print("---- href samples ----")
    hrefs = re.findall(r"""href=["']([^"']+)["']""", html)
    from collections import Counter
    c = Counter(hrefs)
    for h, n in c.most_common(40):
        print(n, h)
    print("---- media ----")
    for u in re.findall(r"https?://[^\s\"'<>]+?\.(?:mp4|m3u8|webm|mkv|flv)", html, re.I)[:20]:
        print(u)
    for key in ["player_aaaa", "m3u8", "play", "vod", "list", "detail"]:
        print(key, html.lower().find(key.lower()) if key.isascii() else html.find(key))


if __name__ == "__main__":
    main()
