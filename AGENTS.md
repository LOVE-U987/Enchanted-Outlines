# Enchanted Outlines 项目规则 (AGENTS.md)

> 项目专属开发规则,优先级高于个人全局规则。所有 AI 助手在改动本仓库前必须阅读本文件。
>
> **个人全局规则**:必须同时遵守 `C:\Users\97128\Documents\GitHub\AGENTS.md`
> (含交互偏好、开发约定、自动沉淀规则等;本项目规则与之冲突时,以本文件为准)。

## 渲染架构铁律(违反即产生"正方形/立方体"描边 bug)

### 1. 形状纹理的 PNG 读取必须用 ImageIO,禁止 NativeImage.read
- **事故(2026-08-08, v0.1.3)**:`shapeTextureForLocation` 用 `NativeImage.read(InputStream)`
  读取盔甲/鞘翅贴图 → **palette(索引色)+ tRNS 透明** 的 PNG(原版盔甲/鞘翅/多数物品贴图都是)
  透明信息被丢失,解码后全像素 alpha=255 → 形状纹理 100% 实心 → 描边变成实心立方体。
- **规则**:读取独立纹理 PNG 时,一律用
  `javax.imageio.ImageIO.read(in)` 得到 `BufferedImage`,再逐像素 `getRGB(x,y)` 转 ABGR
  写入 `NativeImage.setPixelRGBA`。`NativeImage.read` 只可用于已确认 RGBA 格式的图片。
- 参考实现:`OutlineRenderer.shapeTextureForLocation`。

### 1.5 CPU 端读贴图必须 mcmeta 感知;序列图只取首帧
- **事故(2026-09-25, v0.2.1)**:Hazen 序列图武器(volcano 64×320=5 帧等,`.png.mcmeta`
  逐帧动画)描边渲染成<b>整张 sheet 全帧并集巨块</b>。geo 模型 UV 空间只有单帧,本体
  GPU 采样逐帧轮换的活纹理;描边 CPU 端 ImageIO 读原始 PNG 文件 = 整张 sheet,GPU 路径
  绑定的注册纹理同样是整张 sheet(AzureLib 自管逐帧)→ 三种采样模式全中招。
- **规则**:
  - 任何 CPU 端读独立纹理 PNG(形状烘焙/透明检测)一律经 `readTextureFrame`:
    ImageIO(铁律 1)+ `cropAnimationFrame0`(解析 `.png.mcmeta` animation,取首帧);
  - GPU 采样模式(emissive/自定义 shader)对 mcmeta 动画纹理经 `animatedSampleTexture`
    <b>按注册纹理活性分流</b>(2026-09-26 修订):注册纹理是 AzureLib AnimatableTexture
    (管线每帧 setAndUpdate 推 GPU 帧)→ <b>绑原 location</b>,描边采样当前帧、与本体
    动画<b>同步</b>(一律烘帧 0 会让描边静止脱节,用户实测;判定时的 getTexture 还会
    触发 AzureLib mixin 建立动画纹理,治愈本体注册链);普通 SimpleTexture(整张
    sheet、无 mixin 推帧)→ 绑首帧烘焙动态纹理(缓存登记 invalidateCaches);
    非序列图零开销;
  - atlas 精灵版(`shapeTextureFor`)**帧感知(2026-10-25)**:`SpriteContentsTickerMixin`
    在原版 `Ticker.tickAndUpload` 的帧上传点记录当前帧格(spriteFrameTracker),
    形状缓存键含帧号、帧切换重烘 → 描边随本体序列纹理动画;miss = 帧 0(未
    tick 过/单帧纹理,与旧行为一致);
  - 首帧烘焙是静态近似:仅用于"无 mixin 推帧"的死注册;活动画路径描边实时同步
    (且 `animatedSampleTexture` 的死注册缓存带重校验:注册纹理升级为
    AnimatableTexture 后自动改绑原 location);
    独立纹理路径(readTextureFrame,盔甲/鞘翅/投掷物)恒为帧 0 静态,接受;
  - **Sodium 动画纹理兼容(2026-10-25)**:Sodium 0.8 `animate_only_visible_textures`
    对附魔物品渲染路径的可见性标记失效 → atlas 精灵动画冻结(根因实测确认,
    知识库 2026-10-25 条目)。`SodiumCompat` 在三个物品渲染 mixin HEAD 对附魔
    物品精灵调用 Sodium 公开 API `SpriteUtil.markSpriteActive`(反射,无硬依赖),
    配置 `sodiumAnimatedTextureCompat`(默认 true),**独立于描边总开关**。

