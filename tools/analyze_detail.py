# -*- coding: utf-8 -*-
from pathlib import Path
import re

html = Path(r"D:\code\stu\videoPlay\tools\detail.html").read_text(encoding="utf-8", errors="replace")
print("len", len(html))
hrefs = sorted(set(re.findall(r"""href=["']([^"']+)["']""", html)))
for h in hrefs:
    if any(x in h for x in ["play", "v/", "p/", "watch", "player"]):
        print(h)

for key in ["playlist", "play", "mac", "from", "线路", "在线"]:
    idx = html.lower().find(key.lower()) if key.isascii() else html.find(key)
    print("KEY", key, idx)
    if idx >= 0:
        print(html[max(0, idx - 40) : idx + 200])
        print("---")

# print title
m = re.search(r"<title>([^<]+)</title>", html)
print("title", m.group(1) if m else None)
