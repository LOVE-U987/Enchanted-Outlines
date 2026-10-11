"""
Enchanted Outlines 验证工具共享库。

本模块为 `verify.py` 与四个专项工具（check_png_format / check_texture_alpha /
check_png_trns / analyze_screenshot）提供公共能力，避免重复实现：

- <b>仓库路径发现</b>：定位 src/main/java、lang、mixin、outline 等约定目录；
- <b>Java 源码轻量解析</b>：注释/字符串剥离（保留偏移与行号）、括号配对、
  方法体定位、mixin 注解方法枚举 —— 供铁律静态扫描使用；
- <b>PNG 结构解析</b>：IHDR / tRNS 原始分块解析（不依赖 Pillow，因为铁律 1 的
  事故核心正是「palette + tRNS」这类 Pillow 会替你隐式展开的结构）；
- <b>序列图 mcmeta 解析</b>：还原帧高/帧数，供铁律 1.5 的序列图首帧检查；
- <b>报告设施</b>：统一的 Finding/Report（error/warn/ok 三级 + 行号 + 退出码）。

设计约束：仅依赖标准库 + Pillow（像素级统计用）；所有输出走 UTF-8。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys
from dataclasses import dataclass, field
from typing import Iterable, Iterator, Optional

# ==================================================================
# 路径约定
# ==================================================================

#: 仓库根目录（tools/ 的上级）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: Java 源码根
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
#: 本模组 Java 包根
MOD_ROOT = os.path.join(JAVA_ROOT, "com", "enchantedoutlines", "mod")
#: mixin 包目录
MIXIN_DIR = os.path.join(MOD_ROOT, "mixin")
#: 描边渲染包目录（缓存登记铁律的扫描范围）
OUTLINE_DIR = os.path.join(MOD_ROOT, "outline")
#: 配置界面源码（配置键/language 键铁律）
CONFIG_SCREEN = os.path.join(MOD_ROOT, "config", "EnchantedConfigScreen.java")
#: 配置源码（markChanged → invalidateCache 铁律）
CONFIG_JAVA = os.path.join(MOD_ROOT, "config", "Config.java")
#: 语言文件目录
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets",
                        "enchanted_outlines", "lang")
#: 资源目录（mixins.json 等）
RESOURCES_DIR = os.path.join(ROOT, "src", "main", "resources")
#: tools 目录自身
TOOLS_DIR = os.path.join(ROOT, "tools")

#: 配置键统一前缀（与 EnchantedConfigScreen.PREFIX 一致）
LANG_PREFIX = "enchanted_outlines.config"

#: 渐变协议：mixin 描边入口方法体内必须出现的批次闭合调用
FLUSH_CALL = "flushOutlineBatch"

#: 铁律 8 声明必须存在的验证工具（自洽性检查：文档不能引用不存在的工具）
REQUIRED_TOOLS = (
    "verify.py",
    "check_png_format.py",
    "check_texture_alpha.py",
    "check_png_trns.py",
    "analyze_screenshot.py",
)


def read_text(path: str) -> str:
    """读取文本文件（UTF-8，容忍 BOM）。

    :param path: 文件绝对路径
    :return: 文件内容；不存在时返回空串
    """
    if not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        return fh.read()


def iter_java_files(root: str = JAVA_ROOT) -> Iterator[str]:
    """递归枚举 Java 源文件。

    :param root: 起始目录
    :return: 绝对路径迭代器（跳过 build/ 等产物目录）
    """
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("build", "bin", ".git")]
        for name in sorted(filenames):
            if name.endswith(".java"):
                yield os.path.join(dirpath, name)


# ==================================================================
# Java 源码轻量解析
# ==================================================================


def strip_java(text: str) -> str:
    """剥离 Java 源码中的注释与字符串/字符字面量，用空格占位。

    <b>为什么必须剥离</b>：铁律扫描要区分「真实代码调用」与「注释里的反面教材」——
    例如 OutlineRenderer 的文档注释里写了大量 `NativeImage.read` 字样（说明为什么
    禁止），直接全文匹配会把正确代码误报为违规。

    <b>偏移保持</b>：被剥离的字符统一替换为空格、换行原样保留，因此剥离后的文本
    与原文**逐字节等长同偏移**，可以放心地用同一组下标回查原始行号。

    :param text: 原始源码
    :return: 等长同偏移的「代码骨架」文本
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = i
            while j < n and text[j] != "\n":
                out[j] = " "
                j += 1
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = i
            while j < n and not (text[j] == "*" and j + 1 < n and text[j + 1] == "/"):
                if text[j] != "\n":
                    out[j] = " "
                j += 1
            while j < n and j < i + 2:  # 保险：极端短注释
                out[j] = " "
                j += 1
            if j < n:
                out[j] = " "
                if j + 1 < n:
                    out[j + 1] = " "
                j += 2
            i = j
        elif text.startswith('"""', i):
            j = i + 3
            while j < n and not text.startswith('"""', j):
                if text[j] != "\n":
                    out[j] = " "
                j += 1
            end = min(j + 3, n)
            for k in range(i, end):
                out[k] = " "
            i = end
        elif c in ('"', "'"):
            quote = c
            out[i] = " "
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    out[j] = " "
                    if j + 1 < n:
                        out[j + 1] = " "
                    j += 2
                    continue
                if text[j] == quote:
                    out[j] = " "
                    j += 1
                    break
                if text[j] != "\n":
                    out[j] = " "
                j += 1
            i = j
        else:
            i += 1
    return "".join(out)


