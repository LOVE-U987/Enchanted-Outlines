# -*- coding: utf-8 -*-
import io
import json

d = json.load(io.open("tmp/archmap_canvas.json", encoding="utf-8"))
shapes = [v for v in d["document"]["store"].values() if v.get("typeName") == "shape"]
for i in (8, 9, 10):
    s = shapes[i]
    print("=" * 20, "shape[%d] %s" % (i, s["meta"]["symbolName"]), "=" * 20)
    top = {k: v for k, v in s.items() if k not in ("props", "meta")}
    print("top:", json.dumps(top, ensure_ascii=False))
    print("meta:", json.dumps(s["meta"], ensure_ascii=False))
    print("props:", json.dumps(s["props"], ensure_ascii=False))
