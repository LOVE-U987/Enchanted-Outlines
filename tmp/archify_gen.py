# -*- coding: utf-8 -*-
"""用 Archify canvas.json 格式生成 Enchanted Outlines 完整架构图。
基于真实种子(seed)快照,仅替换 store 中的 shapes 并按层布局。"""
import io
import json
import random
import string

SEED = ".archify/canvas.json"
OUT = ".archify/canvas.json"
BASE = "src/main/java/com/enchantedoutlines/mod/"

def load_seed():
    return json.load(io.open(SEED, encoding="utf-8"))

def norm(s):
    return " ".join(s.split()).strip()

def shape_index(i):
    # tldraw 风格 index: a1a,a1b,... a1z,a2a...
    letters = string.ascii_lowercase
    n = i // 26 + 1
    c = letters[i % 26]
    return f"a{n}{c}"

def make_shape(sid, idx, label, file, symbol, container, line0, decl, x, y,
               color="light-blue", w=None, h=56, size="m"):
    lines = label.split("\n")
    w = w or min(430, max(130, max(len(l) for l in lines) * 8 + 50))
    # ⚠️ tldraw 5.2.5 geo 无 props.text(validator 拒绝未知字段导致 loadSnapshot
    # 整图失败);标签必须写进 richText(tiptap doc),每行一个 paragraph。
    props = {
        "w": w, "h": h, "geo": "ellipse", "dash": "solid", "growY": 0,
        "url": "", "scale": 1, "color": color, "labelColor": "black",
        "fill": "solid", "size": size, "font": "draw",
        "align": "middle", "verticalAlign": "middle",
        "richText": {"type": "doc", "content": [
            {"type": "paragraph", "content": [{"type": "text", "text": ln}]}
            for ln in lines]},
    }
    meta = {
        "relativePath": file,
        "symbolName": symbol,
        "symbolStart": {"line": line0, "character": 0},
        "signature": norm(decl)[:200],
        "selectionStart": {"line": line0, "character": 0},
        "preview": norm(decl)[:80],
    }
    if container:
        meta["containerName"] = container
    return {
        "x": x, "y": y, "rotation": 0, "isLocked": False, "opacity": 1,
        "meta": meta, "id": sid, "type": "geo", "props": props,
        "parentId": "page:page", "index": idx, "typeName": "shape",
    }

def line0_of(text, needle):
    """在源码里找 needle 首次出现的 0-based 行号。"""
    for i, l in enumerate(text.splitlines()):
        if needle in l:
            return i
    return 0

def src(p):
    return io.open(BASE + p, encoding="utf-8").read()

# ---------- 节点清单 ----------
# (label, 相对路径, symbolName, containerName|None, 0-based行, 声明/锚行文本, 组色)
def decl_line(p, pat):
    t = src(p)
    for i, l in enumerate(t.splitlines()):
        if pat in l:
            return i, l
    return 0, pat

# 预抓行(与方法名定位一致;类 = 声明行)
rows = []
def add_class(p, pat, label, color, w=None):
    ln, dl = decl_line(p, pat)
    cls = pat.split(" ")[-1].split("{")[0].split("(")[0]
    rows.append((label, BASE + p, cls, None, ln, dl, color, w))

# A 入口
add_class("EnchantedOutlines.java", "public class EnchantedOutlines", "EnchantedOutlines\n@Mod 主类 · 注册 COMMON 配置", "violet")
add_class("EnchantedOutlinesClient.java", "public class EnchantedOutlinesClient", "EnchantedOutlinesClient\n@Mod(CLIENT) · 着色器/配置界面/进出世界清缓存", "violet")
# B 配置
add_class("config/Config.java", "public final class Config", "Config\n配置定义(COMMON)+解析缓存", "light-green")
add_class("config/EnchantedConfigScreen.java", "public class EnchantedConfigScreen", "EnchantedConfigScreen\n配置界面(分类导航)", "light-green")
# C outline 核心
add_class("outline/OutlineRenderer.java", "public final class OutlineRenderer", "OutlineRenderer\n描边渲染核心(4320 行)", "blue", 300)
add_class("outline/ColorResolver.java", "public final class ColorResolver", "ColorResolver\n颜色解析 resolve/thickness", "light-blue")
add_class("outline/OutlineColorRegistry.java", "public final class OutlineColorRegistry", "OutlineColorRegistry\n公开扩展注册表(程序化规则)", "light-blue")
add_class("outline/OutlineColorEvent.java", "public final class OutlineColorEvent", "OutlineColorEvent\n扩展注册事件(游戏总线)", "light-blue")
# D mixin
for f, pat in [
    ("GuiGraphicsMixin.java", "public abstract class GuiGraphicsMixin"),
    ("ItemRendererMixin.java", "public abstract class ItemRendererMixin"),
    ("ItemInHandRendererMixin.java", "public abstract class ItemInHandRendererMixin"),
    ("HumanoidArmorLayerMixin.java", "public abstract class HumanoidArmorLayerMixin"),
    ("ElytraLayerMixin.java", "public abstract class ElytraLayerMixin"),
    ("ThrownTridentRendererMixin.java", "public abstract class ThrownTridentRendererMixin"),
    ("ElytraLayerAccessor.java", "public interface ElytraLayerAccessor"),
    ("ElytraModelAccessor.java", "public interface ElytraModelAccessor"),
]:
    add_class("mixin/" + f, pat, f[:-5], "light-orange")
