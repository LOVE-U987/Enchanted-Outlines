# Enchanted Outlines Changelog

---

## v0.1.3 (2026-08-08)

### 文档: 关键代码区域写入防呆注释(AGENTS.md 铁律内联)

- 目的: 把仓库根目录 `AGENTS.md` 的渲染架构铁律<b>内联到代码事故点</b>,
  任何 AI/开发者阅读或修改这些区域时立即看到警示,避免重蹈 v0.1.5/v0.1.6 覆辙。
- 覆盖位置(`OutlineRenderer.java`,共 19 处 ⚠️ 注释):
  - 类头 Javadoc:渲染架构铁律总览(ImageIO 读取 / 亮度归一化 / 统一轮廓设计);
  - `shapeTextureForLocation`:ImageIO 而非 NativeImage.read 的铁律(palette+tRNS);
  - `averageLuma` / `exposureScale` / `darkenByLuma` / `lumaOf`:亮度必须 0..1 归一化,
    scale 溢出破坏颜色;失败返回 -1 只"不压暗"绝不中断形状生成;
  - `spriteOriginalImage`:沿父类链查找 + 按类缓存(getDeclaredField 会 NoSuchFieldException);
  - NativeImage 像素读写:ABGR 打包方向警示(两处);
  - `ModelGeometry` / `collectQuads`:几何缓存纪律(只缓存帧无关输入,热路径勿重算);
  - `renderHandOutline` / `renderFlatPureColorShape` / `armorOutlineRenderType`:
    统一轮廓算法不可拆分(扁平=形状纹理、3D=几何外扩、盔甲=逐 cube 放大壳);
  - `needVanillaShaderFallback`:热路径 500ms 反射缓存说明。
- 纯注释改动,无任何逻辑变更;`compileJava` 验证通过。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

---



### 优化: 渲染热路径去除重复计算(Uniform/法线/矩阵/临时对象)

- **Uniform 引用缓存**:`setOutlineAlphaBoost` / `setOutlineCutout` 改为在
  `setOutlineShader` 时缓存 `Uniform` 引用(资源重载时随 shader 刷新),消除
  GUI 每格每帧 2 次 `getUniform` 字符串查找。
- **复用缓存的 quad 法线**:扁平物品 8 方向循环里,每个 quad 原每方向调一次
  `safeQuadNormal`(null direction 时做叉积+sqrt);现在 `emitQuad` /
  `emitQuadShape` 直接接收 `ModelGeometry.expandNormals` 缓存的法线,8 方向
  只算一次。
- **GUI 扁平路径矩阵链提出循环**:`scale(16)` + `display transform` + `居中`
  是方向无关的基础变换,原每方向重复构建;现在只应用一次,每方向仅做一次
  平移(模型空间偏移 = 像素/16,数学等价)。8 方向 × 64 格省 7/8 的矩阵乘法。
- **sprite → quad 索引缓存**:`ModelGeometry` 新增 `spriteQuadIndices`
  (构建时一次算好),`renderFlatPureColorShape` 按索引取法线,消除每帧 `indexOf`。
- **`renderPartInflated` 复用 Matrix4f**:盔甲逐 cube 放大壳不再每 cube 新建
  3 个 Matrix4f,改用复用的 `original` / `transform` 临时对象。
- **`needVanillaShaderFallback()` 局部变量化**:`renderHandOutline` 只调一次,
  不再两次触发(反射检测已有 500ms 缓存,此为消除重复调用)。
- **移除每帧刷屏的调试日志**:`HumanoidArmorLayerMixin` 的 "Armor outline rendered"
  与 "Armor item not enchanted" 每帧每槽位打印(实测单次运行产生 **28.9 万行**
  日志),`ThrownTridentRendererMixin` 的一次性日志一并清理 —— 消除每帧字符串
  格式化 + I/O 开销,且不再掩盖真实日志。`cachedTridentRoot` 由 static 改为
  实例字段(资源重载后随渲染器重建)。
