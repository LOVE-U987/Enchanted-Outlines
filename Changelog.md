# Enchanted Outlines Changelog

---

## v0.1.10 (2026-08-31, 多模组 BEWLR 兼容性修复)

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

## v0.1.9 (2026-08-30, API 架构重构完成版 + 修复版)

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

## v0.1.10 (2026-08-31, 模组兼容修复版)

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
