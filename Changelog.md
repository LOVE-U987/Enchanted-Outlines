# Enchanted Outlines Changelog

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

## v0.1.7 (2026-08-12)

### 修复: 手持描边跟随 Better Combat 战斗动画(issue #2)

- 问题: 使用 Better Combat(含 Punchy)时,攻击动画期间附魔描边<b>不跟随武器的
  挥砍动画</b>——描边停留在原版静态位置,与动画中的武器本体分离。
- 根因: Better Combat 用 PlayerAnimator 驱动攻击动画,玩家手持物品的本体渲染
  经过 `ItemInHandLayer.renderArmWithItem` → `ItemInHandRenderer.renderItem`,
  攻击动画变换(scale/translate/rotate)由 PlayerAnimator 在 `renderItem` 调用
  <b>之前</b>应用到 PoseStack。旧描边在 `ItemRenderer.render`(8 参)HEAD 注入,
  当 PlayerAnimator 版本/路径差异(第一人称 THIRD_PERSON_MODEL 模式:取消原版
  第一人称、改由玩家实体 + PlayerItemInHandLayer 渲染)时,描边看到的 pose 与
  本体不一致 → 留在静态位置。
- 修复: 新增 `ItemInHandRendererMixin`,把<b>玩家手持描边提前到
  `ItemInHandRenderer.renderItem` HEAD</b>——该注入点 pose 与本体完全一致
  (已含 Better Combat 动画变换),描边先画垫底、本体随后覆盖中心,与本体
  100% 同步;同时用静态标记让 `ItemRendererMixin` 跳过同一物品在 8 参 render
  HEAD 的重复描边(避免"静态描边 + 动画描边"同时出现)。
- 覆盖范围: 第一人称(原版 ItemInHandRenderer 与 PlayerAnimator
  PlayerItemInHandLayer 都汇聚到此)与第三人称(ItemInHandLayer);掉落物/展示框
  (GROUND/FIXED)不经 ItemInHandRenderer,仍由 ItemRendererMixin 处理。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/mixin/ItemInHandRendererMixin.java`(新增)
  - `src/main/java/com/enchantedoutlines/mod/mixin/ItemRendererMixin.java`
  - `src/main/resources/enchanted_outlines.mixins.json`

### 修复: 多人游戏退出后重进渲染严重错误(issue #1)

- 问题: 加入服务器第一次渲染正常,退出客户端/服务器后重进渲染严重错误;
  需每次进客户端先进一次单人世界才恢复。
- 根因: 渲染缓存未随"资源重载/进出世界"清理——进入服务器(尤其带服务器
  资源包)会触发资源重载,TextureManager 销毁全部动态纹理,但 `worldOutlineRenderTypes`
  /`armorRenderTypes` 等 RenderType 缓存与 `shieldModel`/`tridentModel` 近似模型
  缓存<b>从不清理</b> → 旧缓存引用已失效资源 → 描边渲染成错误状态。进单人
  世界恰好触发 RegisterShadersEvent 清理部分缓存才"恢复"。
- 修复:
  - `OutlineRenderer` 新增统一入口 `invalidateCaches()`,清空全部缓存
    (形状纹理/亮度/RenderType/近似模型/几何缓存);
  - `setOutlineShader`(资源重载回调)改用 `invalidateCaches()`;
  - `EnchantedOutlinesClient` 监听 NeoForge `LevelEvent.Load`(进世界)与
    `ClientPlayerNetworkEvent.LoggingOut`(离开服务器),进出世界时也清空缓存,
    覆盖"无资源包服务器直连不触发资源重载"的场景。
- 影响文件:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
  - `src/main/java/com/enchantedoutlines/mod/EnchantedOutlinesClient.java`

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

