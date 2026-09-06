# -*- coding: utf-8 -*-
import io
import re
import sys

p = r"C:\Users\97128\.vscode\extensions\shade2511.archify-0.0.1\webview-ui\dist\assets\index-DBegTVot.js"
data = io.open(p, encoding="utf-8", errors="replace").read()

# 打印 loadSnapshot 定义附近;也找 "ensureStoreIsUsable"、"migrateStoreSnapshot"、"Unknown record type" 等错误串
for pat in [r"loadSnapshot\(snapshot\)\s*\{", r"ensureStoreIsUsable", r"migrateStoreSnapshot",
            r"Failed to migrate", r"loadSnapshot\s*=|function loadSnapshot"]:
    for m in re.finditer(pat, data):
        s = max(0, m.start() - 200)
        print("==== 命中:", pat, "@", m.start(), "====")
        print(data[s:m.start() + 1200])
        print()