for f, pat in [("ElytraLayerAccessor.java", "public interface ElytraLayerAccessor"),
               ("ElytraModelAccessor.java", "public interface ElytraModelAccessor")]:
    pass  # 已在上面加入

# E OutlineRenderer 关键方法 (label, 方法名, 组色)
E = [
    # (label, method, 0-based 行 - 直接硬编码自 grep)
    ("renderOutline()\nGUI/手持 统一描边入口", "renderOutline", 571 - 1, "light-blue"),
    ("renderHandOutline()\n世界 3D/扁平描边", "renderHandOutline", 802 - 1, "light-blue"),
    ("renderGui3dInflate()\nGUI 3D 放大壳", "renderGui3dInflate", 626 - 1, "light-blue"),
    ("renderVertexNormalExpand()\n顶点法线外扩", "renderVertexNormalExpand", 765 - 1, "light-blue"),
    ("renderFlatOutlineShape()\n扁平形状纹理描边", "renderFlatOutlineShape", 1185 - 1, "light-blue"),
    ("needVanillaShaderFallback()\n光影 fallback 判断", "needVanillaShaderFallback", 342 - 1, "grey"),
    ("setOutlineShader()\n着色器加载回调", "setOutlineShader", 469 - 1, "grey"),
    ("invalidateCaches()\n缓存统一清理", "invalidateCaches", 496 - 1, "grey"),
    ("shapeTextureForLocation()\n独立纹理→形状纹理", "shapeTextureForLocation", 1329 - 1, "light-violet"),
    ("spriteOriginalImage()\n精灵原图像反射读取", "spriteOriginalImage", 1391 - 1, "light-violet"),
    ("emitQuadShape()\n形状纹理 quad 写入", "emitQuadShape", 1431 - 1, "light-violet"),
    ("averageLuma()\n平均感知亮度(÷255)", "averageLuma", 1055 - 1, "light-violet"),
    ("darkenByLuma()\n纯色描边压暗曝光", "darkenByLuma", 1127 - 1, "light-violet"),
    ("isFullyTransparent()\n全透明纹理检测", "isFullyTransparent", 3289 - 1, "light-violet"),
    ("shieldModel()\n盾牌近似盒模型", "shieldModel", 1567 - 1, "light-green"),
    ("tridentModel()\n三叉戟近似盒模型", "tridentModel", 1604 - 1, "light-green"),
    ("inventoryModelFor()\n平面 inventory 模型探测", "inventoryModelFor", 1673 - 1, "light-green"),
    ("renderBewlrEntityOutline()\nBEWLR 3D 描边入口", "renderBewlrEntityOutline", 1887 - 1, "orange"),
    ("findBewlrModel()\nBEWLR 模型反射查找", "findBewlrModel", 2275 - 1, "orange"),
    ("buildCataclysmSpecs()\n灾变精确适配表", "buildCataclysmSpecs", 1844 - 1, "orange"),
    ("ensureBewlrModelsLoaded()\n触发延迟初始化", "ensureBewlrModelsLoaded", 2388 - 1, "orange"),
    ("isTaczNamespace()\nTACZ 停用拦截", "isTaczNamespace", 1770 - 1, "orange"),
    ("isHazenNamespace()\nHazen 停用拦截", "isHazenNamespace", 1790 - 1, "orange"),
    ("renderBewlrCapturedOutline()\n捕获几何放大壳", "renderBewlrCapturedOutline", 2226 - 1, "orange"),
    ("captureBewlrGeometry()\nrenderByItem 顶点捕获", "captureBewlrGeometry", 2024 - 1, "orange"),
    ("resolveBewlrRenderer()\nBEWLR 渲染器解析", "resolveBewlrRenderer", 2056 - 1, "orange"),
    ("renderLionfishPerCube()\n逐 cube 顶点法线外扩", "renderLionfishPerCube", 2461 - 1, "light-red"),
    ("renderLionfishPart()\n部件递归渲染", "renderLionfishPart", 2506 - 1, "light-red"),
    ("renderLionfishInflated()\n整体包围盒放大壳", "renderLionfishInflated", 2751 - 1, "light-red"),
    ("renderGeoItemOutline()\nGeo 物品描边入口", "renderGeoItemOutline", 3751 - 1, "violet"),
    ("renderGeoModelOutline()\nGeo 骨骼放大壳", "renderGeoModelOutline", 3715 - 1, "violet"),
    ("geoItemModel()\nGeo 模型解析", "geoItemModel", 3767 - 1, "violet"),
    ("renderEntityModelOutline()\nMojang Model 放大壳", "renderEntityModelOutline", 3129 - 1, "violet"),
    ("renderArmorOutline()\n盔甲描边(逐 cube)", "renderArmorOutline", 3002 - 1, "violet"),
    ("renderElytraOutline()\n鞘翅描边", "renderElytraOutline", 3161 - 1, "violet"),
    ("armorOutlineRenderType()\n盔甲采样纹理选择", "armorOutlineRenderType", 3239 - 1, "violet"),
    ("isAzureLibArmor()\nAzureLib 盔甲检测", "isAzureLibArmor", 3908 - 1, "violet"),
    ("isGeckoLibArmor()\nGeckoLib 盔甲检测", "isGeckoLibArmor", 4092 - 1, "violet"),
]

