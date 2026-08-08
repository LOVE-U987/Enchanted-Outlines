# Enchanted Outlines Changelog

---

## v0.1.5 (2026-08-09)

### 修复: 鞘翅描边与盔甲视觉一致(同一配置统一控制)

- 问题: 鞘翅描边此前用独立系数(0.09 + refDiag 0.701),同一 armorThickness
  配置下外扩像素 ≈ thickness×0.504(8 → 约 4px),是盔甲(8 → 约 1.2px)的
  <b>3 倍多</b>——玩家实测"盔甲要 8 才看得清,鞘翅用 8 却很宽"。
- 修复: 鞘翅<b>直接复用盔甲</b>的放大系数 `ARMOR_INFLATE_PER_THICKNESS`(0.04)
  与参考半对角线 `ARMOR_REF_DIAG`(0.468),均匀外扩后两者外扩像素完全一致
  —— 同一个 armorThickness 统一控制盔甲与鞘翅的描边厚度。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 修复: 月弧长枪在背包/掉落物里轮廓错位(废弃 bewlr3dPrefer 配置)

- 问题: 永恒星光月弧长枪附魔后,手持描边正常,但背包(GUI)/掉落物/展示框里轮廓错位。
- 根因: 长枪在 GUI/GROUND/FIXED 下<b>本体是平面 inventory 模型</b>(永恒星光在
  `ItemRenderer.render` 内替换为 `crescent_spear_inventory#standalone` 渲染);
  但配置 `bewlr3dPrefer=true` 会让描边跳过平面、统一走 3D 放大壳
  (`GuiGraphicsMixin`/`ItemRendererMixin` 里 `!preferBewlr3D()` 判断)→ 3D 放大壳
  套在平面本体上,轮廓必然错位。手持本体是 3D 实体模型,3D 描边对得上 → 正常。
  违反 AGENTS.md 铁律"本体怎么渲染,描边就怎么渲染"。
- 修复:
  - `GuiGraphicsMixin`/`ItemRendererMixin`:GUI/GROUND/FIXED 下<b>无条件优先平面
    变体</b>(`inventoryModelFor`),不再被任何配置跳过;无平面变体(灾变武器,GUI
    本体就是 BEWLR 3D)才走 3D 放大壳。
  - <b>废弃并移除</b>配置 `bewlr3dPrefer`(注意(破坏性)):该配置语义是"GUI 也统一
    3D",但对"有平面变体的物品"是设计错误——本体 GUI 是平面,3D 描边永远错位;
    对无平面变体的灾变武器它又无意义(本来 GUI 就 3D)。移除 Config 定义、配置界面
    行、语言键、`preferBewlr3D()` 方法;已有 TOML 里的旧键由 NeoForge 自动清理
    (行为回到正确默认)。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/mixin/GuiGraphicsMixin.java`
  - `src/main/java/com/enchantedoutlines/mod/mixin/ItemRendererMixin.java`
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
  - `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json`
  - `src/main/resources/assets/enchanted_outlines/lang/en_us.json`

### 重构: 灾变(LionfishAPI)武器 3D 描边改为逐 cube 顶点法线外扩

- 问题: 灾变等 LionfishAPI 骨骼模型武器的 3D 描边此前用"整体包围盒放大壳"——
  按整件武器 AABB 中心放大,外扩量 = (scale-1)×点到中心距离。细长武器(枪杆/刀身)
  端部离中心远、外扩偏多 → 端部膨胀、各部件描边粗细不均。
- 修复(新增默认算法):`OutlineRenderer.renderLionfishPerCube` 逐 cube 顶点法线外扩:
  - 精确复刻本体 `BasicModelPart.render` 变换链(translate(rotationPoint/16) →
    mulPose(rotationZYX) → scale(xScale,yScale,zScale)),在<b>每个部件局部坐标系</b>
    内渲染描边,姿态(rotateAngle)帧内实时读取,动画部件同步;
  - 反射读取 `ModelBox.quads`(TexturedQuad[]),每个顶点沿<b>聚合顶点法线</b>
    (相邻面法线平均后归一化;cube 24 个 quad 顶点按位置聚合成 8 个角点,共享
    顶点外扩方向一致 → 面间无裂缝)外扩<b>固定距离</b>
    offset = thickness×THICKNESS_SCALE×bewlr3dScale —— 位移与距中心距离无关,
    端部不再膨胀,描边等厚贴身;
  - UV 直接复用 `PositionTextureVertex.textureU/V`(构造时按 textureOffset 算好的
    归一化 UV,与本体完全一致,描边 alpha 遮罩形状重合);
  - 静态几何(坐标/UV/法线)按 cube 用 WeakHashMap 缓存(`ExpandedLionfishCube`),
    每帧只做矩阵变换与顶点写入,零反射(缓存只含与帧无关的输入,不违反性能纪律)。
- 配置: 新增 `bewlr3dPerCube`(默认 true,键位于 `run/config/enchanted_outlines-common.toml`):
  true = 逐 cube 顶点法线外扩(默认);false = 回退整体包围盒放大壳(旧算法,
  `renderLionfishInflated` 保留用于对比/回退)。配置界面新增同名开关行。
- 兼容性(向后): 逐 cube 外扩失败(其他 LionfishAPI 版本反射字段缺失 / 无 quads)
  时<b>自动回退整体放大壳</b> —— `renderLionfishPart` 返回实际渲染 cube 数,
  drawn=0 则 `renderBewlrEntityOutline` 改走 `renderLionfishInflated`,保证任何
  LionfishAPI 版本都有描边,不静默无描边。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`

