# -*- coding: utf-8 -*-
from pathlib import Path
from collections import Counter
import re

html = Path(r"D:\code\stu\videoPlay\tools\sample_final.html").read_text(
    encoding="utf-8", errors="replace"
)
print("len", len(html))
hrefs = re.findall(r"""href=["']([^"']+)["']""", html)
print("href count", len(hrefs))
for h in hrefs:
    low = h.lower()
    if any(x in low for x in ["detail", "vod", "play", "video", "movie", "/v/", "/m/", "film"]):
        print("H", h)

titles = re.findall(r"""<a[^>]+href=["']([^"']+)["'][^>]*>([^<]{1,120})</a>""", html)
print("anchors", len(titles))
shown = 0
for href, text in titles:
    t = re.sub(r"\s+", " ", text).strip()
    if not t:
        continue
    print(t[:50], "->", href)
    shown += 1
    if shown >= 50:
        break

print("mp4", html.lower().find(".mp4"), "m3u8", html.lower().find("m3u8"))
rel = Counter(re.findall(r"""href=["'](/[^"']+)["']""", html))
for p, c in rel.most_common(40):
    print(c, p)

# dump snippets around mac/vod
for key in ["vod", "detail", "play", "mac"]:
    idx = html.lower().find(key)
    if idx >= 0:
        print("===", key, "===")
        print(html[max(0, idx - 80) : idx + 160])
