# -*- coding: utf-8 -*-
import io
import json

d = json.load(io.open("tmp/archmap_canvas.json", encoding="utf-8"))
shapes = [v for v in d["document"]["store"].values() if v.get("typeName") == "shape"]
for i in (19, 20, 21):
    s = shapes[i]
    print("=" * 20, "shape[%d] %s" % (i, s["meta"]["symbolName"]), "=" * 20)
    print("top:", json.dumps({k: v for k, v in s.items() if k not in ("props", "meta")}))
    print("meta:", json.dumps(s["meta"], ensure_ascii=False))
    print("props:", json.dumps(s["props"], ensure_ascii=False))
