"""
VERIFY 自检（负向测试）：确认每条检查**真的能抓到违规**。

「一个永远通过的检查等于没有检查」—— 铁律扫描是纯静态正则/结构分析，
重构一次就可能悄悄失效（漏检而不报错）。本工具向仓库**临时注入**已知违规，
断言 VERIFY 能报出对应规则，然后**无条件还原**，最后复查干净仓库必须 0 错误。

覆盖的注入场景：

1. 新建源码文件注入：`NativeImage.read`（铁律 1）、未登记缓存、`System.out`
   （铁律 6）、未闭合批次的 mixin 入口（铁律 6.7）、`markChanged` 缺缓存失效
   （铁律 7）、mixin 未注册告警；
2. 临时改写既有文件（备份 + finally 还原）：去掉序列图首帧裁剪（铁律 1.5）、
   去掉亮度 ÷255（铁律 2）、配置行缺语言键、mixins.json 指向不存在的类。

⚠️ 场景 2 会短暂改写 `OutlineRenderer.java` / `EnchantedConfigScreen.java` /
`enchanted_outlines.mixins.json`。所有改写都在 try/finally 中还原；若进程被强杀，
请用 `git checkout -- <文件>` 恢复（这三个文件的内容在测试前后完全一致）。

用法：

    python tools/verify_selftest.py
    python tools/verify_selftest.py --verbose      # 打印每次注入的 VERIFY 明细
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

import verify_common as vc

#: 注入用的一次性源码文件（会被创建后删除，不触碰任何既有文件）
FIXTURE_OUTLINE = """
package com.enchantedoutlines.mod.outline;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

/** VERIFY 自检夹具：故意违反多条铁律，验证扫描能抓到。 */
public class VerifySelfTestFixture {
    // 违规：缓存字段未登记到 any invalidate* 方法（资源重载后引用失效纹理）
    private final Map<String, String> selfTestCache = new HashMap<>();

    // 违规：铁律 1 —— NativeImage.read 读取纹理
    void readNative(InputStream in) throws Exception {
        NativeImage.read(in);
    }

    // 违规：铁律 1 —— ImageIO.read 出现在 readTextureFrame 之外
    void readImageIo(InputStream in) throws Exception {
        javax.imageio.ImageIO.read(in);
    }

    // 违规：铁律 6 —— 源码残留调试打印
    void debugPrint() {
        System.out.println("leftover debug");
    }

    // 违规：铁律 7 —— markChanged 未使配置缓存失效
    void markChanged() {
    }
}
"""

#: 注入用的 mixin 夹具（未闭合批次的描边入口 + 未注册到 mixins.json）
FIXTURE_MIXIN = """
package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.outline.OutlineRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VERIFY 自检夹具：描边入口缺 finally + flushOutlineBatch（铁律 6.7）。 */
@Mixin(Object.class)
public class VerifySelfTestFixtureMixin {