### 2. 亮度计算必须归一化到 0..1
- **事故(同上)**:`averageLuma` 的 Rec.601 加权平均后忘了 `÷255`,`exposureScale` 算出
  `scale≈113.9` → RGB 乘巨大值溢出 32 位 int → 颜色完全错乱。
- **规则**:任何像素亮度/颜色统计,输出必须归一化到 **0..1**(除以 255);返回 -1 表示失败。

### 3. 形状算法是"统一轮廓"设计,不得拆分
- 扁平物品 = 形状纹理(alpha 遮罩);3D 物品 = 几何外扩;盔甲/鞘翅/投掷物 = 逐 cube 放大壳。
- 这三条路径的**形状来源与几何**保持 v0.1.2 设计,任何优化(缓存/预处理)不得改变其语义。

### 3.5 扁平物品描边必须用形状纹理(精灵相对 UV),禁止用模型 UV 采样 BLOCK_SHEET
- **事故(2026-08-16)**:扁平描边旧实现 `emitQuad` 直接把模型 quad 的 UV 交给着色器采样
  BLOCK_SHEET。原版扁平物品(剑/工具)烘焙后的 quad UV 是精灵在 atlas 的<b>绝对 UV</b>
  (模型生成器写入),采样正确 → 剑形轮廓;但<b>模组扁平模型</b>(BEWLR 平面变体/手写模型)
  的 UV 常是 [0,1] 全图,采样越出精灵区域 → 整块 quad 全不透明 → <b>描边变成平面正方形</b>
  (不遵循轮廓)。
- **规则**:扁平物品(GUI/手持)描边一律走 `renderFlatOutlineShape` —— CPU 读贴图 alpha
  生成形状纹理(`shapeTextureFor`)+ 精灵相对 UV(`emitQuadShape`)渲染,形状由贴图 alpha
  决定、与模型 UV 无关;颜色恒为纯描边色(不混色),换取任意模组扁平物品轮廓完整。
  3D 物品(方块/盾牌/三叉戟)仍走几何外扩(`renderVertexNormalExpand`/`renderGui3dInflate`),
  不受影响。
- 参考实现:`OutlineRenderer.renderFlatOutlineShape` / `guiOutlineRenderType` / `emitQuadShape`。
- ⚠️ 删除"模型 UV 采样"路径的兜底:若形状纹理 alpha 读取失败(见铁律 1/5)会回退纯白矩形
  → 又是正方形;因此修扁平描边前先确认 ImageIO 与 `spriteOriginalImage` 父类链查找未被破坏。

### 3.6 Geo 骨骼描边统一采样本体贴图轮廓;贴图透明部件无描边
- **事故与方案演进(2026-08-16)**:Geo 物品(武器/法杖)与盔甲(GeckoLib/AzureLib 骨骼)
  的描边形状经历了多次试错:
  <ol>
    <li>纯几何(`geoOutlineRenderType`):扁平 cube(某维度 0 的 2D 贴片)外扩成<b>平面方形</b>;</li>
    <li>采样贴图:模组贴图<b>大部分透明</b>(柠檬神贴图 61372 透明 vs 4164 不透明)
      → 扁平部件(光环)无描边;</li>
    <li>混合双渲染(几何层 + 贴图层叠加):几何层把 cube 填成实心方形,<b>破坏轮廓</b>;</li>
    <li>几何边框线(只描边主平面 4 条边):显示模型几何轮廓,<b>不贴合纹理</b>;</li>
    <li><b>最终:纯采样贴图</b>(`armorOutlineRenderType(texture)`)——所有 cube 采样本体
      贴图 alpha 做形状:贴图有内容的部件描边(剑形/符号/盔甲主体),贴图全透明的部件
      (如柠檬神光环 spin,贴图区域 0/1024 像素不透明)无描边。</li>
  </ol>
- **规则**:Geo 物品与盔甲统一 `armorOutlineRenderType(texture, color)` 采样贴图轮廓,
  不再用纯几何/边框线/混合双渲染。贴图透明区域无描边是贴图本身的限制,接受。
