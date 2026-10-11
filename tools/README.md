# tools/ · VERIFY 自动化验证系统

把 `AGENTS.md` 的渲染架构铁律与构建纪律，从**文档约定**变成**可执行的检查**。
仅依赖 **Python 3.8+**（贴图/截图像素统计需要 **Pillow**：`pip install Pillow`）。

## 快速开始

```bat
verify.bat                  :: 提交前跑这个：编译门禁 + 铁律扫描 + 语言键一致性（~10s）
verify.bat --no-compile     :: 跳过 gradle，秒级（改配置/语言键/文档时用）
verify.bat --only rules     :: 只跑铁律扫描
verify.bat --json           :: 机器可读（CI / 工具消费）
verify.bat --list           :: 列出全部检查项
verify.bat --install-hook   :: 装成 git pre-commit 钩子（可选，不覆盖已有钩子）
```

退出码：`0` 通过（可能含告警）· `1` 存在铁律违规 / 断言不符 · `2` 环境问题。

Linux/macOS 直接 `python tools/verify.py ...`。

## 检查清单

| 分组 | 检查项 | 分级 | 对应铁律 |
|---|---|---|---|
| compile | `gradlew compileJava` 必须 BUILD SUCCESSFUL | error | 构建与测试 |
| rules | 禁用 `NativeImage.read` 读取纹理 | error | 铁律 1 |
| rules | `ImageIO.read` 只能出现在 `readTextureFrame()` 内 | error | 铁律 1 |
| rules | `readTextureFrame()` 必须调用 `cropAnimationFrame0`（序列图首帧） | error | 铁律 1.5 |
| rules | `averageLuma()` 返回值必须 ÷255 归一化 | error | 铁律 2 |
| rules | 无 `System.out/err` 残留、无临时诊断文件 | error / warn | 铁律 6 |
| rules | mixin 描边入口必须 `finally` + `flushOutlineBatch()` | error（缺 `catch(Throwable)` 为 warn） | 铁律 6.7 |
| rules | outline 包内 cache 字段必须进 invalidate 清理链 | error | 缓存登记 |
| rules | `markChanged()` 必须调用 `Config.invalidateCache()` | error | 铁律 7 |
| rules | `mixins.json` 与 mixin 目录双向一致 | error / warn | — |
| rules | 铁律 8 引用的验证工具必须存在 | error | 铁律 8 |
| i18n | 配置行键 + tooltip 在 zh_cn/en_us 齐备、两语言键集一致、动态键存在 | error | 配置界面语言键必须同步 |

设计原则：**确凿的架构违规阻塞（error），启发式提示只告警（warn）** ——
不让噪音淹没真问题。`缓存登记` 的已知豁免项（如反射 `Method` 缓存）会在报告里
**显式打印豁免理由**，保持可审计而不是静默跳过。

## 检查本身的自检

```bat
python tools\verify_selftest.py            :: 负向测试：注入违规 → 断言能抓到 → 无条件还原
python tools\verify_selftest.py --verbose  :: 附带每次注入后的 VERIFY 明细
```

「一个永远通过的检查等于没有检查」。自检会向仓库**临时注入**已知违规
（`NativeImage.read`、未登记缓存、未闭合批次、缺语言键、mixins.json 幽灵类、
去掉首帧裁剪/去掉 ÷255 等），断言 VERIFY 逐条报出对应规则，然后**无条件还原**并
复查干净仓库必须 0 错误。

⚠️ 自检会短暂改写 `OutlineRenderer.java` / `EnchantedConfigScreen.java` /
`enchanted_outlines.mixins.json`（全部写在 try/finally 中还原）。**改动
`verify.py` 的检查逻辑后必须跑一次。**

## 专项工具

### `check_png_format.py` —— 存储形态（铁律 1 / 1.5）

```bat
python tools\check_png_format.py 贴图.png
python tools\check_png_format.py --scan run\config      :: 批量审计，只列高危
```

