# -*- coding: utf-8 -*-
import re
import time
import urllib.parse
import urllib.request
from http.cookiejar import Cookie, CookieJar

URL = "https://www.huanyuxing.com/s/id-a/page/2.html"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
)


def make_cookie(name: str, value: str, domain: str = "www.huanyuxing.com") -> Cookie:
    return Cookie(
        version=0,
        name=name,
        value=value,
        port=None,
        port_specified=False,
        domain=domain,
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


def extract_set_cookie_js(html: str):
    return re.search(r'document\.cookie\s*=\s*"([^"]+)"', html)


def solve_ua_challenge(html: str, cj: CookieJar):
    cpk_m = re.search(r'var\s+cpk\s*=\s*"([^"]+)"', html)
    step_m = re.search(r'var\s+step\s*=\s*"([^"]+)"', html)
    nonce_m = re.search(r'var\s+nonce\s*=\s*(\d+)', html)
    if not (cpk_m and step_m and nonce_m):
        return False
    cpk = cpk_m.group(1)
    step = step_m.group(1)
    nonce = int(nonce_m.group(1))
    cookie_val = None
    for c in cj:
        if c.name == cpk:
            cookie_val = c.value
            break
    print("ua challenge", cpk, step, nonce, "cookie", cookie_val)
    if not cookie_val:
        return False
    total = 12345
    for i, ch in enumerate(cookie_val):
        if ch.isalnum():
            total += ord(ch) * (nonce + i)
    body = urllib.parse.urlencode({"sum": total, "nonce": nonce}).encode()
    req = urllib.request.Request(
        URL,
        data=body,
        method="POST",
        headers={
            "User-Agent": UA,
            "Content-type": "application/x-www-form-urlencoded",
            "X-GE-UA-Step": step,
            "Referer": URL,
        },
    )
    return req, total


def main():
    cj = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    opener.addheaders = [("User-Agent", UA), ("Accept", "text/html")]

    html = ""
    for i in range(30):
        with opener.open(URL, timeout=25) as resp:
            html = resp.read().decode("utf-8", "replace")
        print("GET", i, "len", len(html), "cookies", [(c.name, c.value[:24]) for c in cj])

        m = extract_set_cookie_js(html)
        if m:
            first = m.group(1).split(";")[0]
            name, value = first.split("=", 1)
            cj.set_cookie(make_cookie(name, value))
            print(" set cookie", name)
            continue

        solved = solve_ua_challenge(html, cj)
        if solved:
            req, total = solved
            print(" POST sum", total)
            with opener.open(req, timeout=25) as resp:
                post_body = resp.read().decode("utf-8", "replace")
                print(" POST resp len", len(post_body), resp.status)
            time.sleep(1.2)
            continue

        # maybe real page
        if "mp4" in html.lower() or "m3u8" in html.lower() or "<video" in html.lower() or len(html) > 8000:
            break
        if "ge_ua_p" not in html and "document.cookie" not in html and "ui-uam-box" not in html:
            break
        print(" unknown page, stop")
        break

    out = r"D:\code\stu\videoPlay\tools\sample_final.html"
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print("FINAL len", len(html))
    print(html[:2500])
    print("---- urls ----")
    for found in re.findall(
        r"https?://[^\s\"'<>]+?\.(?:mp4|m3u8|webm|mkv|flv)(?:\?[^\s\"'<>]*)?",
        html,
        flags=re.I,
    )[:40]:
        print(found)


if __name__ == "__main__":
    main()