    @Inject(method = "toString", at = @At("HEAD"))
    private void selfTestOutline(CallbackInfo ci) {
        OutlineRenderer.INSTANCE.renderOutline(null, null, 0, 0f);
    }
}
"""


class Missing(Exception):
    """VERIFY 未能捕获注入的违规。"""


class SelfTest:
    """自检执行器：管理备份、注入、断言与还原。

    :param verbose: 是否打印每次注入后的 VERIFY 明细
    """

    def __init__(self, verbose: bool = False):
        self.verbose = verbose
        self.backup_dir = tempfile.mkdtemp(prefix="verify_selftest_")
        self.results: list[tuple[str, bool, str]] = []
        self.fixture_outline = os.path.join(vc.OUTLINE_DIR, "VerifySelfTestFixture.java")
        self.fixture_mixin = os.path.join(vc.MIXIN_DIR, "VerifySelfTestFixtureMixin.java")
        self.renderer = os.path.join(vc.OUTLINE_DIR, "OutlineRenderer.java")
        self.screen = vc.CONFIG_SCREEN
        self.mixins_json = self._mixins_json_path()

    def _mixins_json_path(self) -> str:
        """定位 mixins.json 路径。

        :return: 文件绝对路径
        """
        for name in sorted(os.listdir(vc.RESOURCES_DIR)):
            if name.endswith(".mixins.json"):
                return os.path.join(vc.RESOURCES_DIR, name)
        return os.path.join(vc.RESOURCES_DIR, "enchanted_outlines.mixins.json")

    # ---------- 基础操作 ----------

    def backup(self, path: str) -> str:
        """备份文件到临时目录。

        :param path: 待备份文件
        :return: 备份副本路径
        """
        dest = os.path.join(self.backup_dir, os.path.basename(path))
        shutil.copy2(path, dest)
        return dest

    def restore(self, path: str) -> None:
        """从临时备份还原文件。

        :param path: 待还原文件
        """
        dest = os.path.join(self.backup_dir, os.path.basename(path))
        if os.path.isfile(dest):
            shutil.copy2(dest, path)

    def run_verify(self) -> dict:
        """执行一次 VERIFY（跳过编译）并解析 JSON 结果。

        :return: VERIFY 的 JSON 报告
        """
        proc = subprocess.run(
            [sys.executable, os.path.join(vc.TOOLS_DIR, "verify.py"), "--no-compile", "--json"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=vc.ROOT,
        )
        try:
            report = json.loads(proc.stdout)
        except ValueError as exc:
            raise Missing(f"VERIFY 输出无法解析为 JSON（stdout 前 400 字符）：{proc.stdout[:400]}") from exc
        if self.verbose:
            for finding in report["findings"]:
                if finding["level"] != "ok":
                    print(f"        [{finding['level']}] {finding['rule']} {finding['message']}")
        return report

    def expect_error(self, label: str, rule: str, report: dict) -> bool:
        """断言报告中出现了指定规则的 error。

        :param label: 场景名
        :param rule: 期望的规则号（前缀匹配即可）
        :param report: VERIFY 报告
        :return: 是否捕获成功
        """
        caught = any(f["level"] == "error" and (f["rule"] == rule or f["rule"].startswith(rule))
                     for f in report["findings"])
        self.results.append((label, caught, rule))
        return caught

    def expect_warn(self, label: str, rule: str, keyword: str, report: dict) -> bool:
        """断言报告中出现了指定规则的 warn（消息含关键字）。

        :param label: 场景名
        :param rule: 规则号
        :param keyword: 消息关键字
        :param report: VERIFY 报告
        :return: 是否捕获成功
        """
        caught = any(f["level"] == "warn" and f["rule"] == rule and keyword in (f["message"] or "")
                     for f in report["findings"])
        self.results.append((label, caught, rule))
        return caught

    # ---------- 场景 ----------

    def scenario_fixtures(self) -> None:
        """场景 1：新建夹具文件注入多条违规（不触碰既有文件）。"""
        with open(self.fixture_outline, "w", encoding="utf-8") as fh:
            fh.write(FIXTURE_OUTLINE)
        with open(self.fixture_mixin, "w", encoding="utf-8") as fh:
            fh.write(FIXTURE_MIXIN)
        report = self.run_verify()
        self.expect_error("铁律 1   NativeImage.read 被捕获", "铁律 1", report)
        self.expect_error("缓存登记  未清理缓存被捕获", "缓存登记", report)
        self.expect_error("铁律 6   System.out 残留被捕获", "铁律 6", report)
        self.expect_error("铁律 6.7 未闭合批次被捕获", "铁律 6.7", report)
        self.expect_error("铁律 7   markChanged 缺失效被捕获", "铁律 7", report)
        self.expect_warn("mixin 注册  未注册类告警", "mixin 注册", "VerifySelfTest", report)
        os.remove(self.fixture_outline)
        os.remove(self.fixture_mixin)

    def scenario_frame_crop(self) -> None:
        """场景 2：去掉序列图首帧裁剪（铁律 1.5）。"""
        self.backup(self.renderer)
        raw = vc.read_text(self.renderer)
        patched = raw.replace("return cropAnimationFrame0(bi, texture);", "return bi;")
        if patched == raw:
            raise Missing("无法注入铁律 1.5 违规：OutlineRenderer 中未找到 cropAnimationFrame0 调用")
        with open(self.renderer, "w", encoding="utf-8") as fh:
            fh.write(patched)
        self.expect_error("铁律 1.5 未裁首帧被捕获", "铁律 1.5", self.run_verify())
        self.restore(self.renderer)

    def scenario_luma(self) -> None:
        """场景 3：去掉亮度 ÷255（铁律 2）。"""
        raw = vc.read_text(self.renderer)
        patched = raw.replace("count * 1000 * 255", "count * 1000")
        if patched == raw:
            raise Missing("无法注入铁律 2 违规：OutlineRenderer 中未找到 ÷255 归一化表达式")
        with open(self.renderer, "w", encoding="utf-8") as fh:
            fh.write(patched)
        self.expect_error("铁律 2   未归一化被捕获", "铁律 2", self.run_verify())
        self.restore(self.renderer)

    def scenario_lang_key(self) -> None:
        """场景 4：配置行引用了不存在的语言键。"""
        self.backup(self.screen)
        raw = vc.read_text(self.screen)
        anchor = 'y = addBooleanRow(y, "bewlr3dPerCube"'
        if anchor not in raw:
            raise Missing("无法注入语言键违规：配置界面中未找到 addBooleanRow 锚点")
        injected = ('y = addBooleanRow(y, "selfTestMissingKey", Config.ENABLE, widgetX);\n            '
                    + anchor)
        with open(self.screen, "w", encoding="utf-8") as fh:
            fh.write(raw.replace(anchor, injected, 1))
        self.expect_error("语言键    缺键/缺 tooltip 被捕获", "语言键", self.run_verify())
        self.restore(self.screen)

    def scenario_mixins_json(self) -> None:
        """场景 5：mixins.json 声明了不存在的 mixin 类。"""
        self.backup(self.mixins_json)
        try:
            cfg = json.loads(vc.read_text(self.mixins_json))
        except ValueError as exc:
            raise Missing(f"mixins.json 解析失败: {exc}") from exc
        cfg.setdefault("client", []).append("GhostMixinNotExist")
        with open(self.mixins_json, "w", encoding="utf-8") as fh:
            json.dump(cfg, fh, indent=2)
        self.expect_error("mixin 注册  指向不存在类被捕获", "mixin 注册", self.run_verify())
        self.restore(self.mixins_json)

    # ---------- 编排 ----------

    def run(self) -> int:
        """执行全部场景并输出结论。

        :return: 退出码（存在未捕获场景或环境未还原干净时为 1）
        """
        try:
            self.scenario_fixtures()
            self.scenario_frame_crop()
            self.scenario_luma()
            self.scenario_lang_key()
            self.scenario_mixins_json()
        except Missing as exc:
            self.results.append((f"注入失败：{exc}", False, "-"))
        finally:
            for path in (self.fixture_outline, self.fixture_mixin):
                if os.path.exists(path):
                    os.remove(path)
            for path in (self.renderer, self.screen, self.mixins_json):
                self.restore(path)

        # 还原复查：干净仓库必须 0 错误（证明自检没有污染工作区）
        try:
            clean = self.run_verify()
            clean_errors = clean["errors"]
        except Missing as exc:
            print(f"✗ 还原复查失败：{exc}", file=sys.stderr)
            shutil.rmtree(self.backup_dir, ignore_errors=True)
            return 1

        print("═" * 66)
        print(" VERIFY 自检（负向测试）")
        print("═" * 66)
        failed = 0
        for label, ok, rule in self.results:
            print(f"  {'✓' if ok else '✗ 未捕获'}  {label}"
                  + (f"   （期望规则: {rule}）" if rule != "-" else ""))
            if not ok:
                failed += 1
        print("─" * 66)
        print(f" 场景 {len(self.results) - failed}/{len(self.results)} 通过   "
              f"还原复查：干净仓库错误 {clean_errors}")
        if clean_errors:
            print(" ✗ 自检未还原干净工作区！请检查 git status 并手动恢复被改写的文件")
        else:
            print(" ✓ 自检未污染工作区（还原后 0 错误）")
        print("═" * 66)
        shutil.rmtree(self.backup_dir, ignore_errors=True)
        return 1 if (failed or clean_errors) else 0


def main(argv: list[str] | None = None) -> int:
    """命令行入口。

    :param argv: 参数列表（默认 sys.argv[1:]）
    :return: 退出码
    """
    parser = argparse.ArgumentParser(
        prog="verify_selftest",
        description="VERIFY 自检：注入已知违规，确认每条检查真的能抓到（结束后无条件还原）",
    )
    parser.add_argument("--verbose", action="store_true", help="打印每次注入后的 VERIFY 明细")
    args = parser.parse_args(argv)

    vc.enable_utf8_stdout()
    return SelfTest(verbose=args.verbose).run()


if __name__ == "__main__":
    sys.exit(main())