### 修复: 模组细分盔甲(热泉石套装)描边比普通盔甲薄很多

- 问题: 永恒星光热泉石盔甲穿戴上描边明显比原版盔甲薄。
- 根因: 盔甲描边是"逐 cube 绕自身包围盒中心放大壳",外扩量 = (scale-1)×cube
  半对角线。原版盔甲 cube 大(如胸甲 8×12×4 像素,半对角线 ≈0.468 模型单位),
  描边自然厚;热泉石盔甲是模组自定义细分模型,cube 小很多 → 外扩按比例缩水 →
  描边明显变薄。与灾变武器整体壳"端部膨胀"同一类问题(外扩依赖几何尺寸)。
- 修复: `renderPartInflated` 新增 uniform 模式(仅盔甲路径启用,鞘翅/投掷物/长枪
  传 false 保持原视觉):每个 cube 的放大系数按自身尺寸自适应
  perCubeScale = 1+(scale-1)×refDiag/自身半对角线(refDiag=0.468,原版胸甲
  参考) → 所有部件表面外扩量一致:原版盔甲视觉完全不变,模组细分盔甲描边
  补足到相同厚度。新增配置 `armorUniformExpand`(默认 true,false 回退旧固定
  放大壳)。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`

### 修复: 配置界面语言键缺失(显示原始键名)

- 问题: 配置界面通用分类新增的 `bewlr3dScale`/`bewlr3dPrefer`/`bewlr3dPerCube`
  三行缺少语言翻译键 → 界面显示原始键名;本次新增的 `armorUniformExpand` 同样。
- 修复: `zh_cn.json` / `en_us.json` 补齐配置项的名称与 tooltip 翻译。
  (`bewlr3dPrefer` 键随后随该配置废弃一并移除,见上方条目。)
- 影响文件:
  - `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json`
  - `src/main/resources/assets/enchanted_outlines/lang/en_us.json`

---


### 修复: 永恒星光(Eternal Starlight)月弧长枪不描边、热泉石盔甲错误轮廓

- 问题: 模组 eternalstarlight-0.8.1+1.21.1+neoforge 的月弧长枪(crescent_spear)
  附魔后无描边;热泉石套装(thermal_springstone)盔甲描边轮廓与本体不符(缺角/实心)。
- 根因:
  - 长枪是 BEWLR 物品(crescent_spear.json parent = builtin/entity,占位模型无几何),
    本体在 GUI/GROUND/FIXED 下由永恒星光的 `ItemRendererMixin` 在 render 方法体内
    替换为 `crescent_spear_inventory#inventory` 平面模型渲染;描边此前对
    `isCustomRenderer()` 一律跳过 → 无描边。
  - 热泉石盔甲本体在 `HumanoidArmorLayer.renderArmorPiece` 方法体内(HEAD 之后)通过
    NeoForge hook 替换模型与纹理:模型换为带角的 `ThermalSpringStoneArmorModel`
    (`IClientItemExtensions.getHumanoidArmorModel`),纹理换为自定义路径
    `textures/armor/thermal_springstone_layer_X.png`
    (`Item.getArmorTexture`,不走标准 `textures/models/armor/`)。
    描边在 HEAD 用的是未替换的原版模型 + `layer.texture()` 生成的不存在标准路径
    → 缺角 + 形状纹理读取失败回退纯白矩形(实心)。
