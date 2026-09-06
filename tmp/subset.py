# -*- coding: utf-8 -*-
"""生成完整架构图的子集用于二分定位: python subset.py <n>
保留 document/page/user 与全部非 shape 记录,只放前 n 个 shape(按 store 原顺序)。
"""
import io
import json
import sys

def main():
    n = int(sys.argv[1])
    d = json.load(io.open("tmp/archmap_canvas.json", encoding="utf-8"))
    shapes = [(k, v) for k, v in d["document"]["store"].items()
              if v.get("typeName") == "shape"]
    store = {k: v for k, v in d["document"]["store"].items()
             if v.get("typeName") != "shape"}
    for k, v in shapes[:n]:
        store[k] = v
    d["document"]["store"] = store
    io.open(".archify/canvas.json", "w", encoding="utf-8").write(
        json.dumps(d, ensure_ascii=False, indent=1))
    print(f"subset: {len(store)} 记录,其中 shape {n}")

if __name__ == "__main__":
    main()