- **实测验证(2026-08-08 游戏实跑)**:清理前单次运行日志 **289,921 行**(其中
  "Armor outline rendered" 刷屏 **289,687 次**,占 99.9%+);清理后日志 **212 行**、
  刷屏归零,描边渲染功能正常(无 ShapeDiag / WHITE fallback / ImageIO 异常)。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`,
  `src/main/java/com/enchantedoutlines/mod/mixin/HumanoidArmorLayerMixin.java`,
  `src/main/java/com/enchantedoutlines/mod/mixin/ThrownTridentRendererMixin.java`

### 修复: 光影兼容下盔甲/鞘翅描边仍为实心立方体(palette+tRNS PNG 透明丢失)

- 现象: 开启光影 + 关闭混色时,穿戴的盔甲、手持鞘翅描边为实心立方体;剑等
  扁平物品也有异常。斧头正常(走 atlas 精灵路径,alpha 正确)。
- 根因(两个叠加的 bug):
  1. **`NativeImage.read(InputStream)` 丢失 palette(索引色)+ tRNS 透明**:
     原版盔甲 `diamond_layer_1.png` 是 palette+tRNS(69% 透明像素),但
     `NativeImage.read` 解码后全像素 alpha=255 → 形状纹理 100% 实心 → 描边
     变实心立方体。斧头走 `spriteOriginalImage`(atlas 精灵原图,Mojang 已
     正确解码 alpha)→ 正常,这解释了"只有斧头正常"。
  2. **`averageLuma` 忘记归一化**:Rec.601 加权平均后未 ÷255,导致
     `exposureScale` 输出 `scale≈55~113`(应为 0.35~1.0),RGB 乘巨大值后溢出
     32 位 int → 描边颜色完全错乱。
- 修复:
  - `shapeTextureForLocation` 改用 **JDK `ImageIO`** 读取 PNG(正确展开
    palette 的 tRNS 为 alpha),逐像素 `getRGB` 转 ABGR 写入 NativeImage;
  - `averageLuma` 归一化到 0..1(`÷255`);
  - 形状纹理生成链全部异常安全(亮度读取失败只不压暗,绝不中断/回退纯白矩形)。
- 清理: 移除排查用的临时测试钩子(`EnchantedOutlinesClient`)与 ShapeDiag 诊断日志。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`,
  `src/main/java/com/enchantedoutlines/mod/EnchantedOutlinesClient.java`,
  `src/main/java/com/enchantedoutlines/mod/mixin/ItemRendererMixin.java`
- 新增项目规则: 仓库根目录 `AGENTS.md`,记录本次事故教训(ImageIO 读取、
  亮度归一化、统一轮廓算法不可拆分、临时诊断代码必须清理等)。

---


### 修复: 关闭混色(非混合模式)时描边可能退化为实心矩形(正方形)

- 现象: 关混色(`itemPixelColorGlint` / `armorPixelColorGlint` = false)时,
  扁平物品(剑/工具)与盔甲的描边丢失物品轮廓,变成实心矩形/立方体。
- 根因: v0.1.3 在形状纹理生成链(`shapeTexture`)中新增了<b>平均亮度</b>计算
  (`averageLuma` → `NativeImage.getPixelRGBA`)用于压暗曝光。`getPixelRGBA`
  在贴图格式非 RGBA(如 RGB 无 alpha 的模组盔甲材质)时抛异常:
  - 盔甲路径 `shapeTextureForLocation` 捕获异常 → 回退纯白纹理(实心立方体);
  - 扁平物品路径 `shapeTextureFor` 无捕获 → 异常向上传播,描边渲染中断;
  - `ModelGeometry` 构造里的 `mainSpriteLuma` 同样可能抛,导致几何缓存构建失败。
- 修复(全部异常安全,任何亮度读取失败<b>绝不</b>中断或改变形状纹理生成):
  - `lumaOf` / `mainSpriteLuma` 对 `averageLuma` 包 try-catch,失败 → 不压暗(-1);
  - `shapeTexture` 像素循环包 try-catch,格式不支持时回退纯白矩形(与 v0.1.2 一致);
  - `spriteOriginalImage` 反射改为<b>沿父类链查找</b> + 按类缓存 —— SpriteContents
    子类不直接声明 `originalImage` 字段,原 `getDeclaredField` 会失败并回退纯白矩形;
    现在子类也能读到贴图 alpha,减少"正方形"回退。
