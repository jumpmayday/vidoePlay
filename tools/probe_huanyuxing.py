# -*- coding: utf-8 -*-
import re
import urllib.request
from http.cookiejar import Cookie, CookieJar

url = "https://www.huanyuxing.com/s/id-a/page/2.html"
cj = CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [
    (
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    ),
    ("Accept", "text/html,application/xhtml+xml"),
]

html = ""
for i in range(20):
    with opener.open(url, timeout=25) as response:
        html = response.read().decode("utf-8", "replace")
    print("pass", i, "len", len(html), "cookies", len(list(cj)))
    match = re.search(r'document\.cookie\s*=\s*"([^"]+)"', html)
    if not match:
        break
    first = match.group(1).split(";")[0]
    name, value = first.split("=", 1)
    cookie = Cookie(
        version=0,
        name=name,
        value=value,
        port=None,
        port_specified=False,
        domain="www.huanyuxing.com",
        domain_specified=True,
        domain_initial_dot=False,
        path="/",
        path_specified=True,
        secure=False,
        expires=None,
        discard=True,
        comment=None,
        comment_url=None,
        rest={},
    )
    cj.set_cookie(cookie)
else:
    print("max retries")

out = r"D:\code\stu\videoPlay\tools\sample_final.html"
with open(out, "w", encoding="utf-8") as f:
    f.write(html)
print(html[:2000])
print("---- video-like ----")
for found in re.findall(
    r"https?://[^\s\"'<>]+?\.(?:mp4|m3u8|webm|mkv|flv)(?:\?[^\s\"'<>]*)?",
    html,
    flags=re.I,
)[:30]:
    print(found)