- 参考实现:`OutlineRenderer.renderGeoModelOutline` / `renderAzureLibArmorOutline` /
  `renderGeckoLibArmorOutline` 全部用 `armorOutlineRenderType`。

## 性能优化纪律

### 4. 几何缓存不得改变渲染结果
- `ModelGeometry`(WeakHashMap)缓存 quads/法线外扩预处理/亮度是安全的,但:
  - **只缓存与帧无关的输入**(顶点坐标/UV/法线),帧内仍做矩阵变换;
  - 优化后必须在开启/关闭光影两种模式下验证形状与颜色不变。

### 4.5 扁平描边 4 对角环 + 外扩 ε 推挤(2026-10-02 统合自 Enchantment Glint Outline)
- **来源**: 赛道头部模组 Enchantment Glint Outline(CC0,106 万下载)反编译源码
  (`thickenQuad`/`growVert`);完整行业情报见知识库「项目经验」《[2026-10-02] 行业情报》。
- **4 对角环**:`DIAG_OFFSETS`(4 对角)替代旧 8 方向 —— 对角拷贝(位移 ±t)任一轴
  范围与基向拷贝一致(t ≤ 面片半径时基向 4 拷贝纯冗余)→ 环范围不变、绘制量减半、
  边中点双重 alpha 叠加消失。极端 t > 面片半径有细缝,接受(非错位)。
- **ε 推挤**:`NORMAL_EPSILON`(1e-4 模型单位)—— 外扩顶点额外沿法线推一点,描边面
  与本体表面永不共面(防 z-fighting 边界情况)。已应用于 `renderVertexNormalExpand` +
  捕获发射器;Lionfish/Geo/armor 路径可后续按需加。
- **教训(方法论)**:下载 JAR 反编译(Modrinth CDN 可达,绕墙)是读同行源码的可靠
  路径;CC0 许可证学习借鉴无法律风险。

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
  新模组 BEWLR 适配 = 把持有模型静态字段的类加入 `BewlrModelResolver.BEWLR_MODEL_HOLDER_CLASSES`
  (2026-09-25 拆分后该表已移至 `outline/BewlrModelResolver`,见"BEWLR 子模块结构")。
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
  (默认 0.1)而非硬编码;`Config.BEWLR_3D_PER_CUBE`
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
- **手持物品描边注入点铁律(v0.1.7+,违反即描边不跟随 Better Combat/PlayerAnimator
  动画)**:玩家手持(第一/第三人称)描边<b>必须</b>在 `ItemInHandRenderer.renderItem`
  (7 参)HEAD 渲染(`ItemInHandRendererMixin`),<b>禁止</b>移回 `ItemRenderer.render`
  (8 参)HEAD —— 攻击动画变换由 PlayerAnimator 在 `renderItem` 调用<b>之前</b>应用到
  PoseStack(ItemInHandLayer 手部变换之后),8 参 render 的 HEAD 拿不到动画变换,
  描边会留在原版静态位置。`ItemInHandRendererMixin` 用 ThreadLocal 标记
  `IN_ITEM_IN_HAND_RENDERER`(HEAD 设 true / RETURN 恢复),`ItemRendererMixin` 玩家
  手持分支检查标记跳过(避免"静态描边 + 动画描边"重复);GROUND/FIXED 不经
  ItemInHandRenderer,仍由 `ItemRendererMixin` 处理。模型解析 seed 必须与本体
  `renderStatic` 一致(`entity.getId() + context.ordinal()`)。
- **逐物品覆盖决策点(v0.2.0,2026-10-02)**:per-item 厚度(`itemThicknesses` 映射,
  程序化 `registerItemThickness` > 配置 > 全局)与 per-item 强制纯色(`solidColorItems`
  列表 + `registerItemSolid`,仅光影模式混色决策生效)。深层混色决策(flat mix /
  armor 光影纹理选择 / 3D fallback 纯白判定)拿不到 stack → 经
  `CURRENT_OUTLINED_ITEM` ThreadLocal 传递(7 个 mixin 描边入口 set / finally clear,
  与 IN_GUI_RENDER 同款模式)—— 新增混色决策点必须用
  `ColorResolver.isSolidOutline(currentOutlinedItem())` 接线;新增 mixin 入口必须
  set/clear 该标记。
