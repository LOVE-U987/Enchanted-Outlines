"""
贴图 alpha 组成检查（铁律 1 / 铁律 3.6 诊断工具）。

描边的「形状」来自贴图 alpha：alpha 在哪里，描边就在哪里。因此两类故障都可以
用本工具定位：

- **alpha 全丢**（铁律 1，2026-08-08 事故）：透明度靠 tRNS 表达的 PNG 被
  `NativeImage.read` 读取后全像素 alpha=255 → 形状纹理 100% 实心 → 描边变实心
  立方体。特征：`--expect-transparent` 下「完全不透明像素 = 100%」。
- **部件贴图本就全透明 / 全不透明**（铁律 3.6）：Geo 骨骼里贴图区域全透明的部件
  （如柠檬神光环 spin）没有描边，这是贴图限制而非 bug —— 用它确认「无描边」是否
  属于预期，而不是去改渲染代码。

还可以用 `--region` 只看贴图的某个矩形区域（多部件共用一张图时定位到具体部件）。

用法：

    python tools/check_texture_alpha.py path/to/tex.png
    python tools/check_texture_alpha.py tex.png --expect-transparent      # 审计：应当有透明区域
    python tools/check_texture_alpha.py tex.png --region 0,0,32,32        # 只看某个部件区域
    python tools/check_texture_alpha.py before.png --compare after.png    # 修复前后对比
"""

from __future__ import annotations

import argparse
import json
import sys

import verify_common as vc


def region_stats(path: str, region: tuple[int, int, int, int] | None) -> tuple[vc.AlphaStats, tuple[int, int]]:
    """统计图片（或指定区域）的 alpha 组成。

    :param path: 图片路径
    :param region: `(x, y, w, h)` 区域；None 表示整图
    :return: `(alpha 统计, 实际统计尺寸)`
    """
    img = vc.load_rgba(path)
    if region:
        x, y, w, h = region
        img = img.crop((x, y, x + w, y + h))
    return vc.alpha_stats(img), img.size


def verdict(stats: vc.AlphaStats) -> str:
    """给出面向「描边形状」的结论文案。

    :param stats: alpha 统计
    :return: 结论字符串（含 emoji 级别的语义前缀）
    """
    if stats.transparent == 0 and stats.partial == 0:
        return ("高危：全不透明 —— 形状纹理会退化成整块矩形（描边变正方形/实心）。"
                "若此贴图本应有透明区域，说明 alpha 在读取/解码环节丢失（铁律 1）")
    if stats.transparent == stats.total:
        return "全透明 —— 该区域没有任何不透明像素，描边为空（铁律 3.6：透明部件无描边属贴图限制）"
    return (f"存在透明区域（{stats.transparent_ratio:.1%} 全透明 + "
            f"{stats.partial / max(stats.total, 1):.1%} 半透明）→ 形状纹理可表达轮廓")


def render(path: str, stats: vc.AlphaStats, size: tuple[int, int],
           region: tuple[int, int, int, int] | None = None) -> str:
    """渲染单张图的可读报告。

    :param path: 图片路径
    :param stats: alpha 统计
    :param size: 实际统计尺寸
    :param region: 统计区域（可选）
    :return: 多行文本
    """
    scope = f"（区域 {region[0]},{region[1]},{region[2]}×{region[3]}）" if region else ""
    return "\n".join([
        f"● {path}  {size[0]}×{size[1]}{scope}",
        f"    总像素 {stats.total}  ·  全透明 {stats.transparent} ({stats.transparent_ratio:.1%})"
        f"  ·  全不透明 {stats.opaque} ({stats.opaque_ratio:.1%})"
        f"  ·  半透明 {stats.partial}",
        f"    ▸ {verdict(stats)}",
    ])


def main(argv: list[str] | None = None) -> int:
    """命令行入口。

    :param argv: 参数列表（默认 sys.argv[1:]）
    :return: 退出码（`--expect-transparent` 未满足时为 1）
    """
    parser = argparse.ArgumentParser(
        prog="check_texture_alpha",
        description="贴图 alpha 组成检查：形状纹理是否可用（铁律 1 / 3.6）",
    )
    parser.add_argument("paths", nargs="+", help="图片路径（PNG 等 Pillow 支持的格式）")
    parser.add_argument("--expect-transparent", action="store_true",
                        help="审计模式：该贴图应当含透明区域，全不透明则判失败（退出码 1）")
    parser.add_argument("--region", metavar="x,y,w,h",
                        help="只统计该矩形区域（定位多部件贴图中的某个部件）")
    parser.add_argument("--compare", metavar="另一张图",
                        help="与另一张图对比 alpha 组成（修复前后一致性核对）")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    args = parser.parse_args(argv)

    vc.enable_utf8_stdout()

    region: tuple[int, int, int, int] | None = None
    if args.region:
        try:
            parts = [int(v) for v in args.region.split(",")]
            if len(parts) != 4:
                raise ValueError
            region = (parts[0], parts[1], parts[2], parts[3])
        except ValueError:
            print("--region 需要 x,y,w,h 四个整数", file=sys.stderr)
            return 2

    payload: list[dict] = []
    failed = False
    try:
        for path in args.paths:
            stats, size = region_stats(path, region)
            payload.append({
                "path": path,
                "width": size[0],
                "height": size[1],
                "region": region,
                "total": stats.total,
                "transparent": stats.transparent,
                "opaque": stats.opaque,
                "partial": stats.partial,
                "transparent_ratio": round(stats.transparent_ratio, 6),
                "opaque_ratio": round(stats.opaque_ratio, 6),
                "fully_opaque": stats.transparent == 0 and stats.partial == 0,
                "verdict": verdict(stats),
            })
            if args.expect_transparent and stats.transparent == 0:
                failed = True
    except RuntimeError as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 2

    comparison: dict | None = None
    if args.compare:
        try:
            stats_b, size_b = region_stats(args.compare, region)
        except RuntimeError as exc:
            print(f"错误: {exc}", file=sys.stderr)
            return 2
        stats_a, size_a = region_stats(args.paths[0], region)
        same_total = stats_a.total == stats_b.total
        same_alpha = (stats_a.transparent == stats_b.transparent
                      and stats_a.opaque == stats_b.opaque)
        comparison = {
            "a": args.paths[0], "b": args.compare,
            "same_pixel_count": same_total, "same_alpha_composition": same_alpha,
            "a_stats": {"transparent": stats_a.transparent, "opaque": stats_a.opaque,
                        "partial": stats_a.partial},
            "b_stats": {"transparent": stats_b.transparent, "opaque": stats_b.opaque,
                        "partial": stats_b.partial},
        }

    if args.json:
        out = {"results": payload}
        if comparison:
            out["comparison"] = comparison
        print(json.dumps(out, ensure_ascii=False, indent=2))
    else:
        for i, item in enumerate(payload):
            if i:
                print("")
            stats = vc.AlphaStats(item["total"], item["transparent"], item["opaque"], item["partial"])
            print(render(item["path"], stats, (item["width"], item["height"]), region))
        if comparison:
            print("")
            mark = "✓" if comparison["same_alpha_composition"] else "✗"
            print(f"对比 {comparison['a']} ↔ {comparison['b']}")
            print(f"  {mark} alpha 组成{'一致' if comparison['same_alpha_composition'] else '不一致'}"
                  f"（A 透明 {comparison['a_stats']['transparent']} / B 透明 {comparison['b_stats']['transparent']}）")
            if not comparison["same_pixel_count"]:
                print("  ⚠ 两张图像素总数不同，尺寸/区域不一致，对比仅供参考")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
