# Enchanted Outlines Changelog

---

## v0.1.6 (2026-08-10) — 1.20.1 Forge 移植版

### 新增: 完整移植到 1.20.1 Forge(与 1.21.1 NeoForge 版功能一致)

- 平台: Minecraft 1.20.1 + Forge 47.4.10(ForgeGradle 6.x + Java 17),与 1.21.1
  NeoForge 版(<code>移植项目/</code> 目录保留原版参考)同版本号 0.1.6。
- 构建: <code>org.spongepowered.mixin</code> 0.7.38(MixinGradle 未发布到
  Gradle Plugin Portal,经 <code>repo.spongepowered.org</code> buildscript 加载;
  1.20.1 Forge 运行时是 SRG,mixin 必须经 refmap 映射,已由 MixinGradle 生成
  <code>enchanted_outlines.refmap.json</code>);settings.gradle 增补阿里云镜像
  加速插件/依赖下载。
- 全部 API 迁移(1.21.1 NeoForge → 1.20.1 Forge,编译期验证 + 打包成功):
  - 事件总线/包名: <code>net.neoforged.*</code> → <code>net.minecraftforge.*</code>;
    <code>NeoForge.EVENT_BUS</code> → <code>MinecraftForge.EVENT_BUS</code>。
  - 配置: <code>ModConfigSpec</code> → <code>ForgeConfigSpec</code>;
    <code>ModContainer.registerConfig</code>(1.21.x 独有)→
    <code>ModLoadingContext.get().registerConfig</code>(1.20.1 唯一标准方式,
    被 Forge 标记待移除,已 <code>@SuppressWarnings</code>);
    保存经 <code>ModConfig.save()</code>。
  - 附魔读取: <code>DataComponents.ENCHANTMENTS</code>/<code>ItemEnchantments</code>
    (1.21.x 数据组件)→ <code>EnchantmentHelper.getEnchantments</code>
    (<code>Map&lt;Enchantment, Integer&gt;</code>);
    附魔/物品 ID 经 <code>BuiltInRegistries.ENCHANTMENT/ITEM.getKey</code>。
  - 资源定位: <code>ResourceLocation.fromNamespaceAndPath/withDefaultNamespace/parse</code>
    (Forge 1.20.1 patch 已提供,非 deprecated,与原代码风格一致)。
  - 客户端入口: <code>@Mod(dist = Dist.CLIENT)</code>(1.21.x)→
    <code>@OnlyIn(Dist.CLIENT)</code> 标注客户端类(服务器端剥离);
    配置界面扩展点 <code>IConfigScreenFactory</code> →
    <code>ConfigScreenHandler.ConfigScreenFactory</code>(registerExtensionPoint
    第二参为无参 Supplier)。
  - 盔甲 hook: <code>ClientHooks.getArmorModel/getArmorTexture</code>(1.21.x)→
    <code>ForgeHooksClient.getArmorModel</code>(返回 Model)+
    <code>HumanoidArmorLayer.getArmorResource</code>(public,含纹理 hook);
    1.20.1 的 <code>renderArmorPiece</code> 是 6 参(1.21.1 为 12 参);
    <code>ArmorMaterial</code> 1.20.1 是接口(无 layers)。
  - 渲染 API: <code>BakedModel.applyTransform</code>(1.21.x 独有)→ 复刻本体
    <code>getTransforms().getTransform(ctx).apply → translate(-0.5)</code>;
    <code>VertexConsumer</code> 11 参 <code>addVertex(int color)</code>(1.21.x)→
    14 参 <code>vertex(x,y,z,r,g,b,a,u,v,overlay,light,nx,ny,nz)</code>
    (<code>vertexFull</code> 辅助);<code>ModelPart.Cube.compile</code> 5 参
    → 8 参(颜色拆 4 个 float);<code>RenderStateShard</code> 常量 1.20.1 是
    protected → 新建 <code>RenderTypeAccess extends RenderType</code> 转发;
    <code>MultiBufferSource.immediate(ByteBufferBuilder)</code> →
    <code>immediate(BufferBuilder)</code>。
  - mixin: <code>ThrownTrident</code> 无 <code>getPickupItemStackOrigin()</code>
    (1.21.x)→ 新增 <code>ThrownTridentAccessor</code> 读私有 <code>tridentItem</code>;
    <code>GuiGraphics.mouseScrolled</code> 4 参 → 3 参;删除 1.21.x 独有的
    <code>Screen.renderBlurredBackground</code>(1.20.1 本就无模糊背景)。
  - mods.toml: 1.20.1 格式(loaderVersion <code>[47,)</code>、依赖用
    <code>mandatory</code>、无 <code>[[mixins]]</code> 段,Forge 自动发现
    <code>*.mixins.json</code>);硬编码(不参与 Groovy 模板展开,规避模板引擎
    对 UTF-8 中文词法解析失败)。
  - <b>pack.mcmeta</b>(pack_format=15): 1.20.1 dev 环境 mod 资源作为资源包
    加载,缺 mcmeta 会被跳过 → 着色器/语言文件加载不到(实测修复前
    "Failed to load the outline shader: FileNotFoundException
    enchanted_outlines:shaders/core/outline.json")。
- 运行验证(2026-08-10 实跑): 1.20.1 Forge 47.4.10 启动成功,mod 实例创建、
  配置生成、outline 着色器注册、mixin 应用全部通过,正常进入单机世界。
- 1.20.1 行为差异说明: <code>forge:separate_transforms</code> 的 Baked 模型
  (1.20.1)getQuads/isCustomRenderer 委托 base(几何非空)→ 描边无需子模型切换,
  与本体取同一模型即一致(1.21.1 NeoForge 版需 applyTransform 切子模型)。
- 影响文件: 全部 <code>src/main/java</code> 与 <code>src/main/resources</code>
  (从 1.21.1 源码整体移植改写);<code>build.gradle</code>/<code>gradle.properties</code>/
  <code>settings.gradle</code> 重建为 1.20.1 Forge 配置;新增
  <code>RenderTypeAccess.java</code>、<code>ThrownTridentAccessor.java</code>。

---

## v0.1.6 (2026-08-09)

### 修复: 灾变武器描边兼容性(映射表 + 预变换分类)

- 问题: 240 模组整合包里灾变<b>大部分武器没有描边</b>(只有掣雷巨锤正常);
  虚空突击肩炮、沙暴之怒等轮廓被渲染到<b>玩家身后</b>不可见。
- 根因一(字段名不匹配 → 无描边): 灾变 `CMItemstackRenderer` 的静态模型字段名
  <b>不遵循</b>统一的 `<物品ID大写>_MODEL` 约定:
  - `wither_assault_shoulder_weapon` / `void_assault_shoulder_weapon` 共用缩写
    `WASW_MODEL`(而不是 `WITHER_/VOID_ASSAULT_SHOULDER_WEAPON_MODEL`);
  - `wrath_of_the_desert`(沙暴之怒)= `WRATH_OF_DESERT_MODEL`(比注册 ID 少 `_THE_`);
  - `soul_render`(断魂战戟)/ `the_annihilator`(歼灭战锤)= `SOUL_RENDER` /
    `THE_ANNIHILATOR`(<b>无</b> `_MODEL` 后缀)。
  反射按约定名找不到字段 → `findBewlrModel` 返回 null → 描边完全缺失。
- 根因二(预变换一刀切 → 描边偏移/身后): 所有灾变物品统一按
  `translate(0.5,0.5,0.5)+scale(1,-1,-1)` 复刻预变换,但实测字节码
  (Cataclysm 1.21.1-3.32)珊瑚矛/珊瑚钺/黑钢圆盾/蔚蓝海石盾分支<b>只有</b>
  `scale(1,-1,-1)`(无居中平移)→ 描边凭空多出 0.5 偏移,错位到本体后方。
- 修复:
  - `OutlineRenderer` 新增灾变武器<b>精确适配表</b>
    `CATACLYSM_WEAPON_SPECS`(物品注册 ID → 模型字段名 + 纹理字段名 + 预变换
    类别),覆盖 5 个特殊字段名 + 4 个纯翻转分支;未列入的武器(掣雷巨锤
    brontes 等)仍走通用 `<ID>_MODEL` 约定 + 居中翻转,兼容后续版本。
  - `BewlrModel` 新增两类预变换:`CM_FLIP`(纯 `scale(1,-1,-1)`,珊瑚矛等)与
    `CM_CENTER_FLIP_TOP`(`translate(0.5,1.5,0.5)+scale(1,-1,-1)`,EMP/祭坛/
    铁砧等方块类物品;按模型类包名 `.model.block.` 自动修正,顺带修复
    方块类 BEWLR 物品描边 Y 偏移 1 格)。
  - 纹理字段读取: 映射表显式指定(`SOUL_RENDER` 无 `_MODEL` 后缀,通用
    `replace` 换不出 `_TEXTURE`,必须显式)。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 修复: 兼容 `neoforge:separate_transforms` 多视角模型(资源包把 3D 改 2D)

- 问题: 玩家整合包加载第三方社区资源包(如 XIMStudio)后,灾变武器在背包里
  从 3D 变 2D、部分 3D 模型被替换 → 描边完全不显示。
- 根因: 资源包用 `neoforge:separate_transforms` loader 把物品拆成多视角子模型
  (GUI/FIXED = 2D 平面 `item/generated`,手持 = 3D `builtin/entity` base)。
  其烘焙模型 `SeparateTransformsModel.Baked` 有三个陷阱:
  1. `isCustomRenderer()` <b>恒返回 false</b>(不委托 base)→ 旧描边永远不触发
     BEWLR 3D 分支;
  2. `getQuads()` 返回 <b>base(builtin/entity 占位)的 quads = 空几何</b> →
     描边无顶点可画 → 完全无描边;
  3. `getTransforms()` 返回 `NO_TRANSFORMS` → 变换链断裂。
  而<b>本体</b> `ItemRenderer.render` 渲染前会 `model.applyTransform(context)` 按
  视角切换子模型 → 本体正常显示、描边拿不到几何。违反"本体怎么渲染,描边就
  怎么渲染"铁律。
- 修复: `ItemRendererMixin` / `GuiGraphicsMixin` 在描边前也调用
  `model.applyTransform(context, pose, leftHand)` 拿到<b>与本体相同的视角子模型</b>:
  - GUI/FIXED → 2D 平面子模型 → 走平面描边(与本体图标对齐);
  - 手持 → 3D base → 走 BEWLR 3D 放大壳(与本体手持对齐)。
  普通模型 `applyTransform` 默认实现 = 原 `getTransforms().getTransform().apply()`,
  行为不变;`isCustomRenderer()` 判断基于子模型。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/mixin/ItemRendererMixin.java`
  - `src/main/java/com/enchantedoutlines/mod/mixin/GuiGraphicsMixin.java`