- **渲染缓存必须随进出世界/资源重载清理(v0.1.7+,违反即多人"退出后重进渲染错误")**:
  新增缓存必须登记到 `OutlineRenderer.invalidateCaches()`(统一清空 shapeTextures /
  lumaCache / RenderType 缓存 / shieldModel / tridentModel / 几何缓存);资源重载
  (`setOutlineShader`)与进出世界(`LevelEvent.Load` / `ClientPlayerNetworkEvent.LoggingOut`,
  挂 `NeoForge.EVENT_BUS` 游戏总线)都会调用它。禁止新增"永不清理"的实例缓存。
- **描边着色器颜色来源(OutlineBlend uniform)**:`outline.fsh` 颜色 =
  `vertexColor.rgb × mix(1, texel.rgb, OutlineBlend)`。扁平"形状纹理"路径
  `renderFlatOutlineShape` 必须走 blend=1(顶点色是白、色烘焙进贴图),否则白色描边;
  盔甲/鞘翅/投掷物自定义 shader 路径(`armorOutlineRenderType`)按
  `Config.ARMOR_PIXEL_COLOR_GLINT` 设 blend(<b>2026-10-02 语义修正,用户决策:
  不开光影时代码级强制纯色(blend 恒 0、扁平 mix 恒 false,无视混色开关),混色仅在
  光影兼容模式依据配置(默认 true 不变)</b>)。uniform 全局、每批 flush 前设置。
- **TACZ 专项(2026-10-18 推倒重做:TaCZ 内部注入走查,`Config.ENABLE_TACZ_COMPAT`
  默认 true)**:TACZ 枪械是自绘 Bedrock BEWLR(`GunItemRendererWrapper`,
  jar-in-jar simplebedrockmodel)。旧"通用捕获"对 TaCZ 无用的两大根因(反编译
  实证,2026-10-18):
  <ol>
    <li><b>第一人称被旁路</b>:simplebedrockmodel IFP 系统在
        `FirstPersonRenderHandler.onRenderHand` 直接取消原版 `RenderHandEvent`
        后自调 `renderFirstPerson` —— 原版 `ItemInHandRenderer.renderItem`
        (旧注入点)在第一人称<b>根本不执行</b>;且 TaCZ 以 GameRenderer mixin 改
        bob/FOV(手持 FOV 与世界 FOV 分离),渲染 pass 外猜 pose 必错位。</li>
    <li><b>第三人称几何被代码级修改</b>:LOD 按距离换模 / 附件动态挂载 / 改枪
        (Refit)实时变换 —— 资源与实际渲染不同步;v2 identity pose 调
        `renderByItem` → LOD 距离判定失真 → 捕获模型与本体不一致。</li>
  </ol>
  <b>新算法</b>:<b>注入 TaCZ 内部</b>,`TaczGunModelMixin`(新配置
  `enchanted_outlines.tacz.mixins.json` + `TaczMixinPlugin` 条件应用,TACZ 缺席
  时不应用任何 mixin)打在 `BedrockGunModel.render` <b>六参重载 HEAD</b> ——
  第一人称(`renderFirstPersonInner`)与第三人称/GROUND/FIXED(`renderByItem`)
  两条渲染链的唯一汇聚点,此刻 PoseStack 即本体最终变换(视角摆动/开镜/后座/
  动画/LOD 选模全部就位;方法体内挂载块 push/pop 平衡)。⚠️ <b>必须 HEAD 不得
  TAIL(实机反馈修复,2026-10-18)</b>:描边统一绘制顺序 = <b>描边先画(壳不写
  深度)→ 本体后画盖住壳中心 → 只露外扩环</b>;TAIL 注入顺序倒置 → 无光影描边
  糊在枪身上、光影下与本体深度竞争(z-fight 闪烁/穿出)。
  `TaczGunOutline` 在该 pose 下遍历
  `getShouldRender()`:逐部件<b>直调公开方法</b> `translateAndRotateAndScale`
  (零复刻误差),逐 cube 绕包围盒中心放大壳后调公开方法 `cube.compile` 让
  TaCZ 顶点代码向描边 buffer 发射(顶点写法与本体 100% 同源);RenderType 走
  `armorOutlineRenderType`(贴图 alpha 形状,铁律 3.6)。对 Accelerated
  Rendering 加速渲染环境天然兼容(只读模型数据)。<b>功能件节点必须跳过(实机
  反馈修复,2026-10-18)</b>:手臂(lefthand_pos/righthand_pos)/枪口火焰/护木
  变体是模型树内的 `FunctionalBedrockPart` 功能渲染器节点 —— 本体<b>从不</b>
  编译其 cubes/children(只渲染功能件输出),走查必须镜像该语义跳过这些节点,
  否则描边本体不可见的隐藏几何(枪模包预置的手臂方块曾被描出)。
  <b>派发分工(2026-10-18)</b>:世界 context(第一/第三人称/GROUND/FIXED)由走查
  mixin 全权接管,`renderBewlrEntityOutline` 对 TaCZ 非 GUI context 返回 false
  (避免双描边);<b>GUI 仍走 v2.1 平坦捕获</b>(SlotModel 单面 quad 本就正常,
  用户决策保留)。已知限制:附件(瞄具/弹匣)与功能件输出(原版手臂等)第一版
  无描边(二期);LOD 远距描边贴图取高模(形状遮罩近似);光影包 GPU 侧手部
  动画(如 EuphoriaPatches breathing hand)只作用于本体着色程序时,描边可能存在
  与待机浮动同幅度的小偏移(本体盖中心语义下不产生 z-fight)。
  <b>编译期依赖</b>:TaCZ 发行
  jar 放 `compat-libs/`(compileOnly,不入库不分发,见 compat-libs/README.md),
  缺 jar 时 TaCZ 专项源码无法编译属预期。Hazen 用 `Config.ENABLE_HAZEN_COMPAT`。
  捕获路径仍统一有 `CAPTURE_MAX_SPAN`(64 模型单位)尺寸守卫 + 手持 context
  中心距离守卫(`CAPTURE_MAX_CENTER_DIST`=16,见铁律 6.8),捕获到异常包围盒
  一律跳过并走 `logCaptureReject` 节流日志留痕(宁可无描边,不画错位巨壳);
  走查路径读模型树结构,无"垃圾几何"来源,不适用捕获守卫。姿态错位可经对应
  开关关闭回到"无描边"。
