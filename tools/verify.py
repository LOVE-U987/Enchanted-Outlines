"""
Enchanted Outlines · VERIFY 自动化验证系统（主入口）。

把仓库 `AGENTS.md` 里「用血换来的」渲染架构铁律与构建/测试纪律，从**文档约定**
变成**可执行的自动检查**。开发流程中的定位：

    ./verify.bat                 # 提交前一键跑：编译门禁 + 铁律扫描 + 配置/语言一致性
    ./verify.bat --no-compile    # 跳过 gradle（秒级，改文档/配置时用）
    ./verify.bat --only rules    # 只跑铁律扫描

检查分组：

1. <b>compile</b> 编译门禁 —— `gradlew compileJava` 必须 BUILD SUCCESSFUL；
2. <b>rules</b>   铁律静态扫描 —— 铁律 1 / 1.5 / 2 / 6 / 6.5 / 6.7 / 7 / 8
   共 9 项检查（纹理读取、亮度归一化、批次闭合、缓存登记、临时诊断代码、
   mixin 注册、工具自洽）；
3. <b>i18n</b>    配置与语言键 —— 配置界面每一行的中英键与 tooltip 必须齐全、
   两语言键集必须完全一致、动态键（分类/取色模式）必须存在。

分级约定：确凿的架构违规记 <b>error</b>（退出码 1，可用于 pre-commit/CI 阻塞）；
启发式提示记 <b>warn</b>（不阻塞，避免噪音淹没真问题）。

本脚本只做**静态**验证。渲染视觉正确性仍需按 AGENTS.md「构建与测试」实机跑游戏，
截图后可交给 `tools/analyze_screenshot.py` 做描边密度判定（实心 / 轮廓）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time

import verify_common as vc


# ==================================================================
# 1. 编译门禁
# ==================================================================

def check_compile(reporter: vc.Reporter, runner: str, timeout: int) -> None:
    """运行 `gradlew compileJava` 作为编译门禁。

    :param reporter: 结论收集器
    :param runner: gradle wrapper 可执行文件路径
    :param timeout: 超时秒数
    """
    if not os.path.isfile(runner):
        reporter.error("编译门禁", f"找不到 gradle wrapper: {runner}")
        return
    cmd = [runner, "compileJava"]
    if os.name == "nt":
        # .bat 必须经 cmd /c 调用；显式 UTF-8 代码页避免中文日志乱码
        cmd = ["cmd", "/c", runner, "compileJava"]
    started = time.time()
    try:
        proc = subprocess.run(
            cmd, cwd=vc.ROOT, capture_output=True, timeout=timeout,
            text=True, encoding="utf-8", errors="replace",
        )
    except subprocess.TimeoutExpired:
        reporter.error("编译门禁", f"gradlew compileJava 超时（>{timeout}s）")
        return
    except OSError as exc:
        reporter.error("编译门禁", f"无法执行 gradle: {exc}")
        return

    elapsed = time.time() - started
    output = (proc.stdout or "") + (proc.stderr or "")
    # ⚠️ 只看 BUILD SUCCESSFUL：PowerShell/cmd 下 gradle 的退出码存在误报
    # （AGENTS.md「构建与测试」已记录该现象），以构建结果文本为准。
    if "BUILD SUCCESSFUL" in output:
        reporter.ok("编译门禁", f"compileJava 通过（{elapsed:.1f}s）")
        return
    reporter.error("编译门禁", f"compileJava 失败（{elapsed:.1f}s），关键输出如下：")
    for line in _extract_error_lines(output):
        reporter.error("编译门禁", "  " + line)


def _extract_error_lines(output: str, limit: int = 12) -> list[str]:
    """从 gradle 输出中挑出最有诊断价值的行。

    :param output: gradle 完整输出
    :param limit: 最多返回行数
    :return: 形如 `文件:行: 错误信息` 的关键行；找不到时回退末尾若干行
    """
    hits = [ln.strip() for ln in output.splitlines()
            if re.search(r"error:|错误:|\.java:\d+", ln)]
    if hits:
        return hits[:limit]
    tail = [ln.strip() for ln in output.splitlines() if ln.strip()]
    return tail[-limit:]


# ==================================================================
# 2. 铁律静态扫描
# ==================================================================

def check_native_image_read(reporter: vc.Reporter) -> None:
    """铁律 1：禁止 `NativeImage.read` 读取纹理。

    事故（2026-08-08, v0.1.3）：palette（索引色）+ tRNS 透明 PNG 经
    `NativeImage.read` 解码后 alpha 全丢 → 形状纹理 100% 实心 → 描边变实心立方体。
    """
    pattern = re.compile(r"\bNativeImage\s*\.\s*read\s*\(")
    hits = 0
    for path in vc.iter_java_files():
        skel = vc.strip_java(vc.read_text(path))
        for m in pattern.finditer(skel):
            hits += 1
            reporter.error(
                "铁律 1",
                "禁止 NativeImage.read 读取纹理（palette+tRNS 丢 alpha → 描边变实心立方体），"
                "请改用 ImageIO + readTextureFrame 管线",
                path, vc.line_of(skel, m.start()),
            )
    if hits == 0:
        reporter.ok("铁律 1", "无 NativeImage.read 调用（纹理一律走 ImageIO）")


def check_imageio_entry(reporter: vc.Reporter) -> None:
    """铁律 1：`ImageIO.read` 只允许出现在 `readTextureFrame` 内。

    单一入口是「铁律 1 + 铁律 1.5」能同时成立的前提：入口内既用 ImageIO
    （保住 palette+tRNS 的 alpha），又裁序列图首帧（不把整张 sheet 烘成描边）。
    任何绕过入口的散点 ImageIO.read 都可能漏掉首帧裁剪。
    """
    pattern = re.compile(r"\bImageIO\s*\.\s*read\s*\(")
    allowed_method = "readTextureFrame"
    hits, outside = 0, 0
    for path in vc.iter_java_files():
        skel = vc.strip_java(vc.read_text(path))
        for m in pattern.finditer(skel):
            hits += 1
            enclosing = vc.enclosing_method(skel, m.start())
            name = enclosing[0] if enclosing else "<unknown>"
            if name != allowed_method:
                outside += 1
                reporter.error(
                    "铁律 1",
                    f"ImageIO.read 出现在 {name}() 中；CPU 端读独立纹理必须经 "
                    f"{allowed_method}()（ImageIO + mcmeta 首帧裁剪）",
                    path, vc.line_of(skel, m.start()),
                )
    if hits and outside == 0:
        reporter.ok("铁律 1", f"ImageIO.read 只出现在 {allowed_method}() 单一入口（{hits} 处）")
    elif hits == 0:
        reporter.warn("铁律 1", "未发现 ImageIO.read —— 若纹理读取路径被重构，请确认仍满足铁律 1")


def check_readtextureframe_crops(reporter: vc.Reporter) -> None:
    """铁律 1.5：`readTextureFrame` 必须裁序列图首帧。

    事故（2026-09-25, v0.2.1）：Hazen 序列图武器（64×320 = 5 帧）整张 sheet 被
    烘进形状纹理 → 描边渲染成整张 sheet 全帧并集巨块。
    """
    target = os.path.join(vc.OUTLINE_DIR, "OutlineRenderer.java")
    skel = vc.strip_java(vc.read_text(target))
    body = vc.body_of(skel, "readTextureFrame")
    if not body:
        reporter.error("铁律 1.5", "找不到 readTextureFrame() —— CPU 纹理读取单一入口丢失", target)
        return
    if "cropAnimationFrame0" not in body:
        reporter.error(
            "铁律 1.5",
            "readTextureFrame() 未调用 cropAnimationFrame0 —— 序列图会把整张 sheet 烘成描边巨块",
            target, vc.line_of(skel, vc.find_method(skel, "readTextureFrame")[0]),
        )
        return
    reporter.ok("铁律 1.5", "readTextureFrame() 已裁序列图首帧（mcmeta 感知）")


def check_average_luma_normalized(reporter: vc.Reporter) -> None:
    """铁律 2：亮度统计必须归一化到 0..1。

    事故（2026-08-08）：`averageLuma` 的 Rec.601 加权平均忘了 ÷255 →
    `exposureScale` 算出 scale≈113.9 → RGB 乘巨大值溢出 32 位 int → 颜色完全错乱。
    """
    target = os.path.join(vc.OUTLINE_DIR, "OutlineRenderer.java")
    skel = vc.strip_java(vc.read_text(target))
    found = vc.find_method(skel, "averageLuma")
    if not found:
        reporter.warn("铁律 2", "找不到 averageLuma() —— 亮度统计实现已变更，请人工确认归一化", target)
        return
    body = skel[found[0] + 1:found[1]]
    returns = re.findall(r"return\s+([^;]+);", body)
    if not returns:
        reporter.warn("铁律 2", "averageLuma() 无可识别的 return 语句，请人工确认归一化", target)
        return
    # -1 是「读取失败」哨兵（设计上不压暗），不参与归一化检查
    sentinel = re.compile(r"^-?\s*1(\.0+)?f?$")
    bad = [expr.strip() for expr in returns
           if not sentinel.match(expr.strip()) and "255" not in expr]
    if bad:
        reporter.error(
            "铁律 2",
            "averageLuma() 返回值疑似未 ÷255 归一化（0..1）→ exposureScale 溢出会毁掉颜色："
            + ", ".join(bad),
            target, vc.line_of(skel, found[0]),
        )
        return
    reporter.ok("铁律 2", "averageLuma() 返回值已 ÷255 归一化到 0..1")


def check_mixin_batch_closure(reporter: vc.Reporter) -> None:
    """铁律 6.7：mixin 描边入口必须闭合批次。

    事故（2026-09-25, v0.2.0）：「头顶/远处巨大模型」——描边 `getBuffer` 写入顶点后
    中途异常被顶层 `catch (Throwable ignored)` 吞掉且无 endBatch，半写脏批滞留共享
    builder，在错误的 GL 状态/坐标系下上屏。所有 mixin 顶层描边入口必须
    `try { 描边 } catch (Throwable) {} finally { flushOutlineBatch(); }`。

    只校验**注解入口方法**（@Inject 等）：静态辅助方法（如 renderItemOutline）由入口
    的 finally 统一兜底，不需要各自 flush。

    <b>入口判定不依赖 flush 是否存在</b>：先求出文件内「含描边渲染调用的方法」集合，
    入口 = 自身在该集合内，或调用了集合内的方法（委托式入口，如 GuiGraphicsMixin
    的 `renderOutline` → `renderOutlineInner`）。否则一旦有人删掉 flush，入口会从
    检查范围里「消失」而静默通过。
    """
    entry_count = 0
    problems = 0
    if not os.path.isdir(vc.MIXIN_DIR):
        reporter.warn("铁律 6.7", f"mixin 目录不存在: {vc.MIXIN_DIR}")
        return
    # 递归收集 mixin 目录(含子包,如 tacz/ 条件配置的 mixin)的全部 .java
    java_files: list[str] = []
    for dirpath, _dirnames, filenames in os.walk(vc.MIXIN_DIR):
        for n in filenames:
            if n.endswith(".java"):
                java_files.append(os.path.join(dirpath, n))
    java_files.sort()
    for path in java_files:
        name = os.path.basename(path)
        skel = vc.strip_java(vc.read_text(path))
        renderers = vc.outline_methods_in_file(skel)
        if not renderers:
            continue  # 该 mixin 不参与描边渲染（纯 @Accessor 等）
        file_entries = 0
        for meth in vc.iter_annotated_methods(skel):
            body = meth.body(skel)
            delegates = any(re.search(r"\b" + re.escape(r) + r"\s*\(", body)
                            for r in renderers if r != meth.name)
            if meth.name not in renderers and not delegates:
                continue  # 不做描边渲染的入口（如 flag 恢复），无需闭合批次
            file_entries += 1
            entry_count += 1
            has_finally = re.search(r"\bfinally\b", body) is not None
            has_flush = vc.FLUSH_CALL in body
            if not (has_finally and has_flush):
                problems += 1
                missing = []
                if not has_finally:
                    missing.append("finally 块")
                if not has_flush:
                    missing.append(f"{vc.FLUSH_CALL}() 调用")
                reporter.error(
                    "铁律 6.7",
                    f"描边入口 {meth.name}()@{meth.annotation} 缺 {' 与 '.join(missing)} "
                    "→ 异常时半写脏批会跨渲染阶段上屏（“头顶巨大模型”）",
                    path, meth.line,
                )
            elif not re.search(r"catch\s*\(\s*Throwable\b", body):
                reporter.warn(
                    "铁律 6.7",
                    f"描边入口 {meth.name}()@{meth.annotation} 未用 catch (Throwable) 兜底"
                    "（异常会外抛崩溃本体渲染）",
                    path, meth.line,
                )
        # 描边入口存在但整个文件一次 flush 都没有 → 必然泄漏
        if file_entries and vc.FLUSH_CALL not in skel:
            reporter.warn("铁律 6.7", f"{name} 有 {file_entries} 个描边入口却无 "
                                      f"{vc.FLUSH_CALL}() 调用", path)
    if problems == 0:
        reporter.ok("铁律 6.7", f"{entry_count} 个描边入口全部闭合批次（finally + flush）")


def check_cache_registration(reporter: vc.Reporter) -> None:
    """缓存登记铁律：新增缓存必须进 `invalidateCaches()` 清理链。

    事故（v0.1.7）：多人游戏「退出后重进渲染严重错误」——进服务器下载资源包触发资源
    重载，TextureManager 销毁全部动态纹理，而 RenderType/模型缓存从不清理 → 旧缓存
    引用失效资源。禁止新增「永不清理」的实例缓存。

    判定方式：outline 包内名字含 cache 的集合字段，必须出现在本类某个 `invalidate*`
    方法体中（直接 clear），或通过 `OutlineRenderer.invalidateCaches()` 的
    `.invalidate()` 委托清理（LionfishOutline / GeoOutline 子模块）。
    """
    #: 已知豁免：与资源重载无关的缓存（附理由，保持可审计）
    allowlist = {
        "GEO_METHOD_CACHE": "反射 Method 缓存：类结构不随资源重载变化，无需清理",
    }
    cache_field = re.compile(
        r"\b(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?"
        r"(?:[\w.]*Map|[\w.]*Set|[\w.]*List)\s*<[^>]*>\s+(\w+)\s*="
    )
    cleared_count, exempt_count = 0, 0
    problems = 0
    delegated: set[str] = set()

    for name in sorted(os.listdir(vc.OUTLINE_DIR)):
        if not name.endswith(".java"):
            continue
        path = os.path.join(vc.OUTLINE_DIR, name)
        skel = vc.strip_java(vc.read_text(path))
        fields = [(fname, m) for m in cache_field.finditer(skel)
                  for fname in [m.group(1)] if "cache" in fname.lower()]
        if not fields:
            continue
        # 本类所有 invalidate* 方法体（清理动作应写在这里）
        invalidate_body = "\n".join(
            vc.body_of(skel, mname) for mname in set(re.findall(r"\b(invalidate\w*)\s*\(", skel))
        )
        for fname, m in fields:
            if fname in invalidate_body:
                cleared_count += 1
            elif fname in allowlist:
                exempt_count += 1
                reporter.ok("缓存登记", f"{fname} 已豁免（{allowlist[fname]}）", path)
            else:
                problems += 1
                reporter.error(
                    "缓存登记",
                    f"缓存字段 {fname} 未出现在任何 invalidate* 方法中 → 资源重载后引用失效纹理",
                    path, vc.line_of(skel, m.start()),
                )

    # 委托清理必须真实存在：子模块缓存靠外层调用 .invalidate()
    target = os.path.join(vc.OUTLINE_DIR, "OutlineRenderer.java")
    skel = vc.strip_java(vc.read_text(target))
    body = vc.body_of(skel, "invalidateCaches")
    for m in re.finditer(r"\b(LionfishOutline|GeoOutline)\s+(\w+)\s*[;=]", skel):
        delegated.add(m.group(2))
    for fname in sorted(delegated):
        if not re.search(re.escape(fname) + r"\s*\.\s*invalidate\s*\(", body):
            problems += 1
            reporter.error(
                "缓存登记",
                f"invalidateCaches() 未调用 {fname}.invalidate() → 子模块缓存（{fname}）不会随资源重载清理",
                target, vc.line_of(skel, vc.find_method(skel, "invalidateCaches")[0]),
            )
    if problems == 0:
        reporter.ok(
            "缓存登记",
            f"{cleared_count} 个缓存字段已登记清理"
            + (f"，{exempt_count} 个已豁免（{len(delegated)} 个子模块委托清理已确认）" if exempt_count else ""),
        )


def check_markchanged_invalidates(reporter: vc.Reporter) -> None:
    """铁律 7：`markChanged()` 必须调用 `Config.invalidateCache()`。

    否则配置界面保存的颜色/开关改动不生效直到重启（解析缓存只在
    ModConfigEvent 时自动失效）。
    """
    checked = 0
    for path in vc.iter_java_files():
        skel = vc.strip_java(vc.read_text(path))
        found = vc.find_method(skel, "markChanged")
        if not found:
            continue
        checked += 1
        body = skel[found[0] + 1:found[1]]
        if "invalidateCache" not in body:
            reporter.error(
                "铁律 7",
                "markChanged() 未调用 Config.invalidateCache() → 配置改动不生效直到重启",
                path, vc.line_of(skel, found[0]),
            )
    if checked == 0:
        reporter.warn("铁律 7", "未找到 markChanged() —— 配置界面保存链路已变更，请人工确认缓存失效")
    elif not reporter.has_error:
        reporter.ok("铁律 7", f"{checked} 处 markChanged() 均会使配置缓存失效")


def check_temp_diagnostics(reporter: vc.Reporter) -> None:
    """铁律 6：临时诊断代码必须清理。

    tick 钩子、一次性日志、每帧打印在修复确认后必须删除；否则日志刷屏会掩盖真实
    问题，且每帧 IO 有性能成本。
    """
    temp_name = re.compile(r"(TestHook|ShapeDiag|DebugHook|PerfDiag)", re.IGNORECASE)
    files = list(vc.iter_java_files())
    sysout = 0
    for path in files:
        skel = vc.strip_java(vc.read_text(path))
        rel = os.path.relpath(path, vc.ROOT)
        for m in re.finditer(r"\bSystem\s*\.\s*(out\s*\.\s*print\w*|err\s*\.\s*print\w*)", skel):
            sysout += 1
            reporter.error(
                "铁律 6",
                "源码内残留 System.out/err 打印（临时诊断代码）→ 请改用 LOGGER 或删除",
                path, vc.line_of(skel, m.start()),
            )
        if temp_name.search(os.path.basename(path)):
            reporter.warn("铁律 6", f"疑似临时诊断类（文件名匹配诊断关键词）：{rel}", path)

    # tools/ 下的散落 .java 属于一次性调试脚本，按铁律 6 应清理
    if os.path.isdir(vc.TOOLS_DIR):
        for name in sorted(os.listdir(vc.TOOLS_DIR)):
            if name.endswith(".java"):
                reporter.warn(
                    "铁律 6",
                    f"tools/{name} 是临时调试用 Java 文件，修复确认后应删除",
                    os.path.join(vc.TOOLS_DIR, name),
                )
    if sysout == 0:
        reporter.ok("铁律 6", f"无 System.out/err 残留（扫描 {len(files)} 个源文件）")


def check_mixins_registration(reporter: vc.Reporter) -> None:
    """mixin 注册一致性：`*.mixins.json` 与 mixin 包目录双向对齐。

    mixins.json 列表指向不存在的类 = 启动崩溃；包内有未注册的 mixin =
    注入静默失效（描边无效果且无报错）。
    """
    if not os.path.isdir(vc.RESOURCES_DIR):
        reporter.warn("mixin 注册", f"资源目录不存在: {vc.RESOURCES_DIR}")
        return
    configs = [n for n in os.listdir(vc.RESOURCES_DIR) if n.endswith(".mixins.json")]
    if not configs:
        reporter.error("mixin 注册", "未找到 *.mixins.json")
        return

    declared: set[str] = set()
    plugins: set[str] = set()
    for cfg_name in configs:
        cfg_path = os.path.join(vc.RESOURCES_DIR, cfg_name)
        try:
            cfg = json.loads(vc.read_text(cfg_path))
        except ValueError as exc:
            reporter.error("mixin 注册", f"{cfg_name} 不是合法 JSON: {exc}", cfg_path)
            continue
        # 各配置可声明 IMixinConfigPlugin 插件类(条件应用) —— 插件不是 mixin,
        # 豁免"未注册"误报(如 enchanted_outlines.tacz.mixins.json 的 TaczMixinPlugin)
        plugin = cfg.get("plugin")
        if plugin:
            plugins.add(plugin.split(".")[-1])
        for key in ("client", "server", "mixins"):
            for entry in cfg.get(key) or []:
                # 允许 "com.foo.Bar" 形式，取末段类名
                declared.add(entry.split(".")[-1])

    # 递归收集 mixin 目录(含子包,如 tacz/ 条件配置的 mixin),按类名(文件名去 .java)对齐
    actual: set[str] = set()
    if os.path.isdir(vc.MIXIN_DIR):
        for dirpath, _dirnames, filenames in os.walk(vc.MIXIN_DIR):
            for n in filenames:
                if n.endswith(".java"):
                    actual.add(n[:-5])

    missing = sorted(declared - actual)
    for name in missing:
        reporter.error("mixin 注册", f"mixins.json 声明了 {name} 但 mixin 包内无此类 → 启动崩溃")
    unlisted = sorted(actual - declared - plugins)
    for name in unlisted:
        reporter.warn(
            "mixin 注册", f"mixin 包内的 {name} 未注册到 mixins.json → 注入静默失效",
            os.path.join(vc.MIXIN_DIR, f"{name}.java"),
        )
    if not missing and not unlisted:
        reporter.ok("mixin 注册", f"{len(declared)} 个 mixin 注册与目录完全对齐")


def check_required_tools(reporter: vc.Reporter) -> None:
    """铁律 8 自洽性：AGENTS.md 引用的验证工具必须真实存在。

    文档引用不存在的工具会误导后续开发（本项目历史上 tools/ 下多个 Python 工具
    长期缺失）。此检查保证「文档说得出，仓库找得到」。
    """
    missing = [n for n in vc.REQUIRED_TOOLS
               if not os.path.isfile(os.path.join(vc.TOOLS_DIR, n))]
    for name in missing:
        reporter.error("铁律 8", f"tools/{name} 缺失（AGENTS.md 铁律 8 引用了该工具）")
    if not missing:
        reporter.ok("铁律 8", f"{len(vc.REQUIRED_TOOLS)} 个验证工具齐备（verify / 贴图 / 截图分析）")


# ==================================================================
# 3. 配置与语言键一致性
# ==================================================================

def _row_keys(raw: str, skel: str) -> list[tuple[str, int]]:
    """从配置界面源码提取每一行的语言键。

    覆盖四种装配入口（布尔/小数/颜色/长文本）与字面量注册（mergeMode），
    统一以 `registerRow` 的 key 语义为准。

    <b>必须在原文上匹配</b>：语言键就是字符串字面量，骨架文本把字符串抹成了空格。
    用 `code_finditer` 在原文匹配、借骨架偏移排除注释里的示例键名。

    :param raw: EnchantedConfigScreen 原文
    :param skel: 同源骨架文本
    :return: `(key, 行号)` 列表
    """
    found: list[tuple[str, int]] = []
    for pattern in (re.compile(r"add\w*Row\s*\(\s*\w+\s*,\s*\"(\w+)\""),
                    re.compile(r"registerRow\s*\(\s*\"(\w+)\"")):
        for m in vc.code_finditer(pattern, raw, skel):
            found.append((m.group(1), vc.line_of(skel, m.start())))
    # 去重保序（同一 key 可能同时被上两条规则命中）
    seen: set[str] = set()
    unique: list[tuple[str, int]] = []
    for key, line in found:
        if key not in seen:
            seen.add(key)
            unique.append((key, line))
    return unique


def check_lang_keys(reporter: vc.Reporter) -> None:
    """配置界面语言键同步铁律。

    `EnchantedConfigScreen` 新增任何行都必须同步补 zh_cn/en_us 的
    `enchanted_outlines.config.<key>` 与 `<key>.tooltip`，否则界面显示原始键名。
    另检查两语言键集完全一致（缺失的翻译 = 界面出现英文/键名）。
    """
    lang_files = {
        "zh_cn": os.path.join(vc.LANG_DIR, "zh_cn.json"),
        "en_us": os.path.join(vc.LANG_DIR, "en_us.json"),
    }
    langs: dict[str, dict] = {}
    for tag, path in lang_files.items():
        if not os.path.isfile(path):
            reporter.error("语言键", f"缺少语言文件 {tag}.json", path)
            continue
        try:
            langs[tag] = json.loads(vc.read_text(path))
        except ValueError as exc:
            reporter.error("语言键", f"{tag}.json 不是合法 JSON: {exc}", path)

    if not langs:
        return

    # ---- 3.1 每个配置行必须有键 + tooltip ----
    raw = vc.read_text(vc.CONFIG_SCREEN)
    skel = vc.strip_java(raw)
    rows = _row_keys(raw, skel)
    if not rows:
        reporter.warn("语言键", "未从配置界面提取到任何行键 —— 装配方式可能已变更，请人工确认")
    missing = 0
    for key, line in rows:
        for suffix in ("", ".tooltip"):
            full = f"{vc.LANG_PREFIX}.{key}{suffix}"
            for tag, table in langs.items():
                if full not in table:
                    missing += 1
                    reporter.error(
                        "语言键",
                        f"配置行 “{key}” 缺少 {tag} 键 {full} → 界面将显示原始键名",
                        vc.CONFIG_SCREEN, line,
                    )

    # ---- 3.2 动态键：分类按钮与取色模式（由代码拼接，非字面量）----
    category_re = re.compile(r"addCategoryButton\s*\(\s*\w+\s*,\s*\"(\w+)\"")
    for m in vc.code_finditer(category_re, raw, skel):
        full = f"{vc.LANG_PREFIX}.category.{m.group(1)}"
        for tag, table in langs.items():
            if full not in table:
                missing += 1
                reporter.error("语言键", f"分类键缺失：{full}（{tag}）",
                               vc.CONFIG_SCREEN, vc.line_of(skel, m.start()))
    # 取色模式的可选值写成 List.of("highest", "first") —— 在原文中读值，
    # 方法体范围取自骨架（两者等长同偏移），从而避开注释里的同名片段
    merge_range = vc.find_method(skel, "addMergeModeRow")
    merge_raw = raw[merge_range[0]:merge_range[1]] if merge_range else ""
    for m in re.finditer(r"List\s*\.\s*of\s*\(([^)]*)\)", merge_raw):
        for value in re.findall(r"\"(\w+)\"", m.group(1)):
            full = f"{vc.LANG_PREFIX}.mergeMode.{value}"
            for tag, table in langs.items():
                if full not in table:
                    missing += 1
                    reporter.error("语言键", f"取色模式键缺失：{full}（{tag}）",
                                   vc.CONFIG_SCREEN,
                                   vc.line_of(skel, merge_range[0] + m.start()))

    # ---- 3.3 两语言键集必须完全一致 ----
    parity = 0
    if len(langs) == 2:
        zh, en = set(langs["zh_cn"]), set(langs["en_us"])
        for key in sorted(zh - en):
            parity += 1
            reporter.error("语言键", f"zh_cn 有而 en_us 缺失：{key}")
        for key in sorted(en - zh):
            parity += 1
            reporter.error("语言键", f"en_us 有而 zh_cn 缺失：{key}")

    if missing == 0 and parity == 0:
        reporter.ok(
            "语言键",
            f"{len(rows)} 个配置行的键与 tooltip 在 zh_cn/en_us 均齐备，两语言键集一致"
            f"（各 {len(langs.get('zh_cn', {}))} 项）",
        )


# ==================================================================
# 检查编排
# ==================================================================

#: (分组, 说明, 检查函数)
CHECKS: tuple[tuple[str, str, object], ...] = (
    ("compile", "编译门禁", check_compile),
    ("rules", "铁律 1   纹理读取禁用 NativeImage.read", check_native_image_read),
    ("rules", "铁律 1   ImageIO 单一入口", check_imageio_entry),
    ("rules", "铁律 1.5 序列图首帧裁剪", check_readtextureframe_crops),
    ("rules", "铁律 2   亮度归一化 0..1", check_average_luma_normalized),
    ("rules", "铁律 6   临时诊断代码清理", check_temp_diagnostics),
    ("rules", "铁律 6.7 描边批次闭合", check_mixin_batch_closure),
    ("rules", "缓存登记 资源重载清理链", check_cache_registration),
    ("rules", "铁律 7   配置缓存失效", check_markchanged_invalidates),
    ("rules", "mixin 注册一致性", check_mixins_registration),
    ("rules", "铁律 8   验证工具自洽", check_required_tools),
    ("i18n", "配置 / 语言键一致性", check_lang_keys),
)

GROUP_TITLES = {
    "compile": "编译门禁",
    "rules": "渲染架构铁律静态扫描",
    "i18n": "配置与语言键一致性",
}


def install_git_hook(reporter: vc.Reporter) -> int:
    """把 VERIFY 装成 pre-commit 钩子（可选，显式调用才执行）。

    默认不覆盖已存在的钩子：先人工确认原有钩子内容，避免破坏用户自己的流程。

    :param reporter: 结论收集器
    :return: 进程退出码
    """
    hooks_dir = os.path.join(vc.ROOT, ".git", "hooks")
    if not os.path.isdir(hooks_dir):
        reporter.error("安装钩子", f"找不到 .git/hooks（{hooks_dir}）—— 非 git 仓库？")
        return reporter.exit_code
    hook_path = os.path.join(hooks_dir, "pre-commit")
    if os.path.isfile(hook_path):
        reporter.warn("安装钩子", f"已存在 {hook_path}，未覆盖。如确认替换请先手动备份并删除")
        return reporter.exit_code
    script = (
        "#!/bin/sh\n"
        "# 由 tools/verify.py --install-hook 生成：提交前跑静态门禁\n"
        "# 如需跳过（紧急提交）：git commit --no-verify\n"
        "python \"$(dirname \"$0\")/../../tools/verify.py\" --no-color || exit 1\n"
    )
    try:
        with open(hook_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(script)
        os.chmod(hook_path, 0o755)
    except OSError as exc:
        reporter.error("安装钩子", f"写入钩子失败: {exc}")
        return reporter.exit_code
    reporter.ok("安装钩子", f"已安装 pre-commit 钩子: {hook_path}（--no-verify 可跳过）")
    return reporter.exit_code


def main(argv: list[str] | None = None) -> int:
    """VERIFY 主流程。

    :param argv: 命令行参数（默认取 sys.argv[1:]）
    :return: 进程退出码（存在 error 则 1）
    """
    parser = argparse.ArgumentParser(
        prog="verify",
        description="Enchanted Outlines 自动化验证系统（编译门禁 + 渲染铁律扫描 + 配置/语言一致性）",
        epilog="专项工具：check_png_format.py / check_texture_alpha.py / check_png_trns.py / analyze_screenshot.py",
    )
    parser.add_argument("--only", default="", metavar="组",
                        help="只跑指定分组，逗号分隔（compile,rules,i18n）；默认全部")
    parser.add_argument("--no-compile", action="store_true", help="跳过 gradle 编译门禁")
    parser.add_argument("--json", action="store_true", help="输出 JSON（供 CI / 工具消费）")
    parser.add_argument("--no-color", action="store_true", help="禁用 ANSI 颜色")
    parser.add_argument("--ascii", action="store_true", help="只用 ASCII 符号（兼容 GBK 控制台）")
    parser.add_argument("--quiet", action="store_true", help="只显示告警与失败")
    parser.add_argument("--timeout", type=int, default=900, help="编译门禁超时秒数（默认 900）")
    parser.add_argument("--install-hook", action="store_true",
                        help="把 VERIFY 安装为 git pre-commit 钩子（可选，不覆盖已有钩子）")
    parser.add_argument("--list", action="store_true", help="列出全部检查项后退出")
    args = parser.parse_args(argv)

    vc.enable_utf8_stdout()
    reporter = vc.Reporter(
        color=(not args.no_color and not args.json and vc.supports_color()),
        ascii_only=args.ascii,
        quiet=args.quiet,
    )

    if args.list:
        print("VERIFY 检查项：")
        for group, title, _ in CHECKS:
            print(f"  [{group}] {title}")
        return 0

    selected = {g.strip() for g in args.only.split(",") if g.strip()}
    started = time.time()

    if not args.json:
        print("═" * 66)
        print(f" Enchanted Outlines · VERIFY 自动化验证")
        print(f" 仓库: {vc.ROOT}")
        print(f" 时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")
        print("═" * 66)

    current_group = None
    for group, title, fn in CHECKS:
        if args.no_compile and group == "compile":
            continue
        if selected and group not in selected:
            continue
        if group != current_group:
            current_group = group
            if not args.json:
                print(f"\n▶ {GROUP_TITLES.get(group, group)}")
        if fn is check_compile:
            fn(reporter, os.path.join(vc.ROOT, "gradlew.bat" if os.name == "nt" else "gradlew"),
               args.timeout)
        else:
            fn(reporter)

    if args.install_hook:
        install_git_hook(reporter)

    elapsed = time.time() - started

    if args.json:
        payload = reporter.to_json()
        payload["elapsed_seconds"] = round(elapsed, 2)
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return reporter.exit_code

    # 汇总：按分组重放明细
    print("\n" + "─" * 66)
    print(" 明细")
    print("─" * 66)
    print(reporter.render())
    print("─" * 66)
    status = "✗ 失败" if reporter.has_error else ("⚠ 通过（有告警）" if reporter.count("warn") else "✓ 全部通过")
    print(f" 结果: {status}    错误 {reporter.count('error')} · 告警 {reporter.count('warn')} "
          f"· 通过 {reporter.count('ok')}    耗时 {elapsed:.1f}s")
    if not reporter.has_error:
        print(" 下一步: 渲染改动仍需实机验证（AGENTS.md「构建与测试」）——"
              "截图可交给 tools/analyze_screenshot.py 判定描边实心/轮廓")
    print("═" * 66)
    return reporter.exit_code


if __name__ == "__main__":
    sys.exit(main())
