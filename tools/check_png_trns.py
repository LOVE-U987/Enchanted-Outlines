"""
PNG tRNS 分块专项检查（铁律 1 的「事故形态」识别工具）。

`tRNS` 是 PNG 用「颜色键」而非 alpha 通道表达透明度的机制：

- 索引色（颜色类型 3）：tRNS 给出**每个调色板条目**的 alpha 字节；
- 灰度 / RGB（颜色类型 0 / 2）：tRNS 给出**一个**「透明色键」。

这正是 2026-08-08 事故的根因形态 —— 原版盔甲/鞘翅/大量物品贴图都是
「palette + tRNS」，`NativeImage.read` 解码后 alpha 全部变成 255，
描边因此变成实心立方体。本工具把这个形态显式打印出来，便于：

1. 确认某张贴图**是否**属于必须走 ImageIO 的高危形态；
2. 用 `--scan` 批量审计一个资源目录，列出全部高危贴图。

用法：

    python tools/check_png_trns.py path/to/tex.png
    python tools/check_png_trns.py --scan run/config          # 批量审计
    python tools/check_png_trns.py tex.png --json
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import verify_common as vc


def inspect(path: str) -> dict:
    """分析单张 PNG 的 tRNS 使用情况。

    :param path: PNG 路径
    :return: 结果字典（含 tRNS 明细、调色板规模与结论）
    """
    with open(path, "rb") as fh:
        data = fh.read()
    info = vc.parse_png(data)
    if info is None:
        return {"path": path, "error": "不是合法的 PNG 文件"}

    invisible = [i for i, a in enumerate(info.trns_alphas) if a == 0]
    semitransparent = [i for i, a in enumerate(info.trns_alphas) if 0 < a < 255]

    if not info.has_trns:
        verdict = "无 tRNS —— 透明度不依赖颜色键（全不透明，或由 alpha 通道表达）"
    elif info.color_type == 3:
        verdict = (
            f"高危：索引色 tRNS，{info.trns_transparent_entries}/{info.palette_entries} "
            "个调色板条目带透明信息 —— NativeImage.read 会丢失全部 alpha（铁律 1），"
            "CPU 端读取必须走 readTextureFrame（ImageIO）"
        )
    else:
        verdict = (
            f"高危：灰度/RGB tRNS 颜色键 {info.trns_gray_or_rgb} —— "
            "NativeImage.read 会把它读成全不透明（铁律 1）"
        )

    return {
        "path": path,
        "width": info.width,
        "height": info.height,
        "color_type": info.color_type,
        "color_type_name": info.color_type_name,
        "bit_depth": info.bit_depth,
        "has_trns": info.has_trns,
        "palette_entries": info.palette_entries,
        "transparent_entries": info.trns_transparent_entries,
        "fully_invisible_entries": invisible,
        "semitransparent_entries": semitransparent,
        "trns_gray_or_rgb": info.trns_gray_or_rgb,
        "risk": info.alpha_via_trns_only,
        "verdict": verdict,
    }


def render(result: dict) -> str:
    """渲染单张 PNG 的 tRNS 报告。

    :param result: `inspect` 的返回值
    :return: 多行文本
    """
    if "error" in result:
        return f"! {result['path']}\n    {result['error']}"
    lines = [
        f"● {result['path']}  {result['width']}×{result['height']}  "
        f"{result['color_type_name']} / {result['bit_depth']} bit",
        f"    tRNS: {'存在' if result['has_trns'] else '不存在'}",
    ]
    if result["has_trns"] and result["color_type"] == 3:
        lines.append(f"    调色板条目: {result['palette_entries']}  ·  "
                     f"带透明信息: {result['transparent_entries']}")
        if result["fully_invisible_entries"]:
            shown = result["fully_invisible_entries"][:16]
            more = "" if len(result["fully_invisible_entries"]) <= 16 else " ..."
            lines.append(f"    完全透明条目索引: {shown}{more}")
        if result["semitransparent_entries"]:
            lines.append(f"    半透明条目索引: {result['semitransparent_entries'][:16]}")
    elif result["has_trns"]:
        lines.append(f"    透明色键: {result['trns_gray_or_rgb']}")
    lines.append(f"    ▸ {result['verdict']}")
    return "\n".join(lines)


def scan_dir(root: str) -> list[str]:
    """递归枚举目录下的 PNG。

    :param root: 起始目录
    :return: PNG 路径列表
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
    :return: 退出码（发现高危贴图时为 1）
    """
    parser = argparse.ArgumentParser(
        prog="check_png_trns",
        description="PNG tRNS 分块检查：识别 NativeImage.read 会丢 alpha 的高危贴图（铁律 1）",
    )
    parser.add_argument("paths", nargs="*", help="PNG 路径")
    parser.add_argument("--scan", metavar="目录", help="递归审计该目录下全部 PNG")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
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

    results = [inspect(p) for p in targets]
    risky = [r for r in results if r.get("risk")]

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
    elif args.scan:
        print(f"扫描 {len(results)} 个 PNG；高危（索引/颜色键 + tRNS）{len(risky)} 个：")
        for r in risky:
            detail = (f"tRNS {r['transparent_entries']}/{r['palette_entries']} 条目"
                      if r["color_type"] == 3 else f"颜色键 {r['trns_gray_or_rgb']}")
            print(f"  ! {r['path']}  [{r['color_type_name']}, {detail}]")
        if not risky:
            print("  （无）")
    else:
        for i, r in enumerate(results):
            if i:
                print("")
            print(render(r))

    return 1 if risky else 0


if __name__ == "__main__":
    sys.exit(main())
