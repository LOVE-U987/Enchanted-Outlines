# Enchanted Outlines 项目规则 (AGENTS.md)

> 项目专属开发规则,优先级高于个人全局规则。所有 AI 助手在改动本仓库前必须阅读本文件。

## 渲染架构铁律(违反即产生"正方形/立方体"描边 bug)

### 1. 形状纹理的 PNG 读取必须用 ImageIO,禁止 NativeImage.read
- **事故(2026-08-08, v0.1.3)**:`shapeTextureForLocation` 用 `NativeImage.read(InputStream)`
  读取盔甲/鞘翅贴图 → **palette(索引色)+ tRNS 透明** 的 PNG(原版盔甲/鞘翅/多数物品贴图都是)
  透明信息被丢失,解码后全像素 alpha=255 → 形状纹理 100% 实心 → 描边变成实心立方体。
- **规则**:读取独立纹理 PNG 时,一律用
  `javax.imageio.ImageIO.read(in)` 得到 `BufferedImage`,再逐像素 `getRGB(x,y)` 转 ABGR
  写入 `NativeImage.setPixelRGBA`。`NativeImage.read` 只可用于已确认 RGBA 格式的图片。
- 参考实现:`OutlineRenderer.shapeTextureForLocation`。

### 2. 亮度计算必须归一化到 0..1
- **事故(同上)**:`averageLuma` 的 Rec.601 加权平均后忘了 `÷255`,`exposureScale` 算出
  `scale≈113.9` → RGB 乘巨大值溢出 32 位 int → 颜色完全错乱。
- **规则**:任何像素亮度/颜色统计,输出必须归一化到 **0..1**(除以 255);返回 -1 表示失败。

### 3. 形状算法是"统一轮廓"设计,不得拆分
- 扁平物品 = 形状纹理(alpha 遮罩);3D 物品 = 几何外扩;盔甲/鞘翅/投掷物 = 逐 cube 放大壳。
- 这三条路径的**形状来源与几何**保持 v0.1.2 设计,任何优化(缓存/预处理)不得改变其语义。

## 性能优化纪律

### 4. 几何缓存不得改变渲染结果
- `ModelGeometry`(WeakHashMap)缓存 quads/法线外扩预处理/亮度是安全的,但:
  - **只缓存与帧无关的输入**(顶点坐标/UV/法线),帧内仍做矩阵变换;
  - 优化后必须在开启/关闭光影两种模式下验证形状与颜色不变。

### 5. 反射缓存的字段查找
- `spriteOriginalImage` 的 `originalImage` 字段:用 `findOriginalImageField` **沿父类链查找**
  + 按类缓存 Field;直接用 `getDeclaredField` 遇 SpriteContents 子类会 NoSuchFieldException。
- Iris 反射检测(`needVanillaShaderFallback`)可按 500ms 间隔缓存(热路径),切换光影包
  最多延迟半秒生效,可接受。

## 调试与提交纪律

### 6. 临时诊断代码(TestHook / ShapeDiag)必须清理
- 定位问题加的 tick 钩子、一次性日志、每帧打印,修复确认后**必须删除**,禁止留在代码里。
- 日志打印有性能成本,`LOGGER.info` 每帧刷屏会掩盖真实日志。

### 7. 配置界面改动必须失效解析缓存
- `Config.markChanged()`(配置界面保存)必须调用 `Config.invalidateCache()`,否则颜色/开关
  改动不生效直到重启。解析缓存只在 `ModConfigEvent` 时自动失效。

### 8. 验证工具优先用 Python
- 贴图格式/alpha 验证用 `tools/check_texture_alpha.py`、`tools/check_png_format.py`、
  `tools/check_png_trns.py`(基于 Pillow),不要手写 PNG 解码或猜。
- 截图分析用 `tools/analyze_screenshot.py`(统计描边色像素密度判断实心/轮廓)。

## 构建与测试

- 编译检查:`.\gradlew.bat compileJava`(PowerShell 的 exit code 1 是 gradle stderr 误报,
  看 BUILD SUCCESSFUL)。
- 运行:`.\gradlew.bat runClient "-PdevArgs=--quickPlaySingleplayer 新的世界"`。
- 渲染改动必须实际跑游戏验证,不能只编译通过就提交。
