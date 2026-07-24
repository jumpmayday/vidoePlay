# -*- coding: utf-8 -*-
from pathlib import Path

p = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\sniff\VideoSniffer.kt")
t = p.read_text(encoding="utf-8")

old1 = """            val title = cleanText(titled.group(2)).ifBlank {
                titled.group(3)?.takeIf { it.isNotBlank() }
                    ?: href.substringAfterLast('/').substringBefore('.')
            }
            result.putIfAbsent(href, title.orEmpty())
"""

new1 = """            val title = cleanText(titled.group(3).orEmpty()).ifBlank {
                titled.group(2).orEmpty().ifBlank {
                    href.substringAfterLast('/').substringBefore('.')
                }
            }
            result.putIfAbsent(href, title)
"""

old2 = """                val title = cleanText(any.group(2)).ifBlank {
                    any.group(3)?.takeIf { it.isNotBlank() } ?: ""
                }
"""

new2 = """                val title = cleanText(any.group(3).orEmpty()).ifBlank {
                    any.group(2).orEmpty()
                }
"""

if old1 not in t:
    print("WARN: titled block not found")
else:
    t = t.replace(old1, new1, 1)
    print("titled fixed")

if old2 not in t:
    print("WARN: any block not found")
else:
    t = t.replace(old2, new2, 1)
    print("any fixed")

p.write_text(t, encoding="utf-8", newline="\n")

client = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\sniff\ChallengedHttpClient.kt")
ct = client.read_text(encoding="utf-8")
print("has toHttpUrlOrNull import", "toHttpUrlOrNull" in ct)
print("has Referer", "Referer" in ct)
print("has 403", "403" in ct)