def brace_match(text: str, open_idx: int) -> int:
    """返回与 `open_idx` 处 `{` 配对的 `}` 下标。

    :param text: 已剥离注释/字符串的骨架文本
    :param open_idx: `{` 的下标
    :return: 配对 `}` 的下标；未闭合时返回文本末下标
    """
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def line_of(text: str, idx: int) -> int:
    """把字符下标换算成 1 起始的行号。

    :param text: 任意文本（通常为骨架文本，偏移与原文一致）
    :param idx: 字符下标
    :return: 行号（从 1 开始）
    """
    return text.count("\n", 0, max(0, idx)) + 1


def find_method(text: str, name: str) -> Optional[tuple[int, int]]:
    """按方法名定位方法体范围（骨架文本上操作）。

    通过「名称 → 配对右括号 → 其后首个 `{`」三步定位，因此不受参数中泛型
    （如 `Map<A, B>`）或注解参数干扰；抽象/接口声明（`;` 结尾）会被跳过。

    :param text: 骨架文本
    :param name: 方法名
    :return: `(body_start, body_end)`，即 `{` 与配对 `}` 的下标；未找到返回 None
    """
    pattern = re.compile(r"\b" + re.escape(name) + r"\s*\(")
    for m in pattern.finditer(text):
        i = m.end() - 1  # 指向 '('
        depth = 0
        j = i
        while j < len(text):
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if j >= len(text):
            continue
        brace = text.find("{", j)
        semi = text.find(";", j)
        if brace < 0 or (0 <= semi < brace):
            continue  # 抽象/接口方法：无方法体
        return brace, brace_match(text, brace)
    return None


def body_of(text: str, name: str) -> str:
    """取方法体文本（不含花括号本身）。

    :param text: 骨架文本
    :param name: 方法名
    :return: 方法体；未找到返回空串
    """
    found = find_method(text, name)
    if not found:
        return ""
    start, end = found
    return text[start + 1:end]


#: 非方法的「块关键字」：以其开头的花括号是控制流块而非方法体
#: （必须跳过，否则 `try (InputStream in = ...open()) {` 会被误认为名为 open() 的方法）
_BLOCK_KEYWORDS = frozenset({
    "try", "catch", "finally", "if", "else", "for", "while", "do",
    "switch", "case", "synchronized", "static", "return", "new",
})


def _signature_start(text: str, body_start: int) -> int:
    """向左定位某个 `{` 之前的签名起始偏移。

    :param text: 骨架文本
    :param body_start: 方法体 `{` 的下标
    :return: 签名起始下标（前一条 `;`/块边界之后）
    """
    depth = 0
    j = body_start - 1
    while j >= 0:
        c = text[j]
        if c == "}":
            depth += 1
        elif c == "{":
            if depth == 0:
                return j + 1
            depth -= 1
        elif c == ";" and depth == 0:
            return j + 1
        j -= 1
    return 0


