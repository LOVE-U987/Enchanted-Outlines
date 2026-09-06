# -*- coding: utf-8 -*-
"""只放第 n 个 shape(1-based 顺序): python single.py <n>"""
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
    k, v = shapes[n - 1]
    store[k] = v
    d["document"]["store"] = store
    io.open(".archify/canvas.json", "w", encoding="utf-8").write(
        json.dumps(d, ensure_ascii=False, indent=1))
    print("single:", v["meta"]["symbolName"], "@", v["x"], v["y"])

if __name__ == "__main__":
    main()
