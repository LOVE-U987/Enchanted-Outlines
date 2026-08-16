# Enchanted Outlines Changelog

---

## v0.1.9 (2026-08-16)

### 新增: GeckoLib 4 + AzureLib 物品与盔甲描边支持

- 背景: Hazen 'N Stuff 等模组用 GeckoLib 4 / AzureLib(其 fork)渲染武器/法杖/盔甲,
  骨骼层(`GeoBone`/`AzBone` → `GeoCube` → `GeoQuad`/`GeoVertex`)几乎同构(仅包名不同)。
  旧 BEWLR 适配(永恒星光/灾变静态字段)拿不到它们的模型 → 这些模组的物品/盔甲
  <b>完全无描边</b>(盔甲会被误画成原版盔甲轮廓)。
- 物品(武器/法杖/工具):
  - GeckoLib: `GeoRenderProvider.of(item).getGeoItemRenderer()` → `getGeoModel()` →
    `getBakedModel` → `BakedGeoModel.topLevelBones()`。⚠️ GeckoLib 物品的 `GeoItemRenderer`
    不通过 `IClientItemExtensions.getCustomRenderer()` 暴露,必须走 `GeoRenderProvider`;
  - AzureLib: `AzItemRendererRegistry.getOrNull` → `provider().provideBakedModel` →
    `AzBakedModel.topLevelBones()`;
  - 物品预变换 `translate(0.5, 0.51, 0.5)`(库内置,非模组自定义,无 scale 翻转)。
- 盔甲:
  - AzureLib: `AzArmorRendererRegistry.getOrNull(stack)` → 反射调用原模组骨骼准备
    `grabRelevantBones` / `applyBaseTransformations` / `applyBoneVisibilityBySlot`
    (精确复刻本体,含 Hazen 自定义 `AzArmorLeggingTorsoLayerPipeline`)+ 全局变换
    `translate(0, 1.5, 0) + scale(-1,-1,1)`(缺了盔甲上下颠倒);
  - GeckoLib: `GeoRenderProvider.getGeoArmorRenderer` → 同上。GeckoLib 的
    `HumanoidArmorLayerMixin` 用 `@WrapWithCondition` 拦截 `renderArmorPiece` 并 cancel
    → 我们的 12 参 `renderArmorPiece` HEAD 不触发,改在 `render` HEAD 遍历 4 槽位处理;
  - 盔甲骨骼名约定:`armorHead`/`armorBody`/`armorRightArm`/.../`armorLeftBoot`
    (+ Hazen 的 `armorLeggingTorsoLayer`)。
- ⚠️ JPMS 模块化: 跨模块反射必须 `setAccessible(true)` —— 匿名类(如 GeckoLib
  `GeoRenderProvider` 的匿名实现)非 public,不 setAccessible 会抛
  `IllegalAccessException`(TrueNightsEdge 武器实测)。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
  - `src/main/java/com/enchantedoutlines/mod/mixin/HumanoidArmorLayerMixin.java`

### 修复: 扁平物品描边渲染成平面正方形(不遵循轮廓)

- 根因: 扁平描边旧实现 `emitQuad` 直接把<b>模型 quad 的 UV</b> 交给着色器采样
  BLOCK_SHEET。原版扁平物品(剑/工具)烘焙后的 UV 是精灵在 atlas 的绝对 UV(采样正确);
  但<b>模组扁平模型</b>的 UV 常是 [0,1] 全图 → 采样越出精灵区域 → 整块 quad 全不透明
  → 描边变成平面正方形。
- 修复: 新增 `renderFlatOutlineShape` —— CPU 读贴图 alpha 生成<b>形状纹理</b>
  (`shapeTextureFor`)+ 精灵相对 UV(`emitQuadShape`)渲染,形状由贴图 alpha 决定、
  与模型 UV 无关;GUI/手持扁平描边统一走它。删除死代码 `emitQuad` /
  `renderFlatPureColorShape` / `outlineRenderType()`。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 修复: Geo 骨骼描边遵循贴图轮廓(纯采样贴图)

- 3D 骨骼盔甲/物品统一采样本体贴图 alpha 做形状(纹理轮廓):<b>贴图有内容的部件
  描边</b>(剑形武器、sigil 符号、盔甲主体),<b>贴图全透明的部件无描边</b>
  (如柠檬神光环 spin,贴图区域 0/1024 像素不透明——贴图本身透明,没有轮廓可依)。
- 方案演进(均试过并回退): 纯几何(扁平 cube 画成平面方形)→ 采样贴图(透明部件无
  描边)→ 混合双渲染(几何层破坏轮廓)→ 几何边框线(不贴合纹理轮廓)→
  <b>最终纯采样贴图</b>。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 其他
- 知识库: AGENTS.md 新增铁律 3.5(扁平物品描边必须用形状纹理,禁止模型 UV 采样
  BLOCK_SHEET)/ 3.6(Geo 骨骼描边采样本体贴图轮廓,贴图透明部件无描边);同步
  Cherry Studio 知识库(合并为单一 AGENTS.md 文档)。

---

## v0.1.8 (2026-08-16)

### 新增: 物品黑白名单支持 `minecraft:*` 命名空间通配符

- 新增白名单配置 `enabledItems`(TOML 键,配置界面「颜色」分类新增一行):
  - 逗号分隔的物品 id,只有列表内的物品才会描边;为空 = 全部物品(默认,行为不变);
  - 支持通配符:如 `minecraft:*` 匹配 minecraft 命名空间下所有物品、`minecraft:di*`
    匹配路径以 `di` 开头的物品;不含 `*` 的条目按精确 id 匹配(向后兼容)。
- 黑名单 `disabledItems` 升级:同样支持 `minecraft:*` / `minecraft:di*` 通配符,
  原精确 id 语义不变。
- 过滤顺序:白名单在前(不在白名单直接不描边),黑名单在后(白名单放行的再被黑名单
  剔除);程序化注册的禁用/取色(`OutlineColorRegistry`)优先级不变。
- 接入点:`ColorResolver.resolve` / `resolveFoilOnly`(所有描边入口——GUI/手持/盔甲/
  鞘翅/投掷物——都汇聚于此),新实现按 `*` 通配符编译正则缓存,热路径零重复编译。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`(`enabledItems` 配置项、
    `isItemEnabled`、黑/白名单通配符解析)
  - `src/main/java/com/enchantedoutlines/mod/outline/ColorResolver.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`
  - `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json` / `en_us.json`

---