def enclosing_method(text: str, idx: int) -> Optional[tuple[str, int, int]]:
    """定位包含下标 `idx` 的最内层**方法**。

    实现：先从下标向左收集**全部**包裹它的块起点（内 → 外），再逐个剔除控制流
    块（`try`/`if`/`for`… 其签名以块关键字开头），取第一个真正的方法。

    <b>为什么不能直接取最内层 `{`</b>：方法体内的 `try {` 块更「内」，
    直接取会把调用点归到名为 `open()`/`try` 的假方法上 —— 铁律 1 的
    `ImageIO.read`（位于 try-with-resources 内）会因此被误判为「不在
    readTextureFrame 中」。

    :param text: 骨架文本
    :param idx: 字符下标
    :return: `(方法名, 方法体起点, 方法体终点)`；无法判定返回 None
    """
    candidates: list[int] = []
    depth = 0
    i = idx
    while i >= 0:
        c = text[i]
        if c == "}":
            depth += 1
        elif c == "{":
            if depth == 0:
                candidates.append(i)  # 包裹 idx 的块起点（继续向外层搜索）
            else:
                depth -= 1
        i -= 1

    for body_start in candidates:
        sig_start = _signature_start(text, body_start)
        sig = text[sig_start:body_start].strip()
        if not sig:
            continue
        first = re.split(r"[\s(]", sig, maxsplit=1)[0].lower()
        if first in _BLOCK_KEYWORDS:
            continue
        names = re.findall(r"([A-Za-z_$][\w$]*)\s*\(", sig)
        if not names:
            continue
        return names[-1], body_start, brace_match(text, body_start)
    return None


def outline_methods_in_file(text: str) -> set[str]:
    """列出文件内**包含描边渲染调用**的方法名。

    用于解析「入口方法把描边委托给同文件辅助方法」的情形：真实的描边入口
    （`@Inject` 方法）体内可能只有一次 `renderOutlineInner(...)` 调用，
    真正的 `OutlineRenderer.INSTANCE.renderXxx` 在辅助方法里。

    :param text: 骨架文本
    :return: 方法名集合
    """
    names: set[str] = set()
    for m in OUTLINE_RENDER_CALL.finditer(text):
        enclosing = enclosing_method(text, m.start())
        if enclosing:
            names.add(enclosing[0])
    return names


def code_finditer(pattern: "re.Pattern[str]", raw: str, skel: str):
    """在**原文**上正则匹配，但丢弃落在注释/字符串里的命中。

    语言键检查必须看原文（键就是字符串字面量），但注释里可能写着示例键名。
    骨架文本与原文等长同偏移，因此可用「该位置在骨架里被抹成空格」判定命中
    是否位于注释/字符串内。

    :param pattern: 编译好的正则
    :param raw: 原始源码
    :param skel: 同源的骨架文本
    :return: 匹配对象迭代器（仅代码中的命中）
    """
    for m in pattern.finditer(raw):
        start = m.start()
        if start < len(skel) and skel[start] == " " and raw[start] != " ":
            continue  # 命中起点位于注释/字符串内
        yield m


#: 被视为「mixin 描边入口」的方法级注解（这些方法是批次的开头，必须闭合批次）
HANDLER_ANNOTATIONS = (
    "Inject", "ModifyVariable", "ModifyArg", "ModifyArgs", "ModifyConstant",
    "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver",
    "Redirect", "WrapOperation", "WrapWithCondition", "Overwrite",
)


@dataclass
class JavaMethod:
    """mixin 中的注解方法（描边入口候选）。

    :param annotation: 触发注解名（如 `Inject`）
    :param name: 方法名
    :param sig_start: 签名起点下标
    :param body_start: 方法体 `{` 下标
    :param body_end: 方法体配对 `}` 下标
    :param line: 方法签名所在行号
    """

    annotation: str
    name: str
    sig_start: int
    body_start: int
    body_end: int
    line: int

    def body(self, text: str) -> str:
        """返回方法体文本（不含花括号）。

        :param text: 骨架文本
        :return: 方法体
        """
        return text[self.body_start + 1:self.body_end]