- 捕获描边本身已在 `renderBewlrCapturedOutline` 用<b>顶点法线平均外扩</b>(与
  `prepareExpand/renderVertexNormalExpand` 同构):共享顶点沿相邻面法线均匀外扩、
  形状贴合、端部不鼓,替代旧 AABB 中心放大壳(端部膨胀/易错位)。
- 原则:本体怎么渲染,描边就怎么渲染 —— 一切自定义渲染(模型替换/纹理替换)必须
  走与本体相同的 hook/解析路径,不得硬编码原版假设。

### 6.7 描边批次必须在所有退出路径闭合(违反即"头顶/远处巨大模型")
- **事故(2026-09-25,v0.2.0)**:描边路径 `getBuffer` 写入顶点后,中途异常被顶层
  `catch (Throwable ignored)` 吞掉且无 `endBatch`,半写脏批滞留共享 builder
  (`outlineBuffers` = `MultiBufferSource.immediate`,vanilla 语义:getBuffer 到
  <b>不同</b> RenderType 时滞留批被<b>当场以当时的 GL 状态/坐标系画出</b>,同
  RenderType 则续写合并)→ 手持局部空间顶点在世界渲染阶段上屏,表现为"巨大模型
  出现在玩家头顶/天上"。确凿现场:`renderLionfishInflated` 反射调用模组自己的
  render,异常被吞无 flush;三个 mixin 顶层 catch 是把一切中途异常变成脏批的总闸门。
- **规则**:
  - 所有 mixin 顶层描边入口统一 `try { 描边 } catch (Throwable ignored) {}
    finally { OutlineRenderer.flushOutlineBatch(); }` —— finally 强制 flush,
    空批 no-op(正常路径已 endBatch,再 flush 零视觉影响零开销);
  - 新增描边渲染路径:`getBuffer` 之后的所有出口(提前 return/catch)必须保证该批
    被 endBatch;易失败操作(反射查找/可访问化)尽量放到 getBuffer <b>之前</b>;
    必须在 getBuffer 之后反射调用模组代码的,失败分支就地 endBatch(参考
    `renderLionfishInflated` / `renderTaczBedrockModel`);
  - "取了 buffer 没写顶点就退出"是空批,vanilla 不绘制,无害;只有"写了顶点未
    endBatch"才是泄漏。

