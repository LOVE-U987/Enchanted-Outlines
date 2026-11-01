package com.enchantedoutlines.mod.outline;

import java.util.Map;

import com.enchantedoutlines.mod.config.Config;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * 附魔物品 → 描边颜色与厚度。
 * <p>
 * 解析优先级:
 * <ol>
 *   <li>物品被禁用 (程序化 {@code OutlineColorRegistry.disableItem} 或配置
 *       {@code disabledItems},支持通配符)→ 不描边 (-1)。<b>禁用在任何颜色之前</b>,
 *       即使开启 RPG 虹灯也生效 (程序化 disable 的 javadoc 承诺"即使带附魔也禁用")</li>
 *   <li>RPG 虹灯 (配置 {@code ENABLE_RPG_RAINBOW})→ 动态彩虹色 (仅对附魔物品,
 *       优先级高于物品固定色/附魔色;白名单不限制彩虹)</li>
 *   <li>物品不在白名单 (配置 {@code enabledItems})→ 不描边 (-1)</li>
 *   <li>资源包轮廓定义 ({@link OutlineResources};静态色或时间循环动画)→ 使用该色;
 *       优先于程序化/配置固定色(资源包是玩家直接可见可控的载体)。
 *       免附魔语义不变:定义只在物品已带附魔、未被禁用且在白名单时被查询到</li>
 *   <li>物品固定颜色 (程序化 &gt; 配置)→ 使用该色</li>
 *   <li>附魔取色 (程序化 &gt; 配置;mergeMode 决定 highest/first)→ 使用该色</li>
 *   <li>默认色兜底</li>
 * </ol>
 * 厚度：程序化逐物品厚度 &gt; 资源包定义厚度 &gt; 全局配置 thickness。
 * <p>
 * 1.21.1 附魔读取走 {@code DataComponents.ENCHANTMENTS}(keySet 为
 * {@code Holder<Enchantment>}),颜色按附魔的 ResourceLocation 匹配 → 模组附魔天然可用。
 */
public final class ColorResolver {

    private ColorResolver() {
    }

    /**
     * @return 描边 ARGB 颜色;无附魔或物品被禁用时返回 -1(不描边)
     */
    public static int resolve(ItemStack stack) {
        // 检查是否有附魔 (RPG 虹灯仅对附魔物品生效)
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (enchantments.isEmpty()) {
            return -1;
        }

        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            // ⚠️ 禁用检查必须在 RPG 虹灯之前:程序化 disableItem / 配置 disabledItems
            // 的语义是"该物品永不描边(即使带附魔)",开启彩虹时同样要生效 —— 曾因
            // 彩虹分支提前返回而穿透禁用(disable 被彩虹覆盖,违背 API 承诺)。
            if (OutlineColorRegistry.isItemDisabled(itemId) || Config.isItemDisabled(itemId)) {
                return -1;
            }
        }

        // 1. RPG 虹灯 (仅对附魔物品生效,优先级最高)
        if (Config.ENABLE_RPG_RAINBOW.get()) {
            return resolveRainbowColor(stack);
        }

        if (itemId != null) {
            // 2. 白名单 (不在列表内 → 不描边;白名单不限制彩虹,故放在彩虹之后)
            if (!Config.isItemEnabled(itemId)) {
                return -1;
            }
            // 3. 资源包轮廓定义 (资源包 > 程序化/配置固定色;动画定义每帧求值)
            Integer packColor = resourcePackColor(itemId);
            if (packColor != null) {
                return packColor;
            }
            // 4. 物品固定颜色 (程序化 > 配置)
            Integer itemColor = fixedItemColor(itemId);
            if (itemColor != null) {
                return itemColor;
            }
        }

        // 5. 附魔取色 (mergeMode: highest / first)
        int fallback = Config.defaultColorArgb();
        if (Config.isHighestMerge()) {
            int bestLevel = -1;
            int bestColor = fallback;
            for (Holder<Enchantment> holder : enchantments.keySet()) {
                int level = enchantments.getLevel(holder);
                if (level > bestLevel) {
                    bestLevel = level;
                    bestColor = colorFor(holder, fallback);
                }
            }
            return bestColor;
        }