def iter_annotated_methods(text: str) -> Iterator[JavaMethod]:
    """枚举 mixin 中带方法级注解的方法。

    注解参数里可能包含方法描述符（内含 `;`），因此必须在**骨架文本**上扫描
    ——字符串被剥离后这些分号才不会干扰「`;` 在前 = 抽象方法」的判定。

    :param text: 骨架文本
    :return: JavaMethod 迭代器
    """
    pattern = re.compile(r"@(" + "|".join(HANDLER_ANNOTATIONS) + r")\b")
    for m in pattern.finditer(text):
        i = m.end()
        # 跳过注解自身的参数括号
        if i < len(text) and text[i] == "(":
            depth = 0
            while i < len(text):
                if text[i] == "(":
                    depth += 1
                elif text[i] == ")":
                    depth -= 1
                    if depth == 0:
                        i += 1
                        break
                i += 1
        brace = text.find("{", i)
        semi = text.find(";", i)
        if brace < 0 or (0 <= semi < brace):
            continue  # @Accessor/@Invoker 等无方法体声明
        sig_start = text.rfind(";", 0, brace)
        sig_start = 0 if sig_start < 0 else sig_start + 1
        names = re.findall(r"([A-Za-z_$][\w$]*)\s*\(", text[i:brace])
        yield JavaMethod(
            annotation=m.group(1),
            name=names[-1] if names else "<unknown>",
            sig_start=sig_start,
            body_start=brace,
            body_end=brace_match(text, brace),
            line=line_of(text, brace),
        )


#: 描边渲染调用形态：实例入口（OutlineRenderer.INSTANCE.renderXxx）与静态入口
OUTLINE_RENDER_CALL = re.compile(
    r"OutlineRenderer\s*\.\s*(?:INSTANCE\s*\.\s*)?"
    r"(render[A-Z]\w*|emit[A-Z]\w*|draw[A-Z]\w*)\s*\("
)


# ==================================================================
# PNG 结构解析（原始分块，不依赖 Pillow）
# ==================================================================

#: PNG IHDR 颜色类型 → 人类可读名称（铁律 1 事故核心：3 = 索引色，常配 tRNS）
PNG_COLOR_TYPES = {
    0: "灰度 (Gray)",
    2: "真彩 RGB",
    3: "索引色 / 调色板 (Palette)",
    4: "灰度 + Alpha",
    6: "真彩 RGBA",
}

#: PNG 隔行扫描方式
PNG_INTERLACE = {0: "无 (None)", 1: "Adam7"}

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


@dataclass
class PngInfo:
    """PNG 结构信息（IHDR + tRNS + 分块概览）。

    :param width: 宽（像素）
    :param height: 高（像素）
    :param bit_depth: 位深
    :param color_type: IHDR 颜色类型（0/2/3/4/6）
    :param interlace: 隔行扫描方式
    :param has_trns: 是否存在 tRNS 分块
    :param trns_transparent_entries: tRNS 声明的透明调色板条目数（颜色类型 3）
    :param trns_gray_or_rgb: tRNS 声明的透明灰度/RGB 值（颜色类型 0/2），否则 None
    :param trns_alphas: tRNS 原始 alpha 字节序列（颜色类型 3 为每调色板条目一个字节）
    :param palette_entries: PLTE 调色板条目总数（颜色类型 3）
    :param chunks: 分块类型列表（按出现顺序）
    """

    width: int
    height: int
    bit_depth: int
    color_type: int
    interlace: int
    has_trns: bool
    trns_transparent_entries: int
    trns_gray_or_rgb: Optional[tuple[int, ...]]
    trns_alphas: tuple[int, ...] = ()
    palette_entries: int = 0
    chunks: list[str] = field(default_factory=list)

    @property
    def color_type_name(self) -> str:
        """颜色类型名称。"""
        return PNG_COLOR_TYPES.get(self.color_type, f"未知 ({self.color_type})")

    @property
    def is_palette(self) -> bool:
        """是否为索引色（调色板）PNG —— 铁律 1 的高危形态。"""
        return self.color_type == 3

    @property
    def has_alpha_channel(self) -> bool:
        """是否自带 alpha 通道（颜色类型 4/6）。"""
        return self.color_type in (4, 6)

    @property
    def alpha_via_trns_only(self) -> bool:
        """透明度是否只靠 tRNS 表达（索引色 / 灰度 / RGB + tRNS）。

        这些图片用 `NativeImage.read` 读取会丢失透明度（铁律 1 事故，
        2026-08-08：描边渲染成实心立方体）。
        """
        return self.has_trns and not self.has_alpha_channel