- 修复:
  - 盔甲:`HumanoidArmorLayerMixin` 改用 NeoForge 官方 hook 与本体同源——
    `ClientHooks.getArmorModel`(模型)+ `ClientHooks.getArmorTexture`(纹理,inner =
    slot==LEGS 与本体 usesInnerModel 一致)。hook 默认实现即原逻辑
    (getHumanoidArmorModel 返回原模型、getArmorTexture 返回 null 回退标准路径),
    原版/普通模组盔甲行为不变;实现了 NeoForge 扩展的模组盔甲自动与本体对齐。
    hook 返回非 HumanoidModel 时回退原模型(降级不破坏)。
  - BEWLR 物品:`OutlineRenderer.inventoryModelFor(ItemStack)` 通用解析——GUI/
    GROUND/FIXED 下按实测 MRL 变体顺序探测平面模型(永恒星光 style
    `<ns>:item/<id>_inventory#standalone` 优先,实测本体 GUI 实际渲染用的就是它;
    依次回退 `<ns>:item/<id>_inventory#inventory`、`<ns>:<id>_inventory#standalone`、
    `<ns>:<id>_inventory#inventory`、`<ns>:<id>#standalone`、`<ns>:<id>#inventory`),
    过滤 missing 模型与 builtin/entity 占位,找到平面模型即描边(与本体 GUI 实际
    渲染模型一致);找不到则照旧跳过。
  - BEWLR 手持物品 3D 描边(新增):`OutlineRenderer.renderBewlrEntityOutline`
    反射 BEWLR 持有模型做放大壳——标准 Mojang Model(root 是 ModelPart,如永恒星光
    CrescentSpearModel)走逐 cube 放大壳;LionfishAPI 骨骼模型(AdvancedEntityModel
    的 root 是 AdvancedModelBox,如灾变)走整体包围盒放大壳(T(c)·S·T(-c) +
    root.render)。模型字段按物品 id 大写 + `_MODEL` 约定从适配类
    (`ESItemStackRenderer`/`CMItemstackRenderer`)静态字段反射读取,缺失时触发一次
    BEWLR 渲染初始化。手持 FIRST/THIRD 与无平面变体的 GROUND/FIXED 均生效。
  - BEWLR 描边<b>错位/翻转修复</b>(同日第二轮):模组 BEWLR 渲染器在 display
    transform 之后、renderToBuffer 之前会对模型套一层<b>内部预变换</b>——永恒星光
    `scale(1,-1,-1)`,灾变 `translate(0.5,0.5,0.5)+scale(1,-1,-1)`。描边必须在同一
    预变换之后做放大壳,否则与本体错位/镜像。按模组适配:`BewlrModel` 记录
    preTransform(ES_FLIP/CM_CENTER_FLIP),`applyBewlrPreTransform` 复刻。
  - BEWLR 描边<b>灾变整体壳中心修复</b>(同日第三轮):LionfishAPI 整体放大壳的
    AABB 直接用 cube 的<b>像素坐标</b>算中心(差 16 倍)且未计各部件
    `rotationPointX/Y/Z` 代码偏移 → 灾变描边壳偏出本体。修复:`lionfishBounds`
    递归累加每级部件 rotationPoint 得全局像素 AABB,中心 ÷16 转模型单位。
    永恒星光(标准 Mojang 逐 cube 路径)本就正确,不受影响。
  - BEWLR 描边<b>颜色混合</b>(同日第四轮):BEWLR 3D 描边此前统一用纯白纹理
    (纯描边色,无混合)。现在反射读取本体纹理(持有类 `<ID>_TEXTURE` 或模型类
    `TEXTURE`,如灾变 CMItemstackRenderer.THE_IMMOLATOR_TEXTURE、永恒星光
    CrescentSpearModel.TEXTURE),描边颜色 = 贴图像素色 × 描边色,与扁平/盔甲
    混色开关一致;拿不到纹理时回退纯白。
  - BEWLR 3D 描边<b>配置化</b>(同日第五轮):3D 描边是几何放大壳(外扩量 =
    (scale-1)×点到中心距离),细长武器多数面离中心近 → 旧硬编码 0.12 太弱。
    新增配置 `bewlr3dScale`(默认 0.3,0.05-1.0 可调)替代硬编码;曾新增
    `bewlr3dPrefer`(<b>v0.1.7 已废弃移除</b>,见 v0.1.7 首条)。配置界面同步新增。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/mixin/HumanoidArmorLayerMixin.java`
  - `src/main/java/com/enchantedoutlines/mod/mixin/ItemRendererMixin.java`
  - `src/main/java/com/enchantedoutlines/mod/mixin/GuiGraphicsMixin.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