# ---------- 布局 ----------
COLW = 330          # 类列间距
METHOD_COL_X = 1720 # 方法区起点
METHOD_X_STEP = 360
# A/B/C/D 列 x
col_x = [150, 520, 890, 1260]
shapes = []
idx_counter = [0]

def emit(label, file, symbol, container, line0, decl, x, y, color, w=None):
    idx_counter[0] += 1
    sid = f"shape:arch{idx_counter[0]}"
    shapes.append(make_shape(sid, shape_index(idx_counter[0] - 1), label, file,
                             symbol, container, line0, decl, x, y, color, w))
    return shapes[-1]["props"]["h"]

# 列行分配(各列独立 y 游标)
col_y = [140, 140, 140, 140]
ROW = 175

for (label, file, sym, cont, ln, decl, color, w) in rows:
    file_pkg = file.split("/")[-2] if "/" in file else "root"
    ci = {"EnchantedOutlines.java": 0, "EnchantedOutlinesClient.java": 0,
          "config": 1, "outline": 2, "mixin": 3}.get(file_pkg, 0)
    if file_pkg == "root":
        ci = 0
    if sym == "OutlineRenderer":
        ci = 2
    if sym in ("EnchantedOutlines", "EnchantedOutlinesClient"):
        ci = 0
    emit(label, file, sym, cont, ln, decl, col_x[ci], col_y[ci], color, w)
    col_y[ci] += ROW

# 方法区: 泳道按行推进,列自动换
my = 140
mi = 0
for (label, m, ln, color) in E:
    # 行号验证: 从源码确认方法存在
    t = src("outline/OutlineRenderer.java")
    lines = t.splitlines()
    if m == "renderOutline":
        found = any(repr(m) + "(" in lines[ln] or m + "(" in lines[ln] for ln in range(max(0, ln - 2), ln + 3))
    else:
        found = any(m in lines[k] for k in range(max(0, ln - 2), min(len(lines), ln + 3)))
    if not found:
        # 找实际行(宽松)
        for k, l in enumerate(lines):
            if l.strip().startswith(("public", "private", "protected")) and m + "(" in l:
                ln = k
                found = True
                break
    col = mi // 15
    emit(label, BASE + "outline/OutlineRenderer.java", m, "OutlineRenderer", ln,
         f"{m}(...)", METHOD_COL_X + col * METHOD_X_STEP, my, color)
    mi += 1
    if mi % 15 == 0:
        my = 140
    else:
        my += 104

# ---------- 组装 ----------
seed = load_seed()
store = seed["document"]["store"]
# 保留 document/page/user,去掉旧 shape
new_store = {}
for k, v in store.items():
    if v.get("typeName") != "shape":
        new_store[k] = v
for s in shapes:
    new_store[s["id"]] = s
seed["document"]["store"] = new_store

# 相机定位到全图中心(初开即见全图)
max_x = max(s["x"] for s in shapes) + 300
max_y = max(s["y"] for s in shapes) + 120
zx = 0.35
seed["session"]["pageStates"][0]["camera"] = {"x": max_x / 2, "y": max_y / 2, "z": zx}
seed["session"]["pageStates"][0]["selectedShapeIds"] = []

io.open(OUT, "w", encoding="utf-8").write(json.dumps(seed, ensure_ascii=False, indent=1))
print(f"已写入 {OUT}: {len(shapes)} 个指针节点, 布局范围 x≤{max_x} y≤{max_y}")