def parse_png(data: bytes) -> Optional[PngInfo]:
    """解析 PNG 的 IHDR/tRNS 等关键分块。

    刻意不使用 Pillow：铁律 1 的事故根因是 Pillow/NativeImage 会以不同方式
    展开「索引色 + tRNS」，必须直接读原始字节才能看到真实存储形态。

    :param data: PNG 文件原始字节
    :return: PngInfo；非法 PNG 返回 None
    """
    if len(data) < 8 or data[:8] != PNG_MAGIC:
        return None
    chunks: list[tuple[str, bytes]] = []
    i = 8
    while i + 8 <= len(data):
        length = int.from_bytes(data[i:i + 4], "big")
        ctype = data[i + 4:i + 8].decode("latin1", errors="replace")
        payload = data[i + 8:i + 8 + length]
        chunks.append((ctype, payload))
        i += 12 + length
        if ctype == "IEND":
            break

    ihdr = next((p for t, p in chunks if t == "IHDR"), None)
    if ihdr is None or len(ihdr) < 13:
        return None
    width = int.from_bytes(ihdr[0:4], "big")
    height = int.from_bytes(ihdr[4:8], "big")
    bit_depth = ihdr[8]
    color_type = ihdr[9]
    interlace = ihdr[12]

    trns = next((p for t, p in chunks if t == "tRNS"), None)
    plt = next((p for t, p in chunks if t == "PLTE"), None)
    trns_entries = 0
    trns_value: Optional[tuple[int, ...]] = None
    trns_alphas: tuple[int, ...] = ()
    if trns is not None:
        if color_type == 3:
            # tRNS 对索引色：每个调色板条目一个 alpha 字节
            trns_alphas = tuple(trns)
            trns_entries = sum(1 for b in trns if b < 255)
        elif color_type == 0 and len(trns) >= 2:
            trns_value = (int.from_bytes(trns[0:2], "big"),)
        elif color_type == 2 and len(trns) >= 6:
            trns_value = tuple(trns[k] for k in range(0, 6, 2))

    return PngInfo(
        width=width,
        height=height,
        bit_depth=bit_depth,
        color_type=color_type,
        interlace=interlace,
        has_trns=trns is not None,
        trns_transparent_entries=trns_entries,
        trns_gray_or_rgb=trns_value,
        trns_alphas=trns_alphas,
        palette_entries=(len(plt) // 3) if plt else 0,
        chunks=[t for t, _ in chunks],
    )


@dataclass
class McmetaAnimation:
    """`.png.mcmeta` 的 animation 声明（铁律 1.5：序列图只取首帧）。

    :param frame_width: 单帧宽（像素；mcmeta 未声明时按图宽）
    :param frame_height: 单帧高（像素；mcmeta 未声明时按图宽 —— vanilla 方帧规则）
    :param frame_count: 帧数（mcmeta 未声明时按 图高/帧高 推算）
    :param frametime: 默认帧持续 tick 数
    :param explicit_frames: mcmeta 是否显式给出 frames 列表
    """

    frame_width: int
    frame_height: int
    frame_count: int
    frametime: int
    explicit_frames: bool


def read_mcmeta_animation(png_path: str, png_width: int, png_height: int) -> Optional[McmetaAnimation]:
    """读取同名 `.png.mcmeta` 的动画声明。

    帧尺寸规则与 `OutlineRenderer.cropAnimationFrame0` 保持一致：mcmeta 可显式
    声明 `width`/`height`，缺省为「方帧」（帧高 = 图宽）。

    :param png_path: PNG 文件路径
    :param png_width: PNG 宽度（用于方帧推断）
    :param png_height: PNG 高度（用于帧数推断）
    :return: McmetaAnimation；无 mcmeta / 非动画返回 None
    """
    meta_path = png_path + ".mcmeta"
    if not os.path.isfile(meta_path):
        return None
    try:
        with open(meta_path, "r", encoding="utf-8-sig") as fh:
            meta = json.load(fh)
    except (OSError, ValueError):
        return None
    anim = meta.get("animation")
    if not isinstance(anim, dict):
        return None

    frame_width = int(anim.get("width", png_width) or png_width)
    frame_height = int(anim.get("height", png_width) or png_width)
    frames = anim.get("frames")
    if isinstance(frames, list) and frames:
        frame_count = len(frames)
        explicit = True
    else:
        frame_count = max(1, png_height // frame_height) if frame_height else 1
        explicit = False
    return McmetaAnimation(
        frame_width=frame_width,
        frame_height=frame_height,
        frame_count=frame_count,
        frametime=int(anim.get("frametime", 1) or 1),
        explicit_frames=explicit,
    )


def load_rgba(path: str):
    """用 Pillow 以 RGBA 方式载入图片（像素统计用）。

    :param path: 图片路径（PNG/JPG 等 Pillow 支持的格式）
    :return: `PIL.Image.Image`（RGBA 模式）
    :raises RuntimeError: 未安装 Pillow 或无法解码
    """
    try:
        from PIL import Image
    except ImportError as exc:  # pragma: no cover - 环境缺失时给出明确指引
        raise RuntimeError(
            "需要 Pillow 进行像素统计：请执行 `pip install Pillow`"
        ) from exc
    try:
        with open(path, "rb") as fh:
            data = fh.read()
        return Image.open(io.BytesIO(data)).convert("RGBA")
    except Exception as exc:  # PIL 的异常类型繁杂，统一转成可读信息
        raise RuntimeError(f"无法解码图片 {path}: {exc}") from exc


@dataclass
class AlphaStats:
    """alpha 分布统计（铁律 1 诊断核心：透明信息是否还在）。

    :param total: 总像素数
    :param transparent: 完全透明像素数（alpha == 0）
    :param opaque: 完全不透明像素数（alpha == 255）
    :param partial: 半透明像素数（0 < alpha < 255）
    """

    total: int
    transparent: int
    opaque: int
    partial: int

    @property
    def transparent_ratio(self) -> float:
        """完全透明像素占比（0..1）。"""
        return self.transparent / self.total if self.total else 0.0

    @property
    def opaque_ratio(self) -> float:
        """完全不透明像素占比（0..1）。"""
        return self.opaque / self.total if self.total else 0.0


def alpha_stats(img) -> AlphaStats:
    """统计图片的 alpha 分布。

    :param img: RGBA 模式图片（`load_rgba` 的返回值）
    :return: AlphaStats
    """
    hist = img.getchannel("A").histogram()
    return AlphaStats(
        total=sum(hist),
        transparent=hist[0],
        opaque=hist[255],
        partial=sum(hist[1:255]),
    )


# ==================================================================
# 报告设施
# ==================================================================

#: ANSI 颜色（仅在支持的终端启用）
_COLORS = {
    "reset": "\033[0m",
    "dim": "\033[2m",
    "bold": "\033[1m",
    "red": "\033[31m",
    "yellow": "\033[33m",
    "green": "\033[32m",
    "cyan": "\033[36m",
}

#: 三个级别的符号（ASCII 模式下替换为纯字符）
_SYMBOLS = {"ok": "✓", "warn": "⚠", "error": "✗"}
_SYMBOLS_ASCII = {"ok": "[OK]", "warn": "[WARN]", "error": "[FAIL]"}


@dataclass
class Finding:
    """一条检查结论。

    :param level: `ok` / `warn` / `error`
    :param rule: 铁律编号或检查项标识（如 `铁律 6.7`）
    :param message: 结论说明
    :param path: 关联文件（可选）
    :param line: 关联行号（可选）
    """

    level: str
    rule: str
    message: str
    path: Optional[str] = None
    line: Optional[int] = None


class Reporter:
    """统一输出器：人类可读报告 / JSON，并决定进程退出码。

    退出码约定：只要存在 error 级结论即返回 1，warn 不阻塞（铁律扫描的
    「宁可提示不要噪音」原则：确凿的架构违规阻塞，启发式提示只告警）。

    :param color: 是否启用 ANSI 颜色
    :param ascii_only: 是否只用 ASCII 符号（兼容 GBK 控制台）
    :param quiet: 是否抑制 ok 级明细
    """

    def __init__(self, color: bool = False, ascii_only: bool = False, quiet: bool = False):
        self.color = color
        self.symbols = _SYMBOLS_ASCII if ascii_only else _SYMBOLS
        self.quiet = quiet
        self.findings: list[Finding] = []
        self.sections: list[str] = []

    # ---------- 记录 ----------

    def ok(self, rule: str, message: str, path: Optional[str] = None, line: Optional[int] = None) -> None:
        """记录一条通过结论。"""
        self.findings.append(Finding("ok", rule, message, path, line))

    def warn(self, rule: str, message: str, path: Optional[str] = None, line: Optional[int] = None) -> None:
        """记录一条告警（不阻塞）。"""
        self.findings.append(Finding("warn", rule, message, path, line))

    def error(self, rule: str, message: str, path: Optional[str] = None, line: Optional[int] = None) -> None:
        """记录一条失败（阻塞，退出码 1）。"""
        self.findings.append(Finding("error", rule, message, path, line))

    def extend(self, findings: Iterable[Finding]) -> None:
        """批量记录结论。

        :param findings: Finding 序列
        """
        self.findings.extend(findings)

    def section(self, title: str) -> None:
        """标记一个新章节（人类可读输出用）。

        :param title: 章节标题
        """
        self.sections.append(title)

    # ---------- 统计 ----------

    def count(self, level: str) -> int:
        """按级别统计结论数。

        :param level: `ok` / `warn` / `error`
        :return: 条数
        """
        return sum(1 for f in self.findings if f.level == level)

    @property
    def has_error(self) -> bool:
        """是否存在阻塞级结论。"""
        return self.count("error") > 0

    @property
    def exit_code(self) -> int:
        """进程退出码（error → 1，否则 0）。"""
        return 1 if self.has_error else 0

    # ---------- 渲染 ----------

    def _paint(self, text: str, key: str) -> str:
        if not self.color or key not in _COLORS:
            return text
        return f"{_COLORS[key]}{text}{_COLORS['reset']}"

    def render(self) -> str:
        """渲染人类可读报告。

        :return: 多行字符串
        """
        lines: list[str] = []
        for finding in self.findings:
            if finding.level == "ok" and self.quiet:
                continue
            symbol = self.symbols[finding.level]
            color_key = {"ok": "green", "warn": "yellow", "error": "red"}[finding.level]
            where = ""
            if finding.path:
                rel = os.path.relpath(finding.path, ROOT)
                where = f"  {self._paint(rel, 'dim')}"
                if finding.line:
                    where += f":{finding.line}"
            lines.append(
                f"  {self._paint(symbol, color_key)} {self._paint(finding.rule, 'bold'):<10} "
                f"{finding.message}{where}"
            )
        return "\n".join(lines)

    def to_json(self) -> dict:
        """导出机器可读结果。

        :return: 可 JSON 序列化的字典
        """
        return {
            "ok": self.count("ok"),
            "warnings": self.count("warn"),
            "errors": self.count("error"),
            "exit_code": self.exit_code,
            "findings": [
                {
                    "level": f.level,
                    "rule": f.rule,
                    "message": f.message,
                    "path": os.path.relpath(f.path, ROOT) if f.path else None,
                    "line": f.line,
                }
                for f in self.findings
            ],
        }


def enable_utf8_stdout() -> None:
    """把 stdout/stderr 切到 UTF-8（Windows 中文控制台兼容）。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):  # pragma: no cover - 老 Python/重定向
            pass


def supports_color() -> bool:
    """判断当前终端是否适合输出 ANSI 颜色。

    :return: True 表示可以着色
    """
    if os.environ.get("NO_COLOR"):
        return False
    if not getattr(sys.stdout, "isatty", lambda: False)():
        return False
    if os.name == "nt":
        # Windows 控制台默认不解析 ANSI；仅在 Windows Terminal / 已开启 VT 时启用
        return bool(os.environ.get("WT_SESSION") or os.environ.get("TERM_PROGRAM"))
    return True
