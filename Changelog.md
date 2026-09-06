# Enchanted Outlines Changelog

---

## v0.2.0

> 注:上一版 v0.1.12 中"TACZ 兼容已停用 / Hazen 描边整体停用 / 删除 enableTaczCompat"
> 的结论在本版被<b>部分扭转</b>:TACZ 与 Hazen'N Stuff 均以可开关的兼容模式恢复,
> 历史章节仍保留原措辞,以本版为准。

### 新功能:Hazen'N Stuff 全武器兼容(enableHazenCompat,默认开启)

- **背景**: 上版将整个 `hazennstuff:` 命名空间从 BEWLR/Geo 描边中一刀切排除(星怒等
  GeckoLib GeoItem 的 2D 贴片在几何放大壳下会膨胀成整块平面)。本版改为提供
  <b>全武器兼容模式</b>,并解决形状问题。
- **渲染策略**:`renderBewlrEntityOutline` 解除对 Hazen 命名空间的整体拦截,新增
  Hazen 专用分支 —— 所有 context<b>先走 Geo 贴图 alpha 采样轮廓</b>(形状由本体贴图
  决定,扁平 quad 不再膨胀成整块平面),<b>失败再回退渲染捕获兜底</b>(任意 BEWLR/
  自绘模型)→ 覆盖 Hazen 全部武器模型类型(GeckoLib/AzureLib/普通 BEWLR/自绘)。
  扁平物品(非 customRenderer)与 AzureLib 盔甲(独立 HumanoidArmorLayer 路径)不受影响。
- **配置**:新增 `enableHazenCompat`(默认 true),关闭即回归旧"一律不描边";
  配置界面高级分类新增开关行,`zh_cn` / `en_us` 同步语言键(键 `hazenCompat`,含
  tooltip)。引用铁律:配置界面新增行必须同步语言键。

### 新功能:TACZ 枪械兼容恢复(enableTaczCompat,默认开启)

- 恢复 `enableTaczCompat`:解除对 `tacz:` / `thearsenalcollection:` 的一刀切排除,
  改由开关控制。TACZ 枪械为自绘 Bedrock BEWLR(`GunItemRendererWrapper`),几何不在
  BakedModel——失去完整 BEWLR 路径后统一走 `renderBewlrCapturedOutline`(`renderByItem`
  捕获顶点 → 绕捕获几何外扩壳)。若某姿态/版本仍错位可关闭该开关回到无描边。

### 修复:Config 并发编辑残留的字段重复定义

- 并发重写导致 `ENABLE_HAZEN_COMPAT` 在 `Config.java` 中重复声明(两处相同定义),
  必造成编译失败。已删除冗余的重复声明,仅保留渲染器实际引用的一份,并确认
  `ENABLE_TACZ_COMPAT` 与 `hazenCompat`/`taczCompat` 语言键齐全。

### 验证

- `gradlew compileJava --rerun-tasks` 强制重编译 `BUILD SUCCESSFUL`(非缓存);
- 无人值守启动客户端,`Enchanted Outlines 0.1.13` 与 Hazen'N Stuff / TACZ / 孢 /
  Iris 等模组共存下干净进入单机世界,无来自本版改动的报错;新配置键(含
  `enableTaczCompat`/`enableHazenCompat`)自动写入配置。
- （注:具体武器外观的最终视觉轮廓仍需交互式游玩验证。）

### 影响文件

- `outline/OutlineRenderer.java`、`config/Config.java`、`config/EnchantedConfigScreen.java`、
  `assets/.../lang/zh_cn.json`、`en_us.json`、`gradle.properties`、`UNIVERSAL_MODEL_ADAPTER_PLAN.md`、`Changelog.md`

---


### 重构:删除零引用的 api/ 适配层(20+ 文件)

- **背景**: `com.enchantedoutlines.mod.api`(adapter/algo/color/core/pipeline 共 15 Java
  + ApiArchitecture.md)自 v0.1.9 起从未被接线——全项目无任何 mixin/渲染代码引用该包,
  渲染链路全部直连 `outline.OutlineRenderer` + `outline.ColorResolver`。经引用图全面核查
  确认外部零引用后整包删除(含两份同名 `ItemModelRegistry`、与活跃版重复的
  `api.color.ColorResolver`、无实现类的 `OutlineShapeTextureProvider`、无真实调用方的
  门面 `OutlineRenderingApi` 等)。
- **文档同步**: `API_README.md` 整篇重写,只保留真实对外扩展点
  (`outline.OutlineColorRegistry` + `outline.OutlineColorEvent`,含 API 说明/颜色优先级/
  兼容矩阵);`UNIVERSAL_MODEL_ADAPTER_PLAN.md` 顶部标注状态(捕获已落地、api 层已删、
  TACZ/Hazen 适配已停用,文中相关章节仅作历史参考)。
- **影响文件**: 删除 `api/` 整包;`API_README.md`;`UNIVERSAL_MODEL_ADAPTER_PLAN.md`

### 重构:移除无实际效果的死配置 ×3(含 UI 行与语言键)

- `enableTaczCompat` / `enableDataPackColors` / `optimizeConfigUi` 三个开关只存在于
  定义 + 配置界面 UI 行,业务代码<b>零读取</b>(TACZ 兼容已停用、数据包颜色资源
  `pack.json` 根本不存在、"优化界面"恒为分类布局)——删除 Config 常量、ConfigScreen 行、
  两语言文件 7 个键;另清理未引用的 `config.cancel` 语言键。
- **影响文件**: `config/Config.java`、`config/EnchantedConfigScreen.java`、
  `assets/.../lang/zh_cn.json`、`en_us.json`

### 修复:BEWLR 字段名映射 key 回归(灾变/永恒星光 BEWLR 3D 描边整体失效)

- **根因**: `findBewlrModel` 用 `BuiltInRegistries.ITEM.getKey(...).toString()`(带命名
  空间,如 `cataclysm:coral_spear`)作为查找键,而 `CATACLYSM_WEAPON_SPECS` 的 key 是
  <b>纯路径</b>(`coral_spear`)→ 精确映射恒 miss;通用兜底字段名把带命名空间的字符串
  大写 → 含冒号 `CATACLYSM:CORAL_SPEAR_MODEL`,Java 字段名不可能含 `:`,兜底也必 miss
  → 灾变精确适配与永恒星光通用适配全部失效。git 历史定位:此回归由 c108d19 把
  `getPath()` 改成 `toString()` 引入。
