# -*- coding: utf-8 -*-
"""克隆第一个 shape 为 N 份(同字段)以隔离"数量 vs 内容"问题。"""
import io
import json
import sys

def main():
    n = int(sys.argv[1])
    d = json.load(io.open("tmp/archmap_canvas.json", encoding="utf-8"))
    base_shape = None
    store = {}
    for k, v in d["document"]["store"].items():
        if v.get("typeName") == "shape":
            if base_shape is None:
                base_shape = v
        else:
            store[k] = v
    assert base_shape is not None
    letters = "abcdefghijklmnopqrstuvwxyz"
    for i in range(n):
        s = json.loads(json.dumps(base_shape))
        s["id"] = f"shape:dup{i}"
        s["index"] = f"a{i // 26 + 1}{letters[i % 26]}"
        s["x"] = 100 + (i % 9) * 200
        s["y"] = 100 + (i // 9) * 200
        store[s["id"]] = s
    d["document"]["store"] = store
    io.open(".archify/canvas.json", "w", encoding="utf-8").write(
        json.dumps(d, ensure_ascii=False, indent=1))
    print(f"dup file: {n} 份克隆")

if __name__ == "__main__":
    main()
