# -*- coding: utf-8 -*-
"""校验 archify_gen.py 里用到的所有 tldraw 颜色值合法。"""
import io
import re
import sys

VALID = {
    "black", "grey", "light-violet", "violet", "blue", "light-blue", "cyan",
    "light-cyan", "green", "light-green", "light-orange", "orange",
    "light-red", "red", "light-yellow", "yellow", "white",
}

t = io.open("tmp/archify_gen.py", encoding="utf-8").read()
# 颜色出现在 make_shape 调用/表尾: 抓所有形如 "xxx" 且是颜色集的字符串
colors = re.findall(r'"([a-z]+(?:-[a-z]+)*)"', t)
bad = []
for c in colors:
    if c in VALID:
        continue
    # 过滤非颜色值(方法名等出现在引号里)
    if c in {"m", "s", "draw", "middle", "black", "solid", "ellipse", "doc",
             "paragraph", "text", "light-blue", "light-green", "blue", "violet",
             "orange", "grey", "light-red", "light-violet", "light-orange"}:
        continue
    if c not in ("doc", "paragraph", "text", "middle", "solid", "draw", "ellipse",
                 "m", "s", "doc"):
        bad.append(c)
print("可能的非法颜色:", sorted(set(bad)))
sys.exit(1 if bad else 0)