- **修复**: `findBewlrModel` 改回 `getPath()`(纯路径),映射表与兜底重新命中;
  命名空间级排除(TACZ/Hazen)仍在 `renderBewlrEntityOutline` 顶部完成,不受影响。
- **影响文件**: `outline/OutlineRenderer.java`

### 重构:清理失效的模组适配表与死分支(OutlineRenderer)

- 删除 `HAZEN_WEAPON_FIELD_MAP`(key 命名空间 `hazenn:` 与真实 `hazennstuff` 不符,
  恒 miss;且 Hazen Geo 描边已整体停用)与 `SPORE_WEAPON_FIELD_MAP`(持有类
  `SItemProperties` 早已从 `BEWLR_MODEL_HOLDER_CLASSES` 移除,javap 证实无静态模型
  字段,反射必然失败)。`readBewlrModelField` 同步移除 Spore/Hazen 分支;
  `holderPreTransform` 移除不可达的 Hazen/Spore 前缀分支。
- 从 `BEWLR_MODEL_HOLDER_CLASSES` 移除已停用的 Hazen 持有类
  (`HSNEnchantedItemStackRenderer`,整命名空间被拦截后永远不会被查询)。
- 清理一次性诊断残留:`CAPTURE_DIAG`/`FLAT_SHAPE_DIAG`/`HAZEN_DIAG` 字段与全部
  `[EO-diag]` 日志(AGENTS.md #6);死字段 `AnimatedShapeKey`/`animatedShapeTextures`;
  死重载 5 参 `renderHandOutline`;死方法 `CaptureSource.endBatch` /
  `CaptureVertexConsumer.finish`;`renderBewlrCapturedOutline` 不可达的 `quads==0`
  分支。
- **影响文件**: `outline/OutlineRenderer.java`、`mixin/GuiGraphicsMixin.java`

### 修复:通用渲染捕获的图元模式假设(CaptureSource)

- 捕获流按 `renderType.mode()` 校验,只接受 QUADS(每 4 顶点一个 quad);检测到
  TRIANGLES 等非 QUADS 的 RenderType 时整体放弃捕获(返回 null → 回退 Geo/跳过),
  杜绝"按 12 float 一组重建 quad 导致三角形流畸形几何"。宁可无描边,不错位渲染
  (与 TACZ/Hazen 停用同原则)。
- **影响文件**: `outline/OutlineRenderer.java`

### 修复:颜色解析逻辑(ColorResolver)

- **RAINBOW_CACHE 写而不读**:旧代码把"最后计算时间"写入缓存但从不读取,100ms
  节流是空操作、HSBtoRGB 每帧照算、缓存永不清理(invalidateCache 无调用方)。
  彩虹本就该逐帧刷新,删除无效缓存与空壳方法,保留连续颜色。
- **disableItem 被 RPG 虹灯穿透**:程序化 `OutlineColorRegistry.disableItem` /
  配置 `disabledItems` 的禁用检查提前到彩虹分支之前——"禁用该物品描边(即使带附魔)"
  的 API 承诺现在对开启彩虹同样生效。
- 提取 `fixedItemColor`(程序化 > 配置的物品固定色)消除 resolve/resolveFoilOnly
  的重复块。
- **影响文件**: `outline/ColorResolver.java`

### 修复:健壮性小项

- `mixin/HumanoidArmorLayerMixin`:GeckoLib 槽位遍历分支补上与 12 参分支同款的
  `armorItem.getEquipmentSlot() == slot` 守卫(错槽时不按错误槽位画壳)。
- `mixin/GuiGraphicsMixin`:GUI 描边主体包 try-catch(描边异常不再打断本体渲染,
  且保证走 RETURN 恢复 `IN_GUI_RENDER` 标记,不残留"GUI 态"污染后续世界渲染);
  顺带把主体拆成 private static 方法。
- `OutlineRenderer.lionfishBounds`:cubeList/childModels 反射改沿父类链 `findField`
  (与 renderLionfishPart 一致),修复 BasicModelPart 子类(AdvancedModelBox)上
  getDeclaredField 抛 NoSuchFieldException → 整体壳回退路径子树静默跳过的问题。
- `OutlineRenderer.invalidateCaches`:补清 `lionfishCubeCache`(与 geoCubeCache 一致)。
- **影响文件**: `mixin/HumanoidArmorLayerMixin.java`、`mixin/GuiGraphicsMixin.java`、
  `outline/OutlineRenderer.java`

### 其他

- 移除不再使用的 `EnchantedOutlines` import(OutlineRenderer,诊断日志删除后)。
- 新增 `tmp/check_lang_keys.py`(一次性语言键校验脚本,用后已删)。

### 确认:Hazen 描边停用范围以代码为准(整命名空间 `hazennstuff`)

- v0.1.11 第六轮 Changelog 记为仅停用星怒(`isHazenStarfury`);实际后续代码演进为
  `isHazenNamespace`(整个 `hazennstuff` 命名空间的 Geo/BEWLR 物品,星怒/黑曜石巨剑/
  永夜刃等 GeckoLib GeoItem 同机制都会把 2D 贴片膨胀成整块平面)。本次确认以代码为准:
  `renderBewlrEntityOutline` 顶部 `isTaczNamespace(stack) || isHazenNamespace(stack)`
  → 直接 return false,两家全 context 不再进入 findBewlrModel / Geo / 捕获任何分支。
  Hazen 的普通扁平物品(非 customRenderer)与 AzureLib 盔甲不受影响。

---

### 移除:TACZ 与 Hazen 星怒的描边兼容(2026-09-06 第六轮,用户决策)

- **决策背景**: 通用渲染捕获虽能出轮廓,但 TACZ 自绘 Bedrock 渲染强依赖相机/玩家动画上下文 → 无上下文捕获几何错位持续存在;Hazen 星怒(`hazennstuff:starfury`,GeckoLib GeoItem)的 Geo/捕获路径对 GUI 产生<b>不透明错误轮廓</b>。用户明确要求移除这两家兼容,宁可无描边也不要错位/错误轮廓。
- **修复**:
  - `renderBewlrEntityOutline` 顶部新增拦截:`isTaczNamespace(stack) || isHazenStarfury(stack)` → 直接 return false。TACZ 全 context(含 GUI)、星怒全 context 不再进入 findBewlrModel / Geo / 捕获任何分支,彻底回到无描边。
  - 新增 `isHazenStarfury`:注册 ID 为 `hazennstuff:starfury`(含路径包含 starfury)。
  - 撤销 `inventoryModelFor` 的 `_gui` 变体候选(2026-09-06 四轮误加):Hazen 星怒有 `starfury_gui` 平面模型文件,但 GUI 本体实为 BEWLR 3D Geo(实测 customRenderer=true),平面描边套 3D 本体会错位 —— 该候选反而让星怒命中错误平面。
- **影响文件**: `OutlineRenderer.java`

### 实现:通用 BEWLR 模型获取算法"渲染捕获"正式接入(2026-09-06 第四轮)

- **背景**: 前三轮只在文档层修正了 `UNIVERSAL_MODEL_ADAPTER_PLAN.md`,渲染链路从未真正调用捕获 → 用户反馈"通用性算法没有起到效果"(正确:确实没接入)。本轮把捕获器与兜底完整实现并接入。
- **通用渲染捕获核心**(OutlineRenderer):
  - `resolveBewlrRenderer(stack)`:按 MC 两条标准注册通路拿 BEWLR 渲染器,不反射模组内部字段——① `IClientItemExtensions.of(stack).getCustomRenderer()`(TACZ GunItemRendererWrapper / 灾变 CMItemstackRenderer 等);② GeckoLib `GeoRenderProvider.of(item).getGeoItemRenderer()`(Hazen 星怒 StarfuryItem implements GeoItem;已 javap 证实 GeoItemRenderer extends BlockEntityWithoutLevelRenderer)。
  - `captureBewlrGeometry`:复制当前描边 pose,调 `renderByItem`,用 `CaptureSource`/`CaptureVertexConsumer` 拦截 addVertex 顶点流(只收 xyz,不真正 endBatch/上屏)。
  - `renderBewlrCapturedOutline`:捕获顶点已是**最终绘制坐标**(含本体 display/预变换/动画姿态)→ 绕全局 AABB 中心放大壳画描边(scale=1+thickness×0.5×BEWLR_3D_SCALE),本体随后覆盖中心。
  - `renderBewlrEntityOutline` 兜底顺序重构:GUI/FIXED/GROUND 下本体就是 `renderByItem` 按该 context 画的 → **捕获优先**(同姿态最贴合),失败再回退 Geo 骨骼;手持先 Geo 快路径、失败捕获。消除旧 GUI 下 Geo 骨骼路径套用手持 `translate(0.5,0.51,0.5)` 导致错位出图标的问题。
  - 调用点传真实 `ItemDisplayContext`(GuiGraphicsMixin=GUI / ItemRendererMixin=context / ItemInHandRendererMixin=context)。
- **诊断日志**(临时,修复后清理):一次性(按物品)打印捕获成功顶点数 / 无几何原因 / renderByItem 异常栈 / 渲染器类名。本次实机(04:24)已确认 TACZ FIXED `ext=AbstractGunItem$1 customRenderer=GunItemRendererWrapper` 捕获 0 顶点(展示框无 player 上下文,渲染器不绘制),证明链路已通;GUI 星怒走 `customRenderer=true + inventoryModel=null`(已确认本体是 BEWLR 3D 非平面,尽管 jar 有 `starfury_gui` 平面文件但未在 GUI 使用)。
- **影响文件**: `OutlineRenderer.java` + 三个 BEWLR 调用点 mixin

### 修复:星怒 GUI 捕获崩溃 "Duplicate delegates" + TACZ 错位渲染(2026-09-06 第五轮)

- **问题**: 星怒(`hazennstuff:starfury`)GUI 捕获抛 `java.lang.IllegalArgumentException: Duplicate delegates`;TACZ 出现错位渲染。
- **Duplicate delegates 根因**: `CaptureSource.getBuffer()` 对所有 RenderType 返回**同一个** `CaptureVertexConsumer` 实例,而真实 `BufferSource` 语义是每 RenderType 独立 consumer。星怒的 GeckoLib 渲染内部用 `VertexMultiConsumer` 组合主渲染 buffer + 附魔层 buffer,两个都是同一实例 → 组合器检测到重复 delegate 抛异常。
- **修复**: `CaptureSource` 改为 `IdentityHashMap<RenderType, CaptureVertexConsumer>`——每个 RenderType 独立实例(同一 RT 复用),`finish()` 汇总各 consumer 顶点。渲染器把同一条顶点写入主+附魔两个 buffer 会重复计数,但描边只做 AABB/壳,重复顶点放大后重叠无视觉影响。
- **TACZ 错位渲染根因与修复**: TACZ 手持/展示框本体由其第一人称专用渲染器(带相机/玩家动画上下文)绘制,脱离上下文调 `renderByItem` 捕获的几何必然与本体错位(此前用户已明确"错位=bug,宁可无描边")。修复:`renderBewlrEntityOutline` 对 TACZ 命名空间**仅 GUI 允许捕获**(本体=2D slot 平面确定性几何),手持/FIXED/GROUND 直接跳过(无描边,但绝不错位)。
- **影响文件**: `OutlineRenderer.java`

### 修复:开启光影后背包/容器 UI 里非方块物品描边显示淡灰色(方块正常)

- **问题**: 开启光影(Iris)后,背包/容器/快捷栏 UI 中所有**非方块物品**(剑/工具等扁平物品,以及 TACZ、真菌感染等模组的扁平/Geo/BEWLR GUI 图标)的描边颜色变成不正确的淡灰色;方块(GUI 3D)正常。反复修 TACZ/Spore 无效。
- **根因**: `GuiGraphics`(背包等)是 **2D 屏幕空间渲染,不经 Iris 光影**,自定义 outline shader 在 GUI 中始终可用。但 GUI 扁平物品描边路径 `OutlineRenderer.renderFlatOutlineShape` 内部**无条件调用 `needVanillaShaderFallback()`**——开光影时错误地把 RenderType 切到 `worldEmissiveOutlineRenderType`(带 LIGHTMAP/OVERLAY 状态、为世界渲染设计的内置 emissive),在 GUI 屏幕空间渲染出淡灰。方块 GUI 3D 走 `handOutlineRenderType()`(恒自定义 shader,不查 fallback),所以"物品灰、方块正常"。该问题与具体模组无关,任何非方块物品都中招,因此针对 TACZ/Spore 的适配修复自然"反复无效"。
- **修复**: 统一用 **GUI 渲染线程标志**区分渲染上下文:
  - `OutlineRenderer` 新增 `IN_GUI_RENDER` ThreadLocal 与 `markGuiRender()` / `isInGuiRender()`(放普通类,mixin 只留 private 方法,遵守 2026-08-13 的 mixin 工具方法铁律);
  - `needVanillaShaderFallback()` 在 GUI 渲染期间恒返回 `false`(自定义 shader),**且不更新** 500ms 光影状态缓存(避免污染随后世界渲染的判断);
  - `GuiGraphicsMixin` 在 `GuiGraphics.renderItem`(7 参,所有公开 renderItem 重载的唯一漏斗)HEAD 设 true、RETURN 恢复 false——背包/容器/快捷栏全部 GUI 图标渲染都被覆盖,扁平/Geo/BEWLR 各路径(renderFlatOutlineShape、armorOutlineRenderType 等)不再错误 fallback。
  - 世界渲染路径(手持/掉落物/展示框/盔甲/鞘翅/投掷物)不设 GUI 标记,光影 fallback 逻辑完全保留,行为不变。
- **影响文件**: `OutlineRenderer.java`、`mixin/GuiGraphicsMixin.java`

### 修复:澄清 TACZ/真菌感染"反复修无效"的逻辑根因(文档与代码现状)

- 经 jar 反编译核实(2026-09-06):
  - v0.1.9 起的整个 `src/main/java/com/enchantedoutlines/mod/api/` 适配层(`ItemModelRegistry`/`TaczAdapter`/`RenderPipeline`/`ShapeDetector` 等 20+ 文件)是**未接线的死代码**——所有渲染 mixin 直连 `OutlineRenderer.INSTANCE`,没有任何类引用 `com.enchantedoutlines.mod.api`。此前在 api 层对 TACZ/Spore 的修复从未进入渲染链路。
  - `SPORE_WEAPON_FIELD_MAP` 假设 `com.Harbinger.Spore.Client.SItemProperties` 持有 `SporeSword_MODEL` 等静态模型字段,javap 证实该类只有 `makeBow/makeSickle/makeShield` 等 ItemProperties 注册方法、**无任何模型字段** → 反射必然失败。
  - 本次 GUI 淡灰修复已覆盖"非方块物品开光影变灰"的全部表现(含 TACZ/Spore 物品图标);TACZ 枪(自研 Bedrock simplebedrockmodel 渲染)与 Spore 枪(Blockbench elements + 仅 arm pose 的 IClientItemExtensions)的**独立手持/世界 3D 适配**留待后续按真实渲染结构设计,不再依赖失效的 api 层与字段映射。

### 修复:TACZ 手持/世界"头顶巨大额外模型"(2026-09-06 第二轮)

- **问题**: TACZ 枪在手持/世界渲染时,本体正常但在模型头顶出现**巨大额外模型**;同时 TACZ 枪在 GUI 无描边。
- **根因**: TACZ 枪(注册 ID 如 `tacz:modern_kinetic_gun`)是**自绘 BEWLR**(渲染器 `GunItemRendererWrapper extends AnimateGeoItemRenderer`,simplebedrockmodel 的 Bedrock 骨骼;GUI 本体=2D slot 纹理平面,手持本体=3D Bedrock 模型)——既无静态模型字段,也不属于 GeckoLib/AzureLib/Lionfish 骨骼体系。描边对 builtin/entity 物品走到 `renderBewlrEntityOutline` → 反射拿不到模型 → 继续 `renderGeoItemOutline` 把无关对象当 Geo 模型放大 → 头顶巨大错乱模型。
- **修复**: `renderBewlrEntityOutline` 顶部对 TACZ 命名空间(`tacz:`/`thearsenalcollection:`)直接返回 false——不再产生任何错误放大几何(巨大模型消失);GUI 下该分支同样不再画错乱放大壳。同时修正 `readBewlrModelField` 前缀判断 bug(旧 `:tac/` 从不匹配 `tacz:...`),从 `BEWLR_MODEL_HOLDER_CLASSES` 移除无模型字段的 Spore `SItemProperties`(javap 证实),杜绝无效反射与误导性适配表。
- **遗留**: TACZ 枪在 GUI 的 2D 槽位描边(本体=slot 纹理平面)需要按真实 GUI 几何接入(见注释 `renderTaczGuiOutline` 预留),手持 3D Bedrock 描边待专项;两者都需要游戏内截图确认几何,不贸然错位渲染。
- **影响文件**: `OutlineRenderer.java`

### 修复:真菌感染(Spore)盔甲"无描边"(2026-09-06 第二轮)

- **问题**: 玩家穿 Spore 盔甲(感染胸甲/头盔/护腿/靴等)时完全没有描边。
- **根因**: 玩家身上的 Spore 盔甲<b>本体不是由原版 HumanoidArmorLayer 绘制</b>:Spore 通过 `EntityRenderersEvent.AddLayers` 向玩家渲染器注册自研 `CustomArmorLayer`(ArmorModelList.ARMOR_RENDERING_BITS 部件渲染),而原版 ArmorLayer 用的 `SporeBaseArmor.getArmorTexture` 恒返回 `spore:textures/entity/empty.png`(全透明 32×32)。我们的盔甲描边 hook 在原版 layer 采样该纹理 alpha → 描边壳整体透明 → 不可见。
- **修复**: `OutlineRenderer.armorOutlineRenderType` 增加<b>全透明纹理检测</b>(`isFullyTransparent`,ImageIO 读 PNG 统计 alpha,结果按纹理缓存并随 `invalidateCaches` 清理):采样源全透明时回退 `WHITE_TEXTURE`——外层几何(renderPartInflated 逐 cube 放大壳)不受影响,描边变为<b>纯几何实心壳</b>绕本体可见,不再被透明纹理吞掉。
- **影响文件**: `OutlineRenderer.java`

### 设计:通用模型适配算法"渲染捕获"修正(2026-09-06 第三轮)

- **背景**: 你同步的 `UNIVERSAL_MODEL_ADAPTER_PLAN.md` + `OutlineRenderer.getUniversalBewlrModel()`(stub)方向正确,但文档 §3.2.1 用 `ItemRenderer.getItemModelShaper().getModel(stack)` 取模型——对 **BEWLR 物品(builtin/entity)无效**:占位 BakedModel 无 quads,几何只存在于模组渲染器 `renderByItem` 画出的顶点里。这解释了为何"每个模组最终都被 MC 渲染"却始终取不到几何。
- **实证(javap)**: TACZ 枪 `GunItemRendererWrapper extends AnimateGeoItemRenderer`(自绘 Bedrock);Hazen 星怒 = `hazennstuff:starfury`(StarfuryItem implements GeckoLib **GeoItem**,StarfuryRenderer extends GeoItemRenderer,GUI 另有 `starfury_gui` 平面),与代码中 `HAZEN_WEAPON_FIELD_MAP` 假设的 `hazenn:wrath`→`WRATH_MODEL` 静态字段**完全不符**;Spore 盔甲本体由自研 `CustomArmorLayer` 部件渲染。
- **文档修正**: `UNIVERSAL_MODEL_ADAPTER_PLAN.md` 更新为 **渲染捕获(Render Capture)** 设计:调用模组自己的 `renderByItem` 并把 `MultiBufferSource` 换成捕获器,拦截 addVertex 顶点流还原 quads → 复用现有法线外扩/AABB 壳画描边。附兼容矩阵(TACZ 手持 3D/Hazen Geo/灾变 Lionfish/永恒星光),列出风险与缓存纪律。
- **遗留(待实测)**:
  - TACZ: 手持 3D Bedrock 需按捕获几何接入(取消命名空间一刀切 return false);GUI 2D slot 需按 `renderSlotTexture` 几何或 slot 纹理形状路径接入;
  - Hazen: 星怒等 GeckoLib GeoItem 走 `renderGeoItemOutline` 快路径应命中,若仍失败需游戏内排查 GeoRenderProvider 初始化时序,再以捕获兜底;
  - Spore: 盔甲描边需 hook 自研 `CustomArmorLayer`(部件渲染)才能贴合本体;武器以扁平/Blockbench 3D 为主,按真实几何排查。
- **影响文件**: `UNIVERSAL_MODEL_ADAPTER_PLAN.md`(设计修正)

---


### 修复：TACZ 轮廓错位和巨大模型

- **问题**: TACZ 物品轮廓错位，空中显示巨大模型。
- **原因**: TACZ 使用 GeoLib 渲染而非 BEWLR，但 `ItemModelRegistry` 将其标记为 BEWLR → 描边进入 BEWLR 路径后无法正确 fallback → 几何错位。
- **修复**: 在 `ItemModelRegistry` 中为 TACZ 物品显式设置 `isBewlr=false`，强制走 Geo 路径。

### 修复：真菌感染 (Spore) 武器和盔甲无法渲染轮廓

- **问题**: Spore 模组的所有武器和盔甲描边完全缺失。
- **原因**: Spore 的模型字段名不遵循通用约定 (`<ID>_MODEL`)，`readBewlrModelField` 无法通过反射找到模型。
- **修复**: 在 `OutlineRenderer` 中添加 `SPORE_WEAPON_FIELD_MAP` 映射表，显式映射 Spore 武器字段名 (如 `spore:spore_sword` → `SporeSword_MODEL`)，并在 `readBewlrModelField` 中检查该映射表。

### 修复：Hazen'N Stuff 星怒武器无法渲染轮廓

- **问题**: Hazen'N Stuff 的"星怒" (`hazenn:wrath` / `hazenn:wrath_weapon`) 描边缺失。
- **原因**: Hazen 武器字段名不遵循通用约定，需要精确映射。
- **修复**: HazenAdapter 和 OutlineRenderer 的 `HAZEN_WEAPON_FIELD_MAP` 已包含星怒武器的精确映射 (`hazenn:wrath` / `hazenn:wrath_weapon` → `WRATH_MODEL`)，修复完成。

### 影响文件

- `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java` (TACZ isBewlr=false)
- `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (SPORE_WEAPON_FIELD_MAP)
- `src/main/java/com/enchantedoutlines/mod/api/adapter/HazenAdapter.java` (已包含星怒映射)

---


### 新增：结构化 API 库架构

- **背景**: 原 `OutlineRenderer` 类达 3700+ 行，包含所有渲染逻辑、适配器、算法，难以维护和扩展。为支持外部模组集成和持续开发，将渲染逻辑收编为结构化的 API 库。
- **新模块结构**:
  ```
  src/main/java/com/enchantedoutlines/mod/api/
  ├── core/                    # 核心层
  │   ├── OutlineRenderingApi  # 主 API 接口 (统一入口)
  │   ├── ItemTypeInfo         # 物品类型信息 (BEWLR/Geo/Lionfish/...)
  │   └── ItemModelRegistry    # 类型注册与判断缓存 (首次判断，结果复用)
  ├── algo/                    # 算法层
  │   ├── ShapeResult          # 形状检测结果 (扁平/3D/算法类型)
  │   ├── ShapeDetector        # 形状检测器 (按 BakedModel 缓存)
  │   └── OutlineShapeTextureProvider  # 形状纹理提供者接口
  ├── color/                   # 颜色处理层
  │   └── ColorResolver        # 颜色解析器 (RPG 虹灯→数据包→配置)
  ├── pipeline/                # 渲染管线层
  │   └── RenderPipeline       # 渲染调度器 (协调各适配器)
  └── adapter/                 # 适配器层
      ├── ItemModelAdapter     # 适配器接口 (统一方法签名)
      ├── BewlrAdapter         # BEWLR 物品适配器 (永恒星光/灾变)
      ├── GeoAdapter           # GeoLib/AzureLib 适配器
      ├── LionfishAdapter      # LionfishAPI 适配器 (灾变武器)
      └── TaczAdapter          # TACZ 专项适配器
  ```
- **判断缓存机制**: 物品类型判断 (BEWLR/Geo/Lionfish/TACZ) 仅首次执行，结果按 `Item` 缓存，后续渲染直接复用 → 避免每帧重复反射。
- **几何缓存**: 模型 quads/法线/外扩顶点按 `BakedModel` 弱引用缓存，帧间复用。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/**/*` (新增 20+ 文件)
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (保留原生实现，作为 API 内部委托目标)
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java` (新增 API 相关配置)
  - `API_README.md` (API 使用文档)
  - `api/ApiArchitecture.md` (内部架构设计文档)

### 新增：RPG 虹灯 (动态彩虹描边)

- **功能**: 启用后描边颜色随时间动态变化，形成彩虹效果。
- **优先级**: 最高，覆盖所有其他颜色配置 (数据包/配置文件/附魔)。
- **配置项**: `enableRPGRainbow` (默认 `false`)
- **原理**: 基于时间 + 物品 hashCode 的 HSB 转 RGB 动态渐变，5 秒周期。
- **实现**:
  - 集成到 `outline/ColorResolver.resolve()` 首行判断，优先级最高；
  - 缓存每物品独立时间戳 (`RAINBOW_CACHE`)，每 100ms 更新一次，避免每帧计算；
  - 配置改动时 `invalidateCache()` 自动清空彩虹缓存。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/ColorResolver.java` (新增 `resolveRainbowColor()` + `invalidateCache()`)
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (experimental 分类新增)
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (`invalidateCaches()` 调用 `ColorResolver.invalidateCache()`)

### 新增：TACZ 专项兼容

- **功能**: 为 The Arsenal Collection Z 模组提供特殊渲染支持。
- **配置项**: `enableTaczCompat` (默认 `true`)
- **适配内容**: TACZ Lionfish 武器动态模型获取 (通过 `IClientItemExtensions.getCustomRenderer()` 反射渲染器内部模型对象)。
- **修复**: TACZ 武器附魔后模型错位/描边缺失问题。
- **实现**:
  - `TaczAdapter.resolve()` 返回 `ItemTypeInfo(isLionfish=true, isTacz=true)`；
  - `ItemModelRegistry` 按适配器优先级判断 (TACZ→Lionfish)；
  - `OutlineRenderer.findTaczLionfishModel()` 动态获取模型 + 纹理，无预变换。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (新增 `findTaczLionfishModel()`)
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (advanced 分类)

### 新增：数据包颜色支持

- **功能**: 从 `assets/enchanted_outlines/color/pack.json` 加载颜色映射，覆盖配置文件颜色。
- **优先级**: 低于 RPG 虹灯，高于配置文件 (物品/附魔颜色)。
- **配置项**: `enableDataPackColors` (默认 `true`)
- **JSON 格式**:
  ```json
  {
    "minecraft:diamond_sword": "FFD700",
    "minecraft:netherite_chestplate": "FF6A00",
    "my_mod:custom_item": "87CEEB"
  }
  ```
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/api/color/ColorResolver.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (experimental 分类)

### 优化：配置界面分类与持久化

- **分类导航**: 配置界面按功能分为 4 个分类：
  - **general**: 总开关、描边厚度、混色选项
  - **colors**: 默认颜色、附魔颜色、物品颜色、白名单/黑名单
  - **advanced**: BEWLR 3D 配置、TACZ 兼容、多附魔取色模式
  - **experimental**: RPG 虹灯、数据包颜色、配置界面优化
- **配置持久化**: 所有配置修改立即写盘 (`Config.save()`),解析缓存即时失效 (`Config.invalidateCache()`)。
- **语言文件同步**: `zh_cn.json` / `en_us.json` 新增所有配置项的中文/英文翻译。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (4 分类导航、新增实验性功能)
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java` (新增配置项)
  - `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json` / `en_us.json` (新增语言键)

### 优化：判断与检查机制 (首次判断缓存)

- **设计**: 物品类型判断 (BEWLR/Geo/Lionfish/TACZ) 仅首次执行，结果按 `Item` 缓存。
- **缓存键**:
  - `ItemTypeInfo`: 按 `Item` 缓存
  - `ShapeResult`: 按 `BakedModel` 缓存 (弱引用，资源重载自动回收)
  - `adapterCache`: 按 `Item` 缓存适配器类型
- **失效机制**:
  - 进出世界：`OutlineRenderer.invalidateCaches()`
  - 资源重载：`OutlineRenderer.invalidateCaches()`
  - 配置改动：`Config.invalidateCache()`
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java`
  - `src/main/java/com/enchantedoutlines/mod/api/algo/ShapeDetector.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (`invalidateCaches` 扩展)

### 修复：API 库编译错误 (NeoForge 20.x 兼容)

- **问题**: 所有适配器未实现 `ItemModelAdapter.resolve()` 方法、`VertexConsumer` 匿名内部类缺少 `setNormal()` 方法、`MultiBufferSource.immediate()` 参数类型不兼容、`ItemEnchantments` API 变更。
- **修复**:
  - 所有适配器 (`BewlrAdapter`/`GeoAdapter`/`LionfishAdapter`/`TaczAdapter`) 实现 `resolve(Item)` 方法，返回 `ItemTypeInfo` 或 `null`
  - 所有适配器添加 `INSTANCE` 单例字段
  - 删除 `BewlrAdapter.getCustomModel()` 中的 `VertexConsumer` 匿名内部类 (改由 `OutlineRenderer` 直接处理)
  - `ColorResolver.getEnchantments()` 改用 `HolderList` 迭代器，移除 `forEach` + 内部变量
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/BewlrAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/GeoAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/LionfishAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/color/ColorResolver.java`

### 修复：RPG 虹灯未集成到渲染管线

- **问题**: `api/color/ColorResolver` 实现了 `resolveRainbowColor()` 方法，但 `outline/ColorResolver.resolve()` 未调用，导致启用 `enableRPGRainbow` 配置后无实际效果。
- **修复**:
  - 在 `outline/ColorResolver.resolve()` 首行判断 `Config.ENABLE_RPG_RAINBOW.get()`，直接调用 `resolveRainbowColor()`；
  - 新增 `resolveRainbowColor()` 静态方法，基于时间 + 物品 hashCode 计算 HSB hue，5 秒周期渐变；
  - 每物品独立缓存 (`RAINBOW_CACHE`)，每 100ms 更新一次，避免每帧计算开销；
  - `OutlineRenderer.invalidateCaches()` 新增调用 `ColorResolver.invalidateCache()`，进出世界/配置改动时清空彩虹缓存。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/ColorResolver.java` (重写 `resolve()` + 新增 `resolveRainbowColor()` + `invalidateCache()`)
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (`invalidateCaches()` 扩展)

### 其他

- **文档**:
  - `API_README.md` - API 使用文档 (快速开始、示例、注意事项)
  - `api/ApiArchitecture.md` - 内部架构设计文档
  - `Changelog.md` - 更新日志 v0.1.9
- **兼容性**: API 库不破坏原版逻辑，`OutlineRenderer` 保留原生实现，API 内部委托给原生方法 → 现有功能完全兼容。
- **扩展性**: 外部模组可通过 `OutlineRenderingApi.registerAdapter()` 注册自定义适配器，无需修改本体代码。

### 修复：GUI 与手持描边颜色不一致 (光影下显示淡灰色)

- **问题**: 开启 Iris 光影时，背包 GUI 物品描边为正确颜色，手持物品描边显示淡灰色/偏色。
- **根因**: 光影 fallback 下，手持扁平物品错误地走 `worldEmissiveOutlineRenderType()` (BLOCK_SHEET 路径)，物品贴图 RGB 混入描边颜色；而 GUI 描边走 `guiOutlineRenderType(tex)` (形状纹理路径)，颜色正确。
- **修复**: 移除 `renderHandOutline` 中扁平物品的 `ITEM_PIXEL_COLOR_GLINT` 条件限制，所有扁平物品在光影 fallback 下统一走 `renderFlatOutlineShape` 形状纹理路径，内部自动按 fallback 选择 `worldEmissiveOutlineRenderType(tex)`，保证与 GUI 描边颜色一致。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 799 行)

### 修复：Hazen 'N Stuff 武器附魔后错位/无描边

- **问题**: Hazen 'N Stuff 的"黑曜石巨剑"和"星怒"等 BEWLR 武器，附魔后描边错位或完全无描边。
- **根因**: Hazen 'N Stuff 使用 `HSNEnchantedItemStackRenderer` 作为 BEWLR 渲染器，但未登记到 `BEWLR_MODEL_HOLDER_CLASSES` 适配表 → 反射失败、模型获取为空、描边缺失。
- **修复**: 新增 `com.coderplus.hazennstuff.client.renderer.HNSEnchantedItemStackRenderer` 到 `BEWLR_MODEL_HOLDER_CLASSES` 数组，采用与永恒星光相同的 `scale(1,-1,-1)` 预变换规则。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 1695 行)

---

### 修复：TACZ 兼容错误 (轮廓错位 + 天上巨大模型)

- **问题**: TACZ (The Arsenal Collection Z) 武器附魔后描边轮廓错位、在天空中出现巨大几何模型。
- **根因**: `TaczAdapter.resolve()` 返回 `isLionfish=true`，误用 LionfishAPI 逐 cube 顶点法线外扩算法;但 TACZ 武器实际用 GeoLib/AnimateGeoItemRenderer 渲染，应走 Geo 骨骼贴图轮廓采样路径。Lionfish 算法用于 Geo → 顶点计算错乱 + 整体放大壳偏出本体 → 轮廓错位 + 空中巨大壳。
- **修复**: `TaczAdapter.resolve()` 改为返回 `isGeo=true, isLionfish=false`，使用 `GeoAdapter.INSTANCE` 处理，贴合 Geo 骨骼贴图轮廓。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java` (第 118-128 行 `resolve()` 方法)

### 修复：真菌感染 (Spore) 武器和盔甲无法渲染轮廓

- **问题**: 真菌感染 (Spore) 模组的 BEWLR 武器和盔甲附魔后无描边。
- **根因**: 真菌感染使用 `com.Harbinger.Spore.Client.SItemProperties` 作为 BEWLR 渲染器，但未登记到 `BEWLR_MODEL_HOLDER_CLASSES` 适配表 → 模型反射失败、描边缺失。
- **修复**: 新增 `com.Harbinger.Spore.Client.SItemProperties` 到 `BEWLR_MODEL_HOLDER_CLASSES` 数组，采用与永恒星光相同的 `scale(1,-1,-1)` 预变换规则。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 1701 行 `BEWLR_MODEL_HOLDER_CLASSES`)