        // first 模式：取列表首个附魔的颜色
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            return colorFor(holder, fallback);
        }
        return fallback;
    }

    /** 物品固定色 (程序化 > 配置);无固定色返回 null。调用方需先确认物品未被禁用/在白名单。 */
    private static Integer fixedItemColor(ResourceLocation itemId) {
        Integer itemColor = OutlineColorRegistry.itemColor(itemId);
        if (itemColor != null) {
            return itemColor;
        }
        return Config.itemColor(itemId);
    }

    /**
     * 资源包轮廓定义取色 (静态色或时间循环动画,动画按当前时间逐帧求值)。
     *
     * @param itemId 物品注册 id
     * @return 定义命中的描边色;未命中资源包定义返回 null
     */
    private static Integer resourcePackColor(ResourceLocation itemId) {
        OutlineDef packDef = OutlineResources.def(itemId);
        if (packDef == null) {
            return null;
        }
        return packDef.isAnimated() ? packDef.colorAt(System.currentTimeMillis()) : packDef.color();
    }

    /**
     * 资源包轮廓定义的逐物品厚度。
     *
     * @param itemId 物品注册 id
     * @return 定义中的厚度;未命中定义或定义未声明厚度返回 null
     */
    private static Float resourcePackThickness(ResourceLocation itemId) {
        OutlineDef packDef = OutlineResources.def(itemId);
        return packDef != null ? packDef.thickness() : null;
    }

    /**
     * 解析 RPG 虹灯颜色。
     * <p>
     * 基于物品 ID + 时间的 HSB 转 RGB 渐变，5 秒周期。颜色随时间<b>连续</b>变化
     * (HSBtoRGB 开销极小,每帧计算即可;旧实现"100ms 节流缓存"只写不读,
     * 是无效缓存,已移除 —— 彩虹本就该逐帧刷新)。
     */
    private static int resolveRainbowColor(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId == null) {
            return Config.defaultColorArgb();
        }
        long now = System.currentTimeMillis();
        // 5 秒周期，每个物品的 hue 偏移基于 hashCode
        long time = now % 5000;
        float hue = (time + itemId.hashCode()) / 5000f;
        // HSB 转 RGB: saturation=1.0, brightness=1.0
        int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f);
        return 0xFF000000 | rgb;
    }

    /**
     * 投掷物实体 (如飞行的 {@link net.minecraft.world.entity.projectile.ThrownTrident})
     * 的降级取色。
     * <p>
     * 客户端上 {@code ThrownTrident} 由 {@code (EntityType, Level)} 构造器创建，
     * {@code pickupItemStack} 字段固定为 {@code getDefaultPickupItem()}(无附魔三叉戟),
     * 且该字段<b>不随 SynchedEntityData 同步</b> → 客户端拿不到附魔列表，无法按附魔取色。
     * 唯一可靠线索是 {@code ThrownTrident.isFoil()}(ID_FOIL 布尔，spawn 时随
     * ClientboundSetEntityDataPacket 同步，原版渲染器也靠它画箔光)。
     * <p>
     * 解析退化为:物品禁用 → -1;资源包轮廓定义 → 该色/动画色;物品固定色
     * (程序化 &gt; 配置)→ 该色;否则默认色。
     * 有附魔与否的判断由调用方 (实体 mixin) 用 {@code isFoil()} 完成。
     */
    public static int resolveFoilOnly(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            if (OutlineColorRegistry.isItemDisabled(itemId) || Config.isItemDisabled(itemId)) {
                return -1;
            }
            if (!Config.isItemEnabled(itemId)) {
                return -1;
            }
            Integer packColor = resourcePackColor(itemId);
            if (packColor != null) {
                return packColor;
            }
            Integer itemColor = fixedItemColor(itemId);
            if (itemColor != null) {
                return itemColor;
            }
        }
        return Config.defaultColorArgb();
    }

    /** 物品描边厚度 (像素，可浮点);0 表示不画。程序化注册 > 资源包定义 > 配置映射 > 全局配置。 */
    public static float thickness(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            Float t = OutlineColorRegistry.itemThickness(itemId);
            if (t != null) {
                return t;
            }
            Float packThickness = resourcePackThickness(itemId);
            if (packThickness != null) {
                return packThickness;
            }
            t = Config.itemThickness(itemId);
            if (t != null) {
                return t;
            }
        }
        return Config.THICKNESS.get().floatValue();
    }

    /** 盔甲描边厚度 (像素，可浮点);0 表示不画。程序化注册 > 资源包定义 > 配置映射 > 全局配置。 */
    public static float armorThickness(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            Float t = OutlineColorRegistry.itemThickness(itemId);
            if (t != null) {
                return t;
            }
            Float packThickness = resourcePackThickness(itemId);
            if (packThickness != null) {
                return packThickness;
            }
            t = Config.itemThickness(itemId);
            if (t != null) {
                return t;
            }
        }
        return Config.ARMOR_THICKNESS.get().floatValue();
    }

    /**
     * 该物品的描边是否被强制为纯色 (逐物品覆盖)。
     * <p>
     * 程序化注册 ({@link OutlineColorRegistry#registerItemSolid}) > 配置
     * {@code solidColorItems}。不开光影时所有描边本就恒为纯色,本判断只在光影
     * 兼容模式的混色决策里被查询(逐物品覆盖:某物品即使全局开混色也恒纯色)。
     *
     * @param itemId 物品注册 ID(可为 null,无上下文时返回 false)
     * @return true = 该物品描边恒为纯色
     */
    public static boolean isSolidOutline(ResourceLocation itemId) {
        return itemId != null && (OutlineColorRegistry.isItemSolid(itemId) || Config.isItemSolid(itemId));
    }

    private static int colorFor(Holder<Enchantment> holder, int fallback) {
        var key = holder.unwrapKey();
        if (key.isEmpty()) {
            return fallback;
        }
        ResourceLocation id = key.get().location();
        Integer color = OutlineColorRegistry.enchantmentColor(id);
        if (color != null) {
            return color;
        }
        Map<ResourceLocation, Integer> map = Config.enchantColorMap();
        Integer c = map.get(id);
        if (c != null) {
            return c;
        }
        // 稀有度自动取色(2026-11-01):未在 enchantColors 配置的附魔按 weight 映射
        // 稀有度取 rarityColors;-1(该稀有度未配置/非法)回退默认色。
        if (Config.ENCHANT_RARITY_AUTO_COLOR.get()) {
            int rarity = Config.rarityColorArgb(rarityIndex(holder));
            if (rarity != -1) {
                return rarity;
            }
        }
        return fallback;
    }

    /**
     * 附魔稀有度索引(2026-11-01,weight 越小越稀有)。
     * <p>
     * 1.21.1 的 Enchantment 数据驱动化后无独立 Rarity 字段,
     * {@code definition().weight()} 即原版 JSON 的 {@code weight}(常见度权重,
     * 值越大越常见)。映射与附魔稀有度直觉对齐:锋利/保护(10)= common,
     * 时运/精准采集(2)= rare,无穷/引雷(1)= epic。
     *
     * @param holder 附魔 holder
     * @return 稀有度索引(0=common,1=uncommon,2=rare,3=epic)
     */
    private static int rarityIndex(Holder<Enchantment> holder) {
        int weight = holder.value().definition().weight();
        if (weight <= 1) {
            return 3; // EPIC
        }
        if (weight == 2) {
            return 2; // RARE
        }
        if (weight <= 4) {
            return 1; // UNCOMMON
        }
        return 0; // COMMON
    }

    private static ResourceLocation itemId(ItemStack stack) {
        return stack.getItemHolder().unwrapKey().map(key -> key.location()).orElse(null);
    }
}
