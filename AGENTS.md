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

### 6.5 模组盔甲/物品描边必须"跟随本体"渲染逻辑(违反即缺角/实心/无描边)
- **事故(2026-08-09, v0.1.6)**:永恒星光热泉石盔甲描边缺角+实心、月弧长枪无描边。
- **盔甲**:本体在 `HumanoidArmorLayer.renderArmorPiece` 方法体内(HEAD 之后)通过
  NeoForge hook 替换模型/纹理(`ClientHooks.getArmorModel` / `ClientHooks.getArmorTexture`)。
  描边必须调用**同一 hook** 获取与本体一致的模型与纹理,禁止用 HEAD 参数的原版模型 +
  `ArmorMaterial.Layer.texture()` 标准路径(自定义模型/纹理的模组盔甲必然错)。
  hook 默认实现即原逻辑,原版盔甲行为不变;返回非 HumanoidModel 时回退原模型。
- **BEWLR 物品**(`model.isCustomRenderer()`):GUI/GROUND/FIXED 下本体由模组/原版在
  `ItemRenderer.render` 内替换为平面 inventory 模型渲染,描边用
  `OutlineRenderer.inventoryModelFor(stack)`(按 `<itemId>#inventory`、
  `<itemId>_inventory#inventory` 约定探测,过滤 missing 与 builtin/entity 占位)取同一
  模型;找不到则跳过。手持 BEWLR 物品本体是实体模型无平面几何,一律跳过。
- **BEWLR 手持物品 3D 描边**(v0.1.6+):本体手持由 BEWLR 渲染自定义实体模型,
  `renderBewlrEntityOutline` 反射其模型做放大壳——标准 Mojang Model(root 是
  ModelPart,永恒星光)→ 逐 cube 放大壳;LionfishAPI 骨骼(root 是 AdvancedModelBox,
  灾变)→ 默认<b>逐 cube 顶点法线外扩</b>(v0.1.7+),整体包围盒放大壳降级为
  回退路径(见下方铁律)。模型字段按物品路径大写 + `_MODEL` 从适配类静态字段
  读取(`ESItemStackRenderer`/`CMItemstackRenderer`),缺失时触发一次 BEWLR 渲染初始化。
  新模组 BEWLR 适配 = 把持有模型静态字段的类加入 `BEWLR_MODEL_HOLDER_CLASSES`。
- **BEWLR 内部预变换铁律**(违反即描边错位/镜像):模组 BEWLR 渲染器在 display
  transform 之后、renderToBuffer 之前会对模型套一层内部预变换(实测:永恒星光
  `scale(1,-1,-1)`;灾变 `translate(0.5,0.5,0.5)+scale(1,-1,-1)`)。描边必须在<b>同一
  预变换之后</b>做放大壳 —— `BewlrModel.preTransform`(ES_FLIP/CM_CENTER_FLIP)+
  `applyBewlrPreTransform` 复刻。新增模组 BEWLR 适配时,必须先反编译其
  renderByItem 确认内部 translate/scale,再登记预变换类别。
- **LionfishAPI 逐 cube 顶点法线外扩铁律**(v0.1.7+,违反即描边错位/裂缝/不贴合):
  灾变 Lionfish 武器 3D 描边默认走 `renderLionfishPerCube`(逐 cube 顶点法线外扩):
  - <b>变换链必须逐部件复刻本体</b> `BasicModelPart.render`:pushPose →
    translate(rotationPointX/Y/Z ÷16) → mulPose(rotationZYX(z,y,x)) →
    scale(xScale,yScale,zScale) → [cubes] → [children 递归] → popPose。
    rotationPoint 与 cube 坐标都是<b>像素单位,渲染时 ÷16</b>;rotateAngle 帧内
    实时读,动画部件才能与本体同步。禁止用整体 AABB 一次放大代替。
  - <b>顶点法线必须按位置聚合</b>:cube 24 个 quad 顶点按位置(float 精确相等)
    归并成 8 个角点,累加相邻 3 面法线归一化 —— 共享顶点在所有面上外扩方向一致,
    否则面间裂缝。UV 直接复用 `PositionTextureVertex.textureU/V`(构造时已算好的
    归一化 UV,与本体完全一致),禁止自己重算 UV。
  - <b>静态几何可缓存</b>:位置/UV/聚合法线与帧无关,按 cube 存
    WeakHashMap(`ExpandedLionfishCube`),每帧只做矩阵变换与顶点写入,零反射;
    部件变换必须仍由 PoseStack 每帧提供,不得入缓存。
