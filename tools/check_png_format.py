"""
PNG 存储格式检查（铁律 1 / 1.5 诊断工具）。

回答两个问题：

1. **这张贴图的透明度是怎么存的？** —— 索引色（调色板）+ tRNS 是铁律 1 事故的
   高危形态：`NativeImage.read` 会把这类 PNG 的 alpha 丢掉，导致形状纹理 100% 实心、
   描边渲染成实心立方体（2026-08-08, v0.1.3）。必须走 `ImageIO`。
2. **这是不是序列图？** —— 带 `.png.mcmeta` animation 声明的贴图纵向平铺多帧，
   CPU 端必须只取首帧（铁律 1.5），否则描边会烘成整张 sheet 全帧并集的巨块
   （2026-09-25, v0.2.1）。

用法：

    python tools/check_png_format.py path/to/tex.png [more.png ...]
    python tools/check_png_format.py --scan run/config   # 递归审计：列出所有高危贴图
    python tools/check_png_format.py tex.png --json
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import verify_common as vc


def describe(path: str) -> dict:
    """解析单张 PNG 的关键结构信息。

    :param path: PNG 路径
    :return: 结果字典（含 PNG 结构、mcmeta 动画、结论与告警列表）
    """
    with open(path, "rb") as fh:
        data = fh.read()
    info = vc.parse_png(data)
    if info is None:
        return {"path": path, "error": "不是合法的 PNG 文件（缺少 PNG 签名或 IHDR）"}

    result: dict = {
        "path": path,
        "size_bytes": len(data),
        "width": info.width,
        "height": info.height,
        "bit_depth": info.bit_depth,
        "color_type": info.color_type,
        "color_type_name": info.color_type_name,
        "interlace": vc.PNG_INTERLACE.get(info.interlace, str(info.interlace)),
        "has_trns": info.has_trns,
        "trns_transparent_entries": info.trns_transparent_entries,
        "trns_gray_or_rgb": info.trns_gray_or_rgb,
        "chunks": info.chunks,
        "notes": [],
        "risky_for_native_image_read": False,
    }

    # ---- 结论 1：透明度存储形态（铁律 1）----
    notes: list[str] = result["notes"]
    if info.alpha_via_trns_only:
        result["risky_for_native_image_read"] = True
        if info.is_palette:
            notes.append(
                f"高危：索引色 + tRNS（{info.trns_transparent_entries} 个透明调色板条目）"
                "—— NativeImage.read 会丢失全部 alpha（铁律 1 事故形态），必须用 ImageIO"
            )
        else:
            notes.append(
                "高危：灰度/RGB + tRNS —— 透明度只由 tRNS 表达，"
                "NativeImage.read 读取会变全不透明，必须用 ImageIO"
            )
    elif info.is_palette:
        notes.append("索引色（无 tRNS）：调色板无 alpha 语义，等同全不透明")
    elif info.has_alpha_channel:
        notes.append("自带 alpha 通道（灰度+Alpha / RGBA）：alpha 与像素一同存储")
    else:
        notes.append("全不透明（无 alpha 通道、无 tRNS）—— 作形状纹理会退化成整块矩形")

    # ---- 结论 2：序列图（铁律 1.5）----
    anim = vc.read_mcmeta_animation(path, info.width, info.height)
    if anim:
        result["animation"] = {
            "frame_width": anim.frame_width,
            "frame_height": anim.frame_height,
            "frame_count": anim.frame_count,
            "frametime": anim.frametime,
            "explicit_frames": anim.explicit_frames,
        }
        notes.append(
            f"序列图：{anim.frame_count} 帧（帧尺寸 {anim.frame_width}×{anim.frame_height}，"
            f"frametime={anim.frametime}）—— CPU 端必须经 readTextureFrame 只取首帧（铁律 1.5）"
        )
    else:
        result["animation"] = None
        if info.width and info.height > info.width and info.height % info.width == 0:
            notes.append(
                f"疑似序列图：高 {info.height} 是宽 {info.width} 的整数倍，但无 .png.mcmeta"
                "—— 若确实为动画贴图，缺 mcmeta 会导致取帧逻辑失效"
            )
    return result


def render(result: dict, ascii_only: bool = False) -> str:
    """把单张 PNG 的结果渲染为可读文本。

    :param result: `describe` 的返回值
    :param ascii_only: 只用 ASCII 符号
    :return: 多行文本
    """
    head = "!" if ascii_only else "⚠"
    tick = "v" if ascii_only else "✓"
    if "error" in result:
        return f"{head} {result['path']}\n    {result['error']}"
    lines = [
        f"● {result['path']}",
        f"    尺寸: {result['width']}×{result['height']}  位深: {result['bit_depth']}  "
        f"颜色类型: {result['color_type_name']}  隔行: {result['interlace']}  "
        f"文件: {result['size_bytes']} B",
        f"    tRNS: {'有' if result['has_trns'] else '无'}"
        + (f"（透明调色板条目 {result['trns_transparent_entries']}）"
           if result["color_type"] == 3 and result["has_trns"] else "")
        + (f"（透明键 {result['trns_gray_or_rgb']}）"
           if result["trns_gray_or_rgb"] else ""),
    ]
    if result["animation"]:
        a = result["animation"]
        lines.append(f"    序列图: {a['frame_count']} 帧 × {a['frame_width']}×{a['frame_height']}")
    for note in result["notes"]:
        mark = head if note.startswith(("高危", "疑似")) else tick
        lines.append(f"    {mark} {note}")
    return "\n".join(lines)


def scan_dir(root: str) -> list[str]:
    """递归找出目录下所有 PNG 路径。

    :param root: 起始目录
    :return: PNG 绝对路径列表
    """
    hits: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "build")]
        for name in sorted(filenames):
            if name.lower().endswith(".png"):
                hits.append(os.path.join(dirpath, name))
    return hits


def main(argv: list[str] | None = None) -> int:
    """命令行入口。

    :param argv: 参数列表（默认 sys.argv[1:]）
    :return: 退出码（发现高危贴图时为 1，便于脚本化审计）
    """
    parser = argparse.ArgumentParser(
        prog="check_png_format",
        description="PNG 存储格式检查：透明度形态（铁律 1）与序列图首帧（铁律 1.5）",
    )
    parser.add_argument("paths", nargs="*", help="PNG 文件路径")
    parser.add_argument("--scan", metavar="目录",
                        help="递归审计目录下所有 PNG，只列出高危（NativeImage.read 会丢 alpha）的贴图")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--ascii", action="store_true", help="只用 ASCII 符号")
    args = parser.parse_args(argv)

    vc.enable_utf8_stdout()

    targets = list(args.paths)
    if args.scan:
        if not os.path.isdir(args.scan):
            print(f"目录不存在: {args.scan}", file=sys.stderr)
            return 2
        targets.extend(scan_dir(args.scan))
    if not targets:
        parser.print_help()
        return 2

    results = [describe(p) for p in targets]

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
    elif args.scan:
        risky = [r for r in results if r.get("risky_for_native_image_read")]
        print(f"扫描 {len(results)} 个 PNG；高危（透明度仅靠 tRNS 表达）{len(risky)} 个：")
        for r in risky:
            print(f"  ! {r['path']}  [{r['color_type_name']}, tRNS 条目 {r['trns_transparent_entries']}]")
        if not risky:
            print("  （无）")
    else:
        for i, r in enumerate(results):
            if i:
                print("")
            print(render(r, ascii_only=args.ascii))

    risky_any = any(r.get("risky_for_native_image_read") for r in results)
    return 1 if risky_any else 0


if __name__ == "__main__":
    sys.exit(main())