### 6.8 捕获守卫必须校验"位置"而非只校验"大小";拒绝必须留痕
- **事故(2026-09-25,v0.2.0)**:`CAPTURE_MAX_SPAN` 只查 AABB 跨度。某些自绘 BEWLR
  在第一/第三人称把顶点整体抬到相机/世界空间 —— <b>形状不大(跨度<64)但中心离
  视角原点很远</b>,守卫放行 → 描边壳画在远处 = "巨大模型出现在玩家头顶"。
- **规则**:`renderBewlrCapturedOutline` 必须同时执行两道守卫,且每次拒绝走
  `logCaptureReject` 节流诊断日志:
  - <b>跨度守卫</b>(>64 拒绝)—— 拦"巨大";
  - <b>手持 context 中心距离守卫</b>(第一/第三人称 |AABB 中心|>16 拒绝;GUI 是
    像素空间、GROUND/FIXED 可合法离相机数十单位,<b>不适用</b>本守卫)—— 拦"错位";
  - 节流日志(10s/原因#物品#context,info 级)是<b>常驻诊断</b>而非临时调试代码
    (铁律 6 的例外:拒绝是低频事件,info 不刷屏),用户报障直接附 latest.log
    即可定位问题模组与 context。

### 6.9 规范化捕获(v2)铁律(违反即回到"每个模组一套补丁"的老路)
- **落地(2026-09-25,v0.2.0)**:`BewlrCapture.renderCapturedV2` —— 空 PoseStack
  (identity)捕获 renderByItem 输出 → 本地模型空间守卫(全 context 统一)→ 兄弟路径
  像素语义外扩 → 真实 pose 矩阵重放。v1(复制 pose 捕获最终坐标)保留为回退。
- **规则**:
  - <b>v2 捕获必须用 identity PoseStack</b>(空栈),禁止把真实 pose 传进捕获 —— 传了
    就退化回 v1 的"别人家坐标系"问题,守卫全部失效;
  - <b>v2 守卫在本地空间全 context 统一</b>(span>64 或 |中心|>16 拒绝,GUI 也适用),
    禁止再加"仅手持/仅 GUI"的 context 例外 —— 本地空间没有那些例外存在的原因;
  - <b>v2 外扩量必须与兄弟路径像素语义一致</b>:GUI = thickness×THICKNESS_SCALE/16
    (对齐 renderGui3dInflate),世界 context = thickness×THICKNESS_SCALE×
    BEWLR_3D_SCALE(对齐 Lionfish renderPerCube);禁止恢复 ∝maxSpan;
  - <b>重放必须用真实 pose 矩阵 transformPosition</b>(与 renderVertexNormalExpand
    同构),禁止让顶点"自带变换";
  - <b>全局缓冲防护(统一,2026-10-02 审计升级)</b>:<b>所有</b>捕获(capturePlain)
    的调用窗口内恒替换 RenderBuffers.bufferSource → CaptureBufferSource,finally
    还原 —— 直写全局型渲染器(TACZ BedrockModel.render)无论写传入 buffer 还是取
    全局都进捕获器:<b>未替换时它们会把顶点漏进真实渲染管线</b>(identity 捕获泄漏
    本地坐标 → flush 时被解释为相机/GUI 空间 → 视野错位图形;real-pose 捕获重复
    渲染浪费),且统一后 TACZ 由 v2 直接捕获(每帧 1 次渲染调用,原 v2/v1/v1-swap
    三次降为一次)。pose 语义按调用方分流:v1 传真实 pose(捕获 = 最终绘制坐标,
    原样发射零重放);v2 传 identity(本地空间垃圾由守卫拦下,不重试);
    <b>经 Config.BEWLR_GLOBAL_BUFFER_SWAP 门禁(默认开启;关闭 = 降级纯捕获,
    直写全局渲染器拿不到几何 → 无描边)</b>,开启后捕获统计走 swap-stats 节流日志;
  - <b>平坦几何双发射</b>(v2.1):捕获几何单薄一轴(GUI slot 贴片/扁平 2D 武器)→
    面内 8 方向偏移 + 捕获纹理贴图形状(法线外扩对薄面片屏幕上不可见);立体几何 →
    顶点法线外扩 + WHITE 纯几何;<b>贴图提取失败禁止退 WHITE</b>(白矩形环 = 实心
    "方形背景"事故;2026-10-11 起标准路径同源加固:shapeTextureForLocation 读失败
    返回 null,调用方降级 GPU 采样原纹理;readTextureFrame 三种失败节流留痕)→
    回退 v1,最终无描边;
  - <b>混合几何双发射 + 捕获分层(2026-10-11,Hazen 方形/光斑四级根因)</b>:
    捕获按 RenderType 记录请求顺序(rtOrder)与每 quad 来源层(quadSources)——
    附魔物品双绘(VertexMultiConsumer glint+main)的<b>跨层重复 = 叠绘</b>(非基础
    层副本跳过,保留基础层一份),<b>同层重复 = 零厚度贴片正反面</b>(GeckoLib
    8×8×0 型装饰 quad);混合几何按 quadHash(量化 1e-4,XOR 顺序无关)分类:
    有体积 quad → 法线外扩,零厚度贴片 → 面内 8 方向 + 贴图 alpha 形状;采样纹理
    按 getBuffer 顺序取<b>首个非 glint</b> 纹理(<b>glint 层半透明噪声图禁作
    alpha 遮罩源</b>,否则形状遮罩失效 → 紫色光斑);平坦判定辅以 isFlatGeometry
    法线平行度(±同轴,18° 锥角);验证信号 = "混合几何双发射"节流日志
    (solid/zeroThick/overlaySkip/tex 四计数;⚠️ 节流 key 不进日志正文,grep 要
    grep detail 文本而非 key);
  - <b>回退边界</b>:v2 捕获为空(渲染器强依赖真实 pose)→ 回退 v1;v2 守卫拒绝
    (本地空间垃圾)→ <b>不回退 v1</b>(垃圾在 v1 也是垃圾)。

### 7. 配置界面改动必须失效解析缓存
- `Config.markChanged()`(配置界面保存)必须调用 `Config.invalidateCache()`,否则颜色/开关
  改动不生效直到重启。解析缓存只在 `ModConfigEvent` 时自动失效。

### 8. 验证工具优先用 Python;提交前必须过 VERIFY 门禁
- **一键门禁**:`.\verify.bat`(或 `python tools/verify.py`)—— 编译门禁 +
  上文铁律静态扫描(铁律 1/1.5/2/6/6.7/7/8、缓存登记、mixin 注册)+
  zh_cn/en_us 配置键一致性。选项:`--no-compile`(跳过 gradle,秒级)、
  `--only rules`、`--json`、`--list`、`--install-hook`(装 git pre-commit,可选)。
  确凿违规退出码 1(可用于 CI/pre-commit),启发式提示只告警不阻塞。
  **改动渲染代码后、提交前先跑它。**
- **检查本身要自检**:`python tools/verify_selftest.py` —— 向仓库临时注入已知违规,
  断言每条检查真的能抓到,再无条件还原并复查干净仓库 0 错误。**改动
  `tools/verify.py` 的检查逻辑后必须跑一次** —— 一个永远通过的检查等于没有检查。
- 贴图格式/alpha 验证用 `tools/check_png_format.py`(存储形态:索引色+tRNS 高危、
  序列图帧数)、`tools/check_texture_alpha.py`(alpha 组成 → 形状纹理可用性)、
  `tools/check_png_trns.py`(tRNS 专项,`--scan <目录>` 批量审计),基于 Pillow,
  不要手写 PNG 解码或猜。
- 截图分析用 `tools/analyze_screenshot.py`(统计描边色像素密度判断实心/轮廓;
  `--expect outline|solid` 可作回归断言,判定不符退出码 1)。
- ⚠️ VERIFY 只做**静态**验证,**不替代实机跑游戏**(见下方"构建与测试")。
  渲染视觉结论一律以实机为准,静态门禁只保证"不违反已知铁律 + 能编译"。

## BEWLR 子模块结构(2026-09-25 行为保持拆分,v0.2.0)

`outline.OutlineRenderer` 保留渲染管线核心(shader/uniform/RenderType 工厂/描边缓冲
`outlineBuffers`/`invalidateCaches` 统一失效)与 BEWLR 统一派发入口
(`renderBewlrEntityOutline`);BEWLR 家族拆为<b>同包独立类</b>(经构造注入 renderer
引用访问共享设施,共享成员为包私有):

- `BewlrCapture` —— 通用渲染捕获(任意模组零适配):<b>v2 规范化捕获已落地</b>(见
  铁律 6.9,`Config.BEWLR_CAPTURE_V2` 默认开启,渲染器强依赖真实 pose 时自动回退
  v1 旧算法)+ 双守卫(铁律 6.8)+ 节流诊断日志;<b>v2 的迭代只允许改这个类</b>;
  <b>全局缓冲防护已统一并入 capturePlain</b>(2026-10-02:所有捕获窗口内恒替换
  RenderBuffers.bufferSource,直写全局渲染器的顶点泄漏/重复渲染根治,见铁律 6.9);
- `LionfishOutline` —— 灾变(LionfishAPI)逐 cube 顶点法线外扩 / 整体壳回退;
- `TaczOutline` —— TACZ/Hazen 命名空间判定(纯静态)。TACZ 世界 context(第一/
  第三人称/GROUND/FIXED)描边由 **TaCZ 内部注入走查**接管(2026-10-18 重做:
  `mixin/tacz/TaczGunModelMixin` 打 `BedrockGunModel.render` TAIL +
  `outline/TaczGunOutline` 模型树走查,mixin 经 `TaczMixinPlugin` 条件应用);
  GUI 仍走捕获(v2.1 平坦);旧"第一人称跳过"已删除;
  受 ENABLE_TACZ_COMPAT 总开关门禁。namespace 注册表
	  (Config.bewlrNamespaceAdapters,详见②)可作为该命名空间下任一命名的细粒度
	  适配控制,在 enableTaczCompat/hazenCompat 总开关之上进一步限制/重排适配路径;
- `GeoOutline` —— GeckoLib/AzureLib 物品+盔甲(OutlineRenderer 保留 mixin 所需
  公有门面:`isAzureLibArmor`/`isGeckoLibArmor`/`renderAzureLibArmorOutline`/
  `renderGeckoLibArmorOutline`)。<b>骨骼列表/纹理不得缓存</b>(2026-09-26:AzureLib/
  GeckoLib 管线每帧把动画姿态应用到渲染上下文的烘焙模型,缓存解析时刻的骨骼引用
  → 描边与本体动画脱节;`geoItemModel` 只缓存"种类"判定,每帧重新解析);
- `BewlrModelResolver` —— 静态字段模型解析(持有类表/灾变精确映射表/预变换类别,
  纯静态)。

**新模组 BEWLR 适配改哪里**:静态字段模型 → `BewlrModelResolver`;GeckoLib/AzureLib
骨骼 → `GeoOutline` 自动覆盖不用改;自绘模型(通用)→ `BewlrCapture` 零适配;
TaCZ 型(自绘 + IFP 第一人称旁路 + LOD/附件代码级换模)→ `TaczGunOutline` 专项
走查(mixin/tacz/ 注入,TACZ 缺席时不应用)。
拆分是<b>行为保持</b>的:渲染语义/批次闭合/缓存清理纪律逐条对照铁律自查;
mixin 文件与 OutlineRenderer 公有门面签名不得动(mixin 零改动约定)。

## 构建与测试

- **提交前门禁**:`.\verify.bat` —— 编译 + 铁律静态扫描 + 配置/语言键一致性一步到位
  (详见铁律 8;秒级,`--no-compile` 更快)。改了检查逻辑就跑
  `python tools/verify_selftest.py` 自检检查本身。
- 编译检查:`.\gradlew.bat compileJava`(PowerShell 的 exit code 1 是 gradle stderr 误报,
  看 BUILD SUCCESSFUL)。
- 运行:`.\gradlew.bat runClient "-PdevArgs=--quickPlaySingleplayer 新的世界"`。
- 渲染改动必须实际跑游戏验证,不能只编译通过就提交。截图后可量化判定:
  `python tools/analyze_screenshot.py <截图> --color <描边色> --expect outline`
  (实心/轮廓不符即退出码 1);贴图侧疑问先用 `check_png_format.py` 确认
  是否"索引色+tRNS"(会导致 alpha 丢失 → 描边变实心)。
