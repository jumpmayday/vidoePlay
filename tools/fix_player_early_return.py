# -*- coding: utf-8 -*-
from pathlib import Path
import re

path = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\feature\player\PlayerScreen.kt")
text = path.read_text(encoding="utf-8")

new_block = """    if (video == null) {
        MissingVideoScreen()
        return
    }"""

m = re.search(r"    if \(video == null\) \{.*?return\n    \}", text, re.S)
if not m:
    raise SystemExit("early return block not found")
text = text[: m.start()] + new_block + text[m.end() :]

helper = '''
@Composable
private fun MissingVideoScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text("视频不存在或已被删除", color = LpText2)
    }
}

'''

marker = "@Composable\nprivate fun TopBar"
if "fun MissingVideoScreen" not in text:
    if marker not in text:
        raise SystemExit("TopBar marker not found")
    text = text.replace(marker, helper + marker, 1)

path.write_text(text, encoding="utf-8", newline="\n")
print("ok")
