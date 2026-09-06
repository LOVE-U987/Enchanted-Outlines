# -*- coding: utf-8 -*-
"""最小对照实验:验证 Archify webview 能加载的 canvas.json 结构。

用法: python make_exp.py <mode>
  mode=native  单形状,props 与 addPointer 完全一致(无 text)
  mode=text    单形状,加 props.text
  mode=frame   单 geo rectangle + props.text(验证 text 渲染?)
"""
import io
import json
import sys

SEED = "tmp/archmap_canvas.json"
OUT = ".archify/canvas.json"

def base():
    d = json.load(io.open(SEED, encoding="utf-8"))
    store = {}
    for k, v in d["document"]["store"].items():
        if v.get("typeName") != "shape":
            store[k] = v
    d["document"]["store"] = store
    return d

def native_shape():
    return {
        "x": 100, "y": 100, "rotation": 0, "isLocked": False, "opacity": 1,
        "meta": {"relativePath": "src/main/java/com/enchantedoutlines/mod/EnchantedOutlines.java",
                 "symbolName": "EnchantedOutlines",
                 "symbolStart": {"line": 18, "character": 0},
                 "signature": "public class EnchantedOutlines {"},
        "id": "shape:expnative", "type": "geo",
        "props": {"w": 80, "h": 80, "geo": "ellipse", "dash": "solid", "growY": 0,
                  "url": "", "scale": 1, "color": "light-blue", "labelColor": "black",
                  "fill": "solid", "size": "s", "font": "draw", "align": "middle",
                  "verticalAlign": "middle",
                  "richText": {"type": "doc", "content": [{"type": "paragraph"}]}},
        "parentId": "page:page", "index": "a1sxQ", "typeName": "shape",
    }

def text_shape():
    s = native_shape()
    s["props"] = dict(s["props"])
    s["props"]["text"] = "EnchantedOutlines\n@Mod 主类"
    s["props"]["w"] = 220
    s["props"]["h"] = 64
    return s

def richtext_shape():
    s = native_shape()
    s["props"] = dict(s["props"])
    s["props"]["w"] = 220
    s["props"]["h"] = 64
    s["props"]["richText"] = {
        "type": "doc",
        "content": [
            {"type": "paragraph",
             "content": [{"type": "text", "text": "EnchantedOutlines"}]},
            {"type": "paragraph",
             "content": [{"type": "text", "text": "@Mod 主类 · 配置注册"}]},
        ],
    }
    return s

def main():
    mode = sys.argv[1]
    d = base()
    if mode == "native":
        s = native_shape()
    elif mode == "text":
        s = text_shape()
    elif mode == "richtext":
        s = richtext_shape()
    elif mode == "frame":
        s = text_shape()
        s["props"]["geo"] = "rectangle"
        s["props"]["w"] = 260
    else:
        raise SystemExit("bad mode")
    d["document"]["store"][s["id"]] = s
    io.open(OUT, "w", encoding="utf-8").write(json.dumps(d, ensure_ascii=False, indent=1))
    print("exp file ready:", mode, "1 shape")

if __name__ == "__main__":
    main()
