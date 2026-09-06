# -*- coding: utf-8 -*-
"""收集 Enchanted Outlines 核心类的声明行(供 Archify 指针 meta.symbolStart 用)。"""
import io
import re
import os

ROOT = "src/main/java/com/enchantedoutlines/mod"

CLASS_RE = re.compile(
    r"^\s*@\w+.*$|^\s*public\s+(final\s+|abstract\s+)?(class|interface|record|enum)\s+(\w+)",
    re.M)

def decl_lines(path):
    """返回 [(lineno0, kind, name, decl_text)]"""
    full = os.path.join(ROOT, path)
    text = io.open(full, encoding="utf-8").read()
    out = []
    # 逐行找类声明(跳过 @ 注解行本身)
    for i, line in enumerate(text.splitlines()):
        s = line.strip()
        m = re.match(r"public\s+(?:final\s+|abstract\s+)?(class|interface|record|enum)\s+(\w+)", s)
        if m:
            out.append((i, m.group(1), m.group(2), s))
    return out

def method_lines(path, clazz):
    """OutlineRenderer 中指定方法/内部类/record 的声明行。"""
    full = os.path.join(ROOT, path)
    text = io.open(full, encoding="utf-8").read()
    lines = text.splitlines()
    res = {}
    # 目标:public/private 方法、record、静态内部类
    pat = re.compile(
        r"^(?:\s*(?:public|private|protected)\s+)?(?:static\s+|final\s+|synchronized\s+)*"
        r"(?:(?!if|for|while|switch|return)\w[\w<>,\[\]\s\.]*?\s+)?"
        r"([a-zA-Z_]\w*)\s*\([^;{}]*\)\s*(?:throws[\s\S]*?)?\{")
    for i, line in enumerate(lines):
        s = line.strip()
        # record 或 内部类
        if re.match(r"(?:private|public|static|final|\s)*(record|class)\s+(\w+)", s):
            m = re.match(r"(?:private|public|static|final|\s)*(record|class)\s+(\w+)", s)
            res.setdefault(m.group(2), i)
        # 方法签名
        if "(" in s and ")" in s and "=" not in s and "->" not in s and not s.startswith("//"):
            m = re.match(
                r"(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?"
                r"(?:[\w<>\[\],\s\.]+?)\s+([a-zA-Z_]\w*)\s*\(", s)
            if m and not s.startswith(("if ", "for ", "while ", "return ")):
                res.setdefault(m.group(1), i)
    return res

items = []
for path in ["EnchantedOutlines.java", "EnchantedOutlinesClient.java",
             "config/Config.java", "config/EnchantedConfigScreen.java",
             "outline/OutlineRenderer.java", "outline/ColorResolver.java",
             "outline/OutlineColorRegistry.java", "outline/OutlineColorEvent.java"]:
    for (ln, kind, name, decl) in decl_lines(path):
        items.append((path, name, ln, decl.strip()))

mixin = ["GuiGraphicsMixin", "ItemRendererMixin", "ItemInHandRendererMixin",
         "HumanoidArmorLayerMixin", "ElytraLayerMixin", "ThrownTridentRendererMixin",
         "ElytraModelAccessor", "ElytraLayerAccessor"]
for mname in mixin:
    p = f"mixin/{mname}.java"
    for (ln, kind, name, decl) in decl_lines(p):
        items.append((p, name, ln, decl.strip()))

# OutlineRenderer 关键方法
meths = ["renderOutline", "renderHandOutline", "renderGui3dInflate",
         "renderVertexNormalExpand", "renderFlatOutlineShape",
         "needVanillaShaderFallback", "setOutlineShader", "invalidateCaches",
         "shapeTexture", "shapeTextureFor", "shapeTextureForLocation",
         "shieldModel", "tridentModel", "inventoryModelFor",
         "renderBewlrEntityOutline", "findBewlrModel", "readBewlrModelField",
         "ensureBewlrModelsLoaded", "renderGeoItemOutline", "geoItemModel",
         "renderGeoModelOutline", "renderAzureLibArmorOutline",
         "renderGeckoLibArmorOutline", "renderLionfishPerCube",
         "renderLionfishInflated", "renderLionfishPart", "renderLionfishCube",
         "renderArmorOutline", "renderElytraOutline", "renderEntityModelOutline",
         "armorOutlineRenderType", "geoOutlineRenderType",
         "captureBewlrGeometry", "resolveBewlrRenderer",
         "renderBewlrCapturedOutline", "isAzureLibArmor", "isGeckoLibArmor",
         "renderTridentOutline"]

olp = "outline/OutlineRenderer.java"
ml = method_lines(olp, "OutlineRenderer")
for m in meths:
    if m in ml:
        items.append((olp, m, ml[m], ""))
    else:
        print("!! 未找到方法:", m)

# 去重(同一文件同类名只保留首个声明 = 类声明)
seen = {}
for (p, name, ln, decl) in items:
    key = (p, name)
    if key not in seen:
        seen[key] = (ln, decl)
print(f"共 {len(seen)} 个唯一节点")
for (p, name), (ln, decl) in sorted(seen.items()):
    print(f"{p} :: {name} @ line {ln}  {decl[:70]}")