### 修复：Hazen 'N Stuff 怒的武器无法渲染轮廓

- **问题**: Hazen 'N Stuff 的"怒的武器"系列 (新武器) 附魔后无描边。
- **根因**: 怒的武器使用 `cn.leolezury.hazennstuff.client.renderer.HSNEnchantedItemStackRenderer` 作为 BEWLR 渲染器，但该类名已存在于 `BEWLR_MODEL_HOLDER_CLASSES` 中;需确认该类名与怒的武器实际渲染器匹配。
- **修复**: 确认 `cn.leolezury.hazennstuff.client.renderer.HSNEnchantedItemStackRenderer` 已登记到 `BEWLR_MODEL_HOLDER_CLASSES`,怒的武器采用与黑曜石巨剑相同的 `scale(1,-1,-1)` 预变换规则。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 1700 行 `BEWLR_MODEL_HOLDER_CLASSES`)

### 支持的 BEWLR/Lionfish 模组列表

- **BEWLR 模组**:
  - 永恒星光 (Eternal Starlight): `cn.leolezury.eternalstarlight.common.client.renderer.ESItemStackRenderer`
  - 灾变 (Cataclysm): `com.github.L_Ender.cataclysm.client.render.CMItemstackRenderer` (LionfishAPI)
  - Hazen 'N Stuff: `cn.leolezury.hazennstuff.client.renderer.HSNEnchantedItemStackRenderer`
  - 真菌感染 (Spore): `com.Harbinger.Spore.Client.SItemProperties`