- **LionfishAPI 整体壳 AABB 铁律**(v0.1.6 旧算法,仅 `bewlr3dPerCube=false` 回退用;
  违反即描边壳偏出本体):Lionfish cube 坐标是
  <b>像素单位</b>(渲染时内部 ÷16),AABB 中心必须 ÷16 转模型单位;且 cube 是相对所属
  部件的局部坐标,必须沿父链累加每级部件 `rotationPointX/Y/Z`(公开字段,像素)
  得全局 AABB,否则多部件武器整体中心算错。`lionfishBounds(part, ox, oy, oz)`
  递归累加,`renderLionfishInflated` 中心 ÷16。
- **BEWLR 描边颜色混合**:BEWLR 3D 描边应采样<b>本体纹理</b>(颜色=贴图×描边色)而非
  纯白 —— `BewlrModel.texture` 反射读取持有类 `<ID>_TEXTURE` 或模型类 `TEXTURE`
  静态字段(灾变 THE_IMMOLATOR_TEXTURE 等、永恒星光 CrescentSpearModel.TEXTURE),
  拿不到回退 WHITE_TEXTURE。
- **BEWLR 3D 描边配置化**:3D 描边几何放大系数 —— 读 `Config.BEWLR_3D_SCALE`
  (默认 0.3)而非硬编码;`Config.BEWLR_3D_PER_CUBE`
  (默认 true)控制灾变 Lionfish 用逐 cube 顶点法线外扩(等厚贴身)还是整体放大壳
  (端部膨胀)。改动缩放需同时看混色/曝光配置。
- **GUI/GROUND/FIXED 必须优先平面描边铁律**(v0.1.7,违反即背包/掉落物轮廓错位):
  有平面 inventory 变体的 BEWLR 物品(长枪),本体在 GUI/GROUND/FIXED 下就是平面
  模型 —— 描边必须无条件优先平面(`inventoryModelFor`),<b>禁止</b>因为任何配置
  (曾有过 `bewlr3dPrefer`,已废弃移除)改成 3D 放大壳,否则 3D 壳套平面本体错位。
  3D 放大壳只用于手持(本体 3D)与无平面变体的物品(灾变,GUI 本体就是 BEWLR 3D)。
- **Lionfish 逐 cube 失败回退铁律**(v0.1.7+):`renderLionfishPerCube` 反射失败或
  无 quads 时必须返回 false,由 `renderBewlrEntityOutline` 回退
  `renderLionfishInflated` 整体壳 —— 保证任意 LionfishAPI 版本都有描边,不静默
  无描边。`renderLionfishPart` 返回实际渲染 cube 数(drawn),drawn=0 即失败。
- **盔甲描边均匀外扩铁律**(v0.1.7+):盔甲"逐 cube 放大壳"外扩量 = (scale-1)×cube
  半对角线,模组细分盔甲(热泉石套装)cube 小 → 描边明显偏薄。修复:仅盔甲路径
  (传 `uniform=true`)按 cube 尺寸自适应 perCubeScale = 1+(scale-1)×refDiag/自身
  半对角线(refDiag=0.468 = 原版胸甲参考)→ 原版盔甲视觉不变、模组细分盔甲补足
  到相同厚度;鞘翅/投掷物/BEWLR 长枪共用 `renderPartInflated` 但传 `false` 保持
  原视觉。开关 `Config.ARMOR_UNIFORM_EXPAND`(默认 true)。
- **鞘翅描边与盔甲同一系数铁律**(v0.1.7+):鞘翅描边<b>必须复用盔甲</b>的
  `ARMOR_INFLATE_PER_THICKNESS`(0.04)+ `ARMOR_REF_DIAG`(0.468)+ 均匀外扩
  (uniform=true),使同一 armorThickness 下鞘翅与盔甲外扩像素完全一致。
  事故(v0.1.7):鞘翅曾用独立系数 0.09 + refDiag 0.701 → 外扩 ≈ thickness×0.504,
  是盔甲(≈thickness×0.15)的 3 倍多,实测"盔甲 8 清晰、鞘翅 8 很宽"。
  ⚠️ 不要为鞘翅单独调大系数/参考对角线;投掷物/长枪仍走固定放大壳(false)。
- **配置界面语言键必须同步**:`EnchantedConfigScreen` 新增任何行(addBooleanRow/
  addDoubleRow/... 的 key)都必须同步补 `zh_cn.json`/`en_us.json` 的
  `enchanted_outlines.config.<key>` 与 `<key>.tooltip`,否则界面显示原始键名。
- 原则:本体怎么渲染,描边就怎么渲染 —— 一切自定义渲染(模型替换/纹理替换)必须
  走与本体相同的 hook/解析路径,不得硬编码原版假设。

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