- 形状算法本身(扁平=形状纹理、3D=几何外扩、盔甲=逐 cube 放大壳)保持 v0.1.2 的
  统一设计未变。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

---

### 优化: 渲染热路径去除反射与重复遍历

- `needVanillaShaderFallback()`(每帧每格/每槽位调用)改为<b>按 500ms 时间间隔缓存</b>
  光影检测结果:每次渲染不再做两次反射 invoke,Iris 光影包切换最多延迟半秒生效。
- `spriteOriginalImage` 反射的 `originalImage` 字段改为<b>静态缓存</b>,避免每次
  `getDeclaredField` 类遍历。
- 关闭混色时,同一贴图的不同描边色会各生成一张形状纹理——平均亮度按源贴图
  location 缓存(`lumaCache`),避免对同一张图反复遍历像素;资源重载时一并清空。
- `usesOnlyStoneSprite`(盾牌/三叉戟盒模型判断)结果缓存进模型几何缓存,
  不再每帧遍历 quads。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 修复: 配置界面修改后解析缓存不失效,改动不生效

- 根因: `Config` 的解析缓存(默认色/逐附魔色/逐物品色/禁用列表)只在
  `ModConfigEvent`(重启/F3+T 重载)时通过 `invalidateCache()` 失效;配置界面的
  `markChanged()` 只调用了 `Config.save()` 写盘,<b>未触发缓存失效</b> → 界面里改的
  颜色/开关不生效,直到重启或资源重载。
- 修复: `markChanged()` 在保存后同步调用 `Config.invalidateCache()`。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`

---


### 优化: 渲染几何按 BakedModel 缓存,消除每帧重复计算

- 根因: `collectQuads`(含 `RandomSource.create()` + 7 个方向遍历)与顶点法线平均外扩
  (`renderVertexNormalExpand` 的 HashMap 累积 + 归一化)在**每次渲染**都重复执行:
  GUI 中同一物品 64 格 → 每帧 64 次全模型遍历 + 64 次法线哈希;手持/3D 物品每帧同样重复。
- 修复: 新增按 `BakedModel` 实例的几何缓存(`WeakHashMap`):
  - `collectQuads` 结果(quads)只算一次,同物品多格/多帧共享;
  - 顶点法线平均外扩的**预处理**(顶点坐标 + 归一化平均法线方向 + 每 quad 面法线)
    只算一次,帧内只做矩阵变换与顶点写入,不再分配 HashMap / 顶点对象;
  - 按 sprite 分组、主 sprite 平均亮度一并缓存。
  - 资源重载(F3+T)后模型是全新实例,弱引用自动回收,无需手动清理。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 修复: 关闭混色(纯色描边)时描边曝光严重

- 根因: 混色开启时描边 = 描边色 × 物品贴图像素色,暗色贴图天然压暗亮度;
  关闭混色后描边为**纯色**,在光影兼容的 emissive 全亮渲染下,亮色描边
  (粉/金/白)在明亮场景中严重过曝刺眼——纯色路径缺少"物体颜色调亮"这层调制。
- 修复: 纯色描边按物品贴图的**平均感知亮度**(Rec.601 × alpha 加权,一次计算并缓存)
  压暗 RGB(色相不变):映射 `0.35 + 0.65 × luma`,暗色物品(铁剑等)描边显著变暗、
  亮色物品(金苹果等)基本不变——模拟"混合算法根据物体颜色降低曝光亮度"的效果。
  覆盖三条纯色路径:扁平物品/盔甲"描边色形状纹理"烘焙、3D 物品纯白 × vertexColor。
- 新增配置项 `outlineExposureReduce`(默认 true,COMMON 配置文件,
  键名 `outlineExposureReduce`):true = 按物体颜色压暗曝光;false = 保持原始亮度。
  配置界面「通用」分类新增该开关,可即时保存。
- 影响文件: `OutlineRenderer.java`、`Config.java`、`EnchantedConfigScreen.java`、
  `assets/enchanted_outlines/lang/zh_cn.json`、`assets/enchanted_outlines/lang/en_us.json`

