# -*- coding: utf-8 -*-
from pathlib import Path

p = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\core\sniff\VideoDownloader.kt")
t = p.read_text(encoding="utf-8")
t = t.replace("import android.util.Log\n", "")
t = t.replace(
    "val created = tree.createFile(mime, fileName.substringBeforeLast('.'))",
    "val created = tree.createFile(mime, fileName)",
)
old = """
    companion object {
        private const val TAG = "VideoDownloader"
    }
"""
if old in t and "Log." not in t:
    t = t.replace(old, "\n")
p.write_text(t, encoding="utf-8", newline="\n")
print("ok")
