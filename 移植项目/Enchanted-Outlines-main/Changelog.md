# Enchanted Outlines Changelog

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