---


### 修复: 持有盾牌/三叉戟时与 FerriteCore 同装崩溃(不可变 Map)

- 问题: 装备 FerriteCore 时,持有盾牌或三叉戟进入物品栏/手持渲染即崩溃,
  `UnsupportedOperationException` 栈顶为
  `ModelSidesImpl.minimizeCulled → ImmutableCollections$AbstractImmutableMap.put`。
- 根因: `shieldModel()`/`tridentModel()` 用 `Map.of()` + `List.of()` 构造
  `culledFaces` 传入 `SimpleBakedModel`。FerriteCore 的 mixin 在模型构造器开头
  对传入的原始 Map 调用 `put()`(把空列表替换为紧凑共享实例),不可变 Map 直接抛异常。
- 修复: 新增 `newEmptyCulledFaces()` 辅助方法,返回 6 方向全为可变 `ArrayList`
  的可变 `HashMap`,两处模型构造统一改用;原"缺方向 get(dir) 返回 null →
  collectQuads addAll(null) 崩溃"的语义保持不变。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

---

## v0.1.4 (2026-08-08)

### 修复: 与 FA+Player 等 EMF 资源包共用时鞘翅描边错位

- 问题: 启用 FA+Player-v1.1 资源包(含 `emf/cem/elytra.jem`)时,鞘翅描边与本体错位。
- 根因: 1.21.1 的 `ElytraLayer` 通过 `EntityModelSet.bakeLayer(ModelLayers.ELYTRA)` 获取
  `ElytraModel`,EMF(Entity Model Features)会拦截 `bakeLayer` 并用 jem 替换模型树
  (翼片几何移到 EMF 自定义子部件,并附带自定义翅膀动画)。描边若在
  `ElytraLayer#render` 的 HEAD 复刻 `copyPropertiesTo + setupAnim` 并手动触发 EMF 动画,
  任何一步与本体渲染路径的细微差异都会导致描边与本体姿态不一致(轮廓错位)。
- 修复: 描边注入点改为 `ElytraLayer#render` 的 **TAIL**。本体渲染流程是
  `translate(0,0,0.125) → copyPropertiesTo → setupAnim → renderToBuffer`,
  其中 EMF 自定义动画在 `renderToBuffer → EMFModelPartWithState.render → root.animate()`
  内部应用;TAIL 时本体已渲染完毕,`elytraModel` 的姿态就是本体实际渲染用的
  <b>最终姿态</b>,直接复用渲染描边即可,无需复刻任何逻辑 —— 与本体 100% 同步。
  深度测试保证:本体(cutout,先画,写深度)遮挡描边放大壳的内侧表面,描边(translucent,
  后画)只显示外扩边缘,不污染本体表面。
- 移除: 原 HEAD 方案的 `setupAnim` 复刻与 EMF `getRoot()/animate()` 反射调用(不再需要)。
- 依赖说明: 本功能<b>不依赖 EMF 及任何前置</b>(如 ETF),不引用其任何类,无编译期/
  运行期硬依赖(`neoforge.mods.toml` 仅有 neoforge/minecraft 必需依赖)。有 EMF 时
  描边自动同步其自定义动画;无 EMF 时即原版姿态,同一套代码两种场景通用。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/mixin/ElytraLayerMixin.java`

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

