# Enchanted Outlines 扩展 API

本文档面向**外部模组开发者**:如何为你的附魔/物品注册描边颜色、厚度与禁用规则。

## 概述

本模组对外公开的扩展点收敛在 `com.enchantedoutlines.mod.outline` 包:
- `OutlineColorRegistry`:静态注册表(任意时刻可调用,建议在事件中批量注册);
- `OutlineColorEvent`:注册时机事件(客户端启动时于游戏事件总线触发一次,时序有保证)。

> 历史遗留的 `com.enchantedoutlines.mod.api` 适配层(OutlineRenderingApi / ShapeDetector /
> RenderPipeline 等 20+ 文件)经核查为**零引用的死代码**,已于重构中删除,请勿再引用。

## 快速开始

### 1. 在事件中注册(推荐,时序有保证)

客户端启动时(`FMLClientSetupEvent` 后)本模组会在 NeoForge **游戏事件总线**触发一次
`OutlineColorEvent`,供其他模组注册描边规则。事件本身不带负载,直接调用
`OutlineColorRegistry` 的静态方法即可:

```java
import com.enchantedoutlines.mod.outline.OutlineColorEvent;
import com.enchantedoutlines.mod.outline.OutlineColorRegistry;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public class MyModClient {
    public MyModClient() {
        NeoForge.EVENT_BUS.addListener(OutlineColorEvent.class, event -> {
            OutlineColorRegistry.registerItemColor(
                    ResourceLocation.fromNamespaceAndPath("mymod", "magic_sword"), 0xFF00FF00);
            OutlineColorRegistry.disableItem(
                    ResourceLocation.fromNamespaceAndPath("mymod", "special_item"));
        });
    }
}
```

### 2. 直接调用注册表(任意客户端初始化阶段)

```java
// 为某个附魔(id,含模组附魔)注册描边颜色,优先于配置里的 enchantColors
OutlineColorRegistry.registerEnchantmentColor(
        ResourceLocation.fromNamespaceAndPath("mymod", "magic"), 0xFF00FF00);

// 为某个物品(id,含模组物品)固定描边颜色,覆盖该物品的附魔取色
OutlineColorRegistry.registerItemColor(
        ResourceLocation.fromNamespaceAndPath("mymod", "magic_sword"), 0xFF00FF00);

// 为某个物品单独设置描边厚度(像素,可浮点),覆盖全局 thickness 配置
OutlineColorRegistry.registerItemThickness(
        ResourceLocation.fromNamespaceAndPath("mymod", "magic_sword"), 2.5f);

// 禁用某个物品的描边(即使它带有附魔)
OutlineColorRegistry.disableItem(
        ResourceLocation.fromNamespaceAndPath("mymod", "special_item"));
```

## 颜色解析优先级(供参考)

1. RPG 虹灯(配置 `enableRPGRainbow`,仅对附魔物品生效,优先级最高);
2. 物品不在白名单(配置,支持 `minecraft:*` 通配符)或已被禁用 → 不描边;
3. 物品固定色:程序化(`OutlineColorRegistry`)> 配置(`itemColors`);
4. 附魔取色:程序化 > 配置(`enchantColors`;`mergeMode` 决定 highest/first);
5. 默认色(`defaultColor`)兜底。

程序化注册的规则**优先于配置文件**,便于模组覆盖玩家的 TOML 设置;
模组物品/模组附魔的 id 直接可用,无需任何适配。

## 渲染兼容性说明

描边覆盖 GUI(背包/容器/快捷栏)、手持(第一/第三人称)、掉落物、物品展示框、
玩家穿戴盔甲、鞘翅与投掷三叉戟。渲染实现全部位于
`com.enchantedoutlines.mod.outline.OutlineRenderer`,不对外暴露;

已知主动停用描边的物品族(用户决策,宁可无描边也不接受错位轮廓):
- TACZ 枪械(`tacz:` / `thearsenalcollection:` 命名空间,自绘 Bedrock 渲染);
- Hazen'N Stuff 的 Geo/BEWLR 物品(`hazennstuff:` 命名空间,如星怒,GeckoLib GeoItem)。

若你的模组物品没有描边,请先确认:带附魔?未被白名单过滤?不属于上述停用族?
对于自绘 BEWLR 渲染器(无静态模型字段、不属于 GeckoLib/AzureLib/Lionfish 体系),
通用"渲染捕获"兜底可能因强依赖渲染上下文而失败,属已知边界。