回答：透明明明是"索引色 + tRNS"吗（`NativeImage.read` 读了会丢 alpha → 描边变实心
立方体）？是不是序列图（多少帧、帧尺寸），CPU 端必须只取首帧？

### `check_texture_alpha.py` —— alpha 组成 → 形状纹理可用性（铁律 1 / 3.6）

```bat
python tools\check_texture_alpha.py 贴图.png --expect-transparent   :: 审计：应当有透明区域
python tools\check_texture_alpha.py 贴图.png --region 0,0,32,32     :: 只看某个部件区域
python tools\check_texture_alpha.py before.png --compare after.png  :: 修复前后对比
```

判定"全不透明 → 形状纹理退化成整块矩形（描边变正方形）"；或确认某部件
（如柠檬神光环）本就是全透明贴图，因此无描边属**贴图限制**而非 bug。

### `check_png_trns.py` —— tRNS 分块专项（铁律 1）

```bat
python tools\check_png_trns.py 贴图.png
python tools\check_png_trns.py --scan "run\GeoTexture Debug Printouts"
```

打印调色板规模、哪些条目被声明透明、完全透明条目索引；`--scan` 批量列出全部
"必须走 ImageIO"的高危贴图。

### `analyze_screenshot.py` —— 截图描边"实心 / 轮廓"判定

```bat
python tools\analyze_screenshot.py 截图.png --list-colors           :: 先看颜色分布
python tools\analyze_screenshot.py 截图.png --color FFD700 --expect outline
python tools\analyze_screenshot.py 截图.png --region 800,400,200,200 --color 55FF55
```

统计与目标描边色相近的像素，算**包围盒填充率**：实心块 ≈ 100%，线状轮廓明显偏低
（判定阈值 0.75 / 0.25）。`--expect outline|solid|partial` 可作回归断言（不符退出码 1）。

⚠️ 这是**辅助**手段，不替代实机观察：细长物体（剑/长枪）在小图上的填充率可能偏高，
可疑时配合 `--region` 聚焦目标物体后再判断。

## 文件结构

| 文件 | 作用 |
|---|---|
| `verify.py` | 主门禁（编译 + 铁律扫描 + 语言键） |
| `verify_common.py` | 共享库：仓库路径、Java 骨架解析、PNG/mcmeta 解析、报告设施 |
| `verify_selftest.py` | 检查的自检（负向测试 + 无条件还原） |
| `check_png_format.py` | PNG 存储形态 / 序列图 |
| `check_texture_alpha.py` | alpha 组成统计 |
| `check_png_trns.py` | tRNS 专项 + 批量审计 |
| `analyze_screenshot.py` | 截图描边密度判定 |

### 实现要点（改检查逻辑前先读）

- **Java 扫描一律在"骨架文本"上做**：`strip_java()` 把注释与字符串字面量抹成空格、
  **保持逐字节等长同偏移**。否则 OutlineRenderer 文档注释里那些 `NativeImage.read`
  反面教材会被误报为违规。
- **行号回查**：骨架与原文等长同偏移，所以 `line_of(skel, idx)` 对原文同样成立。
- **`enclosing_method()` 必须跳过控制流块**：直接取最内层 `{` 会把
  `try (InputStream in = ...open()) {` 里的调用归到名为 `open()` 的假方法上
  （曾经导致铁律 1 误报）。实现为从内向外收集候选块、剔除块关键字、取第一个真方法。
- **语言键在原文上匹配、借骨架排除注释**：键就是字符串字面量，
  用 `code_finditer(pattern, raw, skel)`（骨架里被抹白 ⇒ 命中在注释/字符串内 ⇒ 丢弃）。
- **入口判定不依赖被检查项本身**：铁律 6.7 先求"文件内含描边调用的方法集合"，
  入口 = 自身在集合内或调用了集合内方法。若用"body 里有 flush"当入口判据，
  一旦 flush 被删掉，入口会从检查范围里消失而静默通过。