- **GeoLib/AzureLib 模组**:
  - TACZ (The Arsenal Collection Z): `TaczAdapter` → `GeoAdapter`
  - Hazen 'N Stuff 盔甲: `GeoRenderProvider` / `AzArmorRendererRegistry`
- **LionfishAPI 模组**:
  - 灾变武器 (部分 Lionfish 武器): `CMItemstackRenderer` → `LionfishAdapter`

### 优化：API 算法同步更新

- **内容**: 更新 TACZ 适配逻辑，确保与 API 库算法一致;TACZ 物品统一走 Geo 路径，不再误判为 Lionfish。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java` (适配器优先级判断)

---

## v0.1.8 (2026-08-16)

### 新增：GeckoLib 4 + AzureLib 物品与盔甲描边支持

- **背景**: Hazen 'N Stuff 等模组用 GeckoLib 4 / AzureLib(其 fork) 渲染武器/法杖/盔甲，
  骨骼层 (`GeoBone`/`AzBone` → `GeoCube` → `GeoQuad`/`GeoVertex`) 几乎同构 (仅包名不同)。
  旧 BEWLR 适配 (永恒星光/灾变静态字段) 拿不到它们的模型 → 这些模组的物品/盔甲
  <b>完全无描边</b>(盔甲会被误画成原版盔甲轮廓)。
- **物品 (武器/法杖/工具)**:
  - GeckoLib: `GeoRenderProvider.of(item).getGeoItemRenderer()` → `getGeoModel()` →
    `getBakedModel` → `BakedGeoModel.topLevelBones()`。⚠️ GeckoLib 物品的 `GeoItemRenderer`
    不通过 `IClientItemExtensions.getCustomRenderer()` 暴露，必须走 `GeoRenderProvider`;
  - AzureLib: `AzItemRendererRegistry.getOrNull` → `provider().provideBakedModel` →
    `AzBakedModel.topLevelBones()`;
  - 物品预变换 `translate(0.5, 0.51, 0.5)`(库内置，非模组自定义，无 scale 翻转)。
- **盔甲**:
  - AzureLib: `AzArmorRendererRegistry.getOrNull(stack)` → 反射调用原模组骨骼准备
    `grabRelevantBones` / `applyBaseTransformations` / `applyBoneVisibilityBySlot`
    (精确复刻本体，含 Hazen 自定义 `AzArmorLeggingTorsoLayerPipeline`)+ 全局变换
    `translate(0, 1.5, 0) + scale(-1,-1,1)`(缺了盔甲上下颠倒);
  - GeckoLib: `GeoRenderProvider.getGeoArmorRenderer` → 同上。GeckoLib 的
    `HumanoidArmorLayerMixin` 用 `@WrapWithCondition` 拦截 `renderArmorPiece` 并 cancel
    → 我们的 12 参 `renderArmorPiece` HEAD 不触发，改在 `render` HEAD 遍历 4 槽位处理;
  - 盔甲骨骼名约定:`armorHead`/`armorBody`/`armorRightArm`/.../`armorLeftBoot`
    (+ Hazen 的 `armorLeggingTorsoLayer`)。
- ⚠️ <b>JPMS 模块化</b>: 跨模块反射必须 `setAccessible(true)` —— 匿名类 (如 GeckoLib
  `GeoRenderProvider` 的匿名实现) 非 public，不 setAccessible 会抛
  `IllegalAccessException`(TrueNightsEdge 武器实测)。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
  - `src/main/java/com/enchantedoutlines/mod/mixin/HumanoidArmorLayerMixin.java`

### 修复：扁平物品描边渲染成平面正方形 (不遵循轮廓)

- **根因**: 扁平描边旧实现 `emitQuad` 直接把<b>模型 quad 的 UV</b> 交给着色器采样
  BLOCK_SHEET。原版扁平物品 (剑/工具) 烘焙后的 UV 是精灵在 atlas 的绝对 UV(采样正确);
  但<b>模组扁平模型</b>的 UV 常是 [0,1] 全图 → 采样越出精灵区域 → 整块 quad 全不透明
  → 描边变成平面正方形。
- **修复**: 新增 `renderFlatOutlineShape` —— CPU 读贴图 alpha 生成<b>形状纹理</b>
  (`shapeTextureFor`)+ 精灵相对 UV(`emitQuadShape`) 渲染，形状由贴图 alpha 决定、
  与模型 UV 无关;GUI/手持扁平描边统一走它。删除死代码 `emitQuad` /
  `renderFlatPureColorShape` / `outlineRenderType()`。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 修复：Geo 骨骼描边遵循贴图轮廓 (纯采样贴图)

- **设计**: 3D 骨骼盔甲/物品统一采样本体贴图 alpha 做形状 (纹理轮廓):<b>贴图有内容的部件
  描边</b>(剑形武器、sigil 符号、盔甲主体),<b>贴图全透明的部件无描边</b>
  (如柠檬神光环 spin，贴图区域 0/1024 像素不透明——贴图本身透明，没有轮廓可依)。
- **方案演进**: 纯几何 (扁平 cube 画成平面方形) → 采样贴图 (透明部件无描边) → 混合双渲染 (几何层破坏轮廓) → 几何边框线 (不贴合纹理轮廓) →
  <b>最终纯采样贴图</b>。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 其他
- 知识库：AGENTS.md 新增铁律 3.5(扁平物品描边必须用形状纹理，禁止模型 UV 采样
  BLOCK_SHEET)/ 3.6(Geo 骨骼描边采样本体贴图轮廓，贴图透明部件无描边);同步
  Cherry Studio 知识库 (合并为单一 AGENTS.md 文档)。

---

## v0.1.8 (2026-08-16, 重复条目已合并)

### 新增：物品黑白名单支持 `minecraft:*` 命名空间通配符

- **功能**: 白名单 `enabledItems` / 黑名单 `disabledItems` 支持通配符。
- **示例**: `minecraft:*` 匹配整个命名空间、`minecraft:di*` 匹配路径前缀。
- **过滤顺序**: 白名单在前，黑名单在后。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/ColorResolver.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`
  - `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json` / `en_us.json`

---

## v0.1.0 - v0.1.7 (历史版本)

- 基础描边功能实现
- BEWLR 物品支持 (永恒星光/灾变武器)
- LionfishAPI 逐 cube 外扩算法
- 光影兼容 (Iris/Oculus)
- 配置系统与颜色解析
- 性能优化 (几何缓存/反射缓存)
