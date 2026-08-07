# Enchanted Outlines

一个 NeoForge **1.21.1** 客户端模组：为背包、快捷栏、容器等 GUI 以及手持、掉落物、穿戴盔甲、鞘翅、投掷三叉戟等场景中**带有附魔的物品**渲染沿物品形状的**彩色纯色轮廓描边**，按附魔分色。灵感来自 Modrinth 资源包 [Enchantment Outlines](https://modrinth.com/resourcepack/glowing-glints)。

## 特性

- **按附魔分色**：锐利金色、火焰橙红、精准采集冰蓝……颜色可在配置界面逐附魔自定义；未配置的附魔用默认色兜底。
- **绘制时程序化生成**：用自定义核心着色器把物品模型的 alpha 遮罩以纯描边色沿 8 个方向偏移渲染，**对任意模组物品自动生效**，无需预画贴图、零烘焙、零像素读回、零缓存内存。
- **全渲染上下文覆盖**：背包 / 快捷栏 / 容器 GUI、第一/第三人称手持、掉落物、物品展示框、穿戴盔甲、穿戴鞘翅、投掷中的三叉戟，均按附魔渲染描边；GUI 3D 物品与手持立体物品用顶点法线平均外扩，描边贴合表面、厚度均匀。
- **BEWLR 近似模型**：盾牌、三叉戟、望远镜等程序化渲染（BEWLR）物品用盒模型近似描边，并跟踪 `blocking` 举盾 / `throwing` 投掷等 display 变换避免错位；钓鱼竿、地图等占位模型无形状，暂不描边。
- **动画与变体天然正确**：时钟、指南针、NBT 变体、损伤模型每帧解析同一模型，描边形状实时跟随。
- **可扩展**：模组附魔/物品 id 可直接写进配置；另提供公开 Java API（`OutlineColorRegistry`）与扩展事件（`OutlineColorEvent`）供其他模组开发者注册规则。
- **原生配置界面**：原版 `Screen` 实现，深色 + 琥珀金主题，禁用模糊背景，分类导航 + 滚动 + 即时保存。
- **纯客户端**：仅客户端逻辑，无服务器端内容，可加入任何多人服务器。

## 安装

- 需要 NeoForge 21.1.x（1.21.1）。
- 将构建出的 `enchanted_outlines-<version>.jar` 放入 `mods/` 文件夹。

## 开发环境

```
./gradlew runClient   # 启动客户端测试
./gradlew build       # 构建发布 jar
```

## 配置

游戏内 Mod 列表 → Enchanted Outlines → 配置，或直接编辑
`run/config/enchanted_outlines-common.toml`。

| 配置项 | 说明 |
|---|---|
| `enable` | 总开关 |
| `thickness` | 物品描边超出贴图的像素数（0–8 支持小数，默认 1；实际偏移 = 值 × 0.5） |
| `armorThickness` | 穿戴盔甲 / 鞘翅描边超出模型的像素数（0–8 支持小数，默认 2） |
| `mergeMode` | 多附魔取色：`highest`（最高等级）/ `first`（列表首个） |
| `defaultColor` | 未配置附魔的默认描边色（RRGGBB） |
| `enchantColors` | 逐附魔颜色，`id=RRGGBB` 逗号分隔（含模组附魔） |
| `itemColors` | 逐物品颜色，`itemid=RRGGBB` 逗号分隔，覆盖该物品的附魔取色（含模组物品） |
| `disabledItems` | 永不描边的物品 id，逗号分隔 |

## 扩展 API（其他模组开发者）

```java
NeoForge.EVENT_BUS.addListener(OutlineColorEvent.class, event -> {
    OutlineColorRegistry.registerEnchantmentColor(
            ResourceLocation.fromNamespaceAndPath("mymod", "my_enchant"), 0xFFFFA500);
    OutlineColorRegistry.registerItemColor(
            ResourceLocation.fromNamespaceAndPath("mymod", "magic_sword"), 0xFF00FF00);
    OutlineColorRegistry.registerItemThickness(
            ResourceLocation.fromNamespaceAndPath("mymod", "big_axe"), 3);
    OutlineColorRegistry.disableItem(
            ResourceLocation.fromNamespaceAndPath("mymod", "special_item"));
});
```

程序化注册的规则优先于配置文件；`registerItemThickness` 注册的逐物品厚度同样作用于盔甲 / 鞘翅（覆盖 `armorThickness`）。

## 已知限制

- 投掷三叉戟的颜色判断退化：客户端拿不到投掷物的附魔列表（`pickupItemStack` 不随实体数据同步），只能按 `isFoil()` 判断是否有附魔，颜色取物品固定色 / 默认色，无法按附魔分色。
- 钓鱼竿、地图等 BEWLR 占位模型无几何形状，不描边；盾牌、三叉戟、望远镜使用近似盒模型描边，轮廓为近似形状而非逐像素。

## 构建说明

- 当前版本 v0.1.1；Java 21，NeoForge 21.1.219，ModDevGradle 2.0.140。
- Mixin 由 NeoForge 自动处理，无需额外 gradle 插件。
- Mixin 钩子：GUI（`GuiGraphicsMixin`）、手持/掉落物/展示框（`ItemRendererMixin`）、穿戴盔甲（`HumanoidArmorLayerMixin`）、穿戴鞘翅（`ElytraLayerMixin` + `ElytraModelAccessor`）、投掷三叉戟（`ThrownTridentRendererMixin`）。
