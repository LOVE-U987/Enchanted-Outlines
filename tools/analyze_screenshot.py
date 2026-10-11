"""
描边截图分析（描边「实心 / 轮廓」自动判定）。

AGENTS.md 把「描边变成正方形 / 实心立方体」列为头号回归形态（铁律 1 / 3 / 3.5，
历史上反复出现）。肉眼判断依赖截图与经验，本工具把它量化：

1. 统计与**目标描边色**相近的像素（`--color` 指定，或自动推荐高饱和候选色）；
2. 计算这些像素的**包围盒填充率** fill = 描边色像素数 ÷ 包围盒面积；
   - 实心块（正方形/立方体）：fill ≈ 1.0；
   - 沿轮廓的一圈线：fill 明显偏低（线宽/尺寸比）；
3. 据此给出 **实心 (SOLID) / 形状块 (PARTIAL) / 轮廓 (OUTLINE)** 判定。

配合 `--expect outline` 可直接作为回归断言（判定不符退出码 1）。

⚠️ 本工具是**辅助**，不替代实机观察：判定阈值基于像素密度启发式，
细长物体（剑/长枪）与大面积实心物体在小图上的 fill 可能接近。可疑时用
`--list-colors` 看颜色分布、用 `--region` 聚焦目标物体后再判断。

用法：

    python tools/analyze_screenshot.py shot.png --list-colors
    python tools/analyze_screenshot.py shot.png --color FFD700 --expect outline
    python tools/analyze_screenshot.py shot.png --region 800,400,200,200 --color 55FF55
    python tools/analyze_screenshot.py before.png after.png --color FFD700
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import verify_common as vc


#: 判定阈值：包围盒填充率 ≥ 此值判为「实心」
SOLID_FILL_THRESHOLD = 0.75
#: 判定阈值：包围盒填充率 ≥ 此值判为「形状块（半填充）」
PARTIAL_FILL_THRESHOLD = 0.25


def parse_color(text: str) -> tuple[int, int, int] | None:
    """解析 `RRGGBB` / `#RRGGBB` 颜色。

    :param text: 颜色字符串
    :return: `(r, g, b)`；非法返回 None
    """
    value = text.strip().lstrip("#")
    if len(value) != 6:
        return None
    try:
        return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16))
    except ValueError:
        return None


def color_candidates(rgb, top: int) -> tuple[list[tuple[int, tuple[int, int, int]]], list[tuple[int, tuple[int, int, int]]]]:
    """统计截图中出现频率最高的颜色，并挑出高饱和候选（描边色通常饱和度高）。

    :param rgb: RGB 模式图片
    :param top: 返回的高频颜色数
    :return: `(高频颜色列表, 高饱和候选列表)`，元素为 `(像素数, (r,g,b))`
    """
    from PIL import Image

    quantized = rgb.quantize(colors=64, method=Image.Quantize.MEDIANCUT).convert("RGB")
    counted = quantized.getcolors(maxcolors=1 << 22) or []
    counted.sort(reverse=True, key=lambda item: item[0])
    saturated = [(count, px) for count, px in counted if max(px) - min(px) >= 60]
    return counted[:top], saturated[:top]


def mask_for_color(rgb, target: tuple[int, int, int], tolerance: int):
    """生成「与目标色相近」的二值掩码（纯 Pillow C 实现，支持 4K 截图）。

    判定：三通道差值的最大值 ≤ tolerance。

    :param rgb: RGB 模式图片
    :param target: 目标颜色
    :param tolerance: 每通道容差
    :return: `L` 模式掩码图（命中 = 255）
    """
    from PIL import Image, ImageChops

    diff = ImageChops.difference(rgb, Image.new("RGB", rgb.size, target))
    r, g, b = diff.split()
    max_diff = ImageChops.lighter(ImageChops.lighter(r, g), b)
    return max_diff.point(lambda v: 255 if v <= tolerance else 0)


def verdict_for(fill_ratio: float, matched: int) -> tuple[str, str]:
    """由填充率给出判定与结论文案。

    :param fill_ratio: 包围盒填充率（0..1）
    :param matched: 命中像素数
    :return: `(判定类别, 说明文案)`；类别为 OUTLINE / PARTIAL / SOLID / NONE
    """
    if matched == 0:
        return "NONE", "未检出目标颜色像素 —— 用 --list-colors 查看实际颜色，或用 --color 指定描边色"
    if fill_ratio >= SOLID_FILL_THRESHOLD:
        return "SOLID", (f"实心 (SOLID)：包围盒填充率 {fill_ratio:.1%} —— "
                         "疑似「正方形/实心立方体」回归（铁律 1/3/3.5），检查形状纹理 alpha 是否丢失")
    if fill_ratio >= PARTIAL_FILL_THRESHOLD:
        return "PARTIAL", (f"形状块 (PARTIAL)：包围盒填充率 {fill_ratio:.1%} —— 介于轮廓与实心之间，"
                           "细长物体的描边带会落在这一档，建议结合实机观察")
    return "OUTLINE", f"轮廓 (OUTLINE)：包围盒填充率 {fill_ratio:.1%} —— 呈线状轮廓，符合预期"


def analyze(path: str, color: tuple[int, int, int] | None, tolerance: int,
            region: tuple[int, int, int, int] | None, top: int) -> dict:
    """分析单张截图。

    :param path: 截图路径
    :param color: 目标描边色；None 时自动挑选高饱和候选
    :param tolerance: 颜色容差
    :param region: 只分析该矩形区域；None 为整图
    :param top: 高频颜色返回数量
    :return: 结果字典
    """
    img = vc.load_rgba(path).convert("RGB")
    if region:
        x, y, w, h = region
        img = img.crop((x, y, x + w, y + h))

    top_colors, saturated = color_candidates(img, top)
    result: dict = {
        "path": path,
        "size": list(img.size),
        "region": region,
        "tolerance": tolerance,
        "top_colors": [{"rgb": f"{px[0]:02X}{px[1]:02X}{px[2]:02X}", "count": count}
                       for count, px in top_colors],
        "saturated_candidates": [{"rgb": f"{px[0]:02X}{px[1]:02X}{px[2]:02X}", "count": count}
                                 for count, px in saturated],
    }

    target = color
    auto = False
    if target is None and saturated:
        target = saturated[0][1]
        auto = True
    result["target_rgb"] = f"{target[0]:02X}{target[1]:02X}{target[2]:02X}" if target else None
    result["target_auto"] = auto

    if target is None:
        result.update({"matched": 0, "matched_ratio": 0.0, "fill_ratio": 0.0,
                       "class": "NONE", "verdict": "图中没有高饱和色块；请用 --color 明确指定描边色"})
        return result

    mask = mask_for_color(img, target, tolerance)
    matched = mask.histogram()[255]
    total = img.size[0] * img.size[1]
    bbox = mask.getbbox()
    bbox_area = ((bbox[2] - bbox[0]) * (bbox[3] - bbox[1])) if bbox else 0
    fill_ratio = (matched / bbox_area) if bbox_area else 0.0
    cls, text = verdict_for(fill_ratio, matched)

    result.update({
        "matched": matched,
        "matched_ratio": round(matched / total, 6) if total else 0.0,
        "bbox": list(bbox) if bbox else None,
        "bbox_area": bbox_area,
        "fill_ratio": round(fill_ratio, 6),
        "class": cls,
        "verdict": text,
    })
    return result


def render(result: dict) -> str:
    """渲染单张截图的分析报告。

    :param result: `analyze` 的返回值
    :return: 多行文本
    """
    lines = [f"● {result['path']}  {result['size'][0]}×{result['size'][1]}"
             + (f"  区域 {result['region']}" if result.get("region") else "")]
    if result.get("target_rgb"):
        origin = "自动推荐" if result.get("target_auto") else "指定"
        lines.append(f"    目标色 #{result['target_rgb']}（{origin}）  容差 {result['tolerance']}")
    if result.get("matched"):
        lines.append(f"    命中像素 {result['matched']}  占画面 {result['matched_ratio']:.2%}  "
                     f"包围盒 {result['bbox']}  填充率 {result['fill_ratio']:.1%}")
    else:
        lines.append("    命中像素 0")
    lines.append(f"    ▸ {result['verdict']}")
    if result.get("target_auto") and result.get("saturated_candidates"):
        top = result["saturated_candidates"][:5]
        lines.append("    高饱和候选色：" + "  ".join(f"#{c['rgb']}({c['count']})" for c in top))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    """命令行入口。

    :param argv: 参数列表（默认 sys.argv[1:]）
    :return: 退出码（`--expect` 判定不符时为 1）
    """
    parser = argparse.ArgumentParser(
        prog="analyze_screenshot",
        description="描边截图分析：由描边色像素密度判定实心 / 轮廓（辅助实机回归验证）",
    )
    parser.add_argument("paths", nargs="+", help="截图路径（可多张对比）")
    parser.add_argument("--color", metavar="RRGGBB", help="目标描边色；省略时自动推荐高饱和候选")
    parser.add_argument("--tolerance", type=int, default=40, help="颜色容差（每通道，默认 40）")
    parser.add_argument("--region", metavar="x,y,w,h", help="只分析该矩形区域（聚焦目标物体）")
    parser.add_argument("--expect", choices=("outline", "solid", "partial"),
                        help="断言期望判定；不符则退出码 1（回归断言）")
    parser.add_argument("--list-colors", action="store_true", help="只列出高频颜色与高饱和候选")
    parser.add_argument("--top", type=int, default=12, help="高频颜色返回数量（默认 12）")
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

    color = None
    if args.color:
        color = parse_color(args.color)
        if color is None:
            print("--color 需要 RRGGBB 形式的十六进制颜色", file=sys.stderr)
            return 2

    try:
        results = [analyze(p, color, args.tolerance, region, args.top) for p in args.paths]
    except RuntimeError as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
    elif args.list_colors:
        for r in results:
            print(f"● {r['path']}  {r['size'][0]}×{r['size'][1]}")
            print("    高频颜色：")
            for c in r["top_colors"]:
                print(f"      #{c['rgb']}  {c['count']} px")
            print("    高饱和候选（描边色优先从这里选）：")
            for c in r["saturated_candidates"]:
                print(f"      #{c['rgb']}  {c['count']} px")
    else:
        for i, r in enumerate(results):
            if i:
                print("")
            print(render(r))

    if args.expect:
        expected = args.expect.upper()
        mismatched = [r for r in results if r["class"] != expected]
        if mismatched:
            print(f"\n✗ 判定不符：期望 {expected}，实际 "
                  + ", ".join(f"{os.path.basename(r['path'])}={r['class']}" for r in mismatched),
                  file=sys.stderr)
            return 1
        print(f"\n✓ 判定符合期望（{expected}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
