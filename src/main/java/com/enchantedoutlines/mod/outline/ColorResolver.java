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
 *   <li>物品固定颜色 (程序化 &gt; 配置)→ 使用该色</li>
 *   <li>附魔取色 (程序化 &gt; 配置;mergeMode 决定 highest/first)→ 使用该色</li>
 *   <li>默认色兜底</li>
 * </ol>
 * 厚度：程序化逐物品厚度 &gt; 全局配置 thickness。
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
            // 3. 物品固定颜色 (程序化 > 配置)
            Integer itemColor = fixedItemColor(itemId);
            if (itemColor != null) {
                return itemColor;
            }
        }

        // 4. 附魔取色 (mergeMode: highest / first)
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
     * 解析退化为:物品禁用 → -1;物品固定色 (程序化 &gt; 配置)→ 该色;否则默认色。
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
            Integer itemColor = fixedItemColor(itemId);
            if (itemColor != null) {
                return itemColor;
            }
        }
        return Config.defaultColorArgb();
    }

    /** 物品描边厚度 (像素，可浮点);0 表示不画。程序化注册 > 配置映射 > 全局配置。 */
    public static float thickness(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            Float t = OutlineColorRegistry.itemThickness(itemId);
            if (t != null) {
                return t;
            }
            t = Config.itemThickness(itemId);
            if (t != null) {
                return t;
            }
        }
        return Config.THICKNESS.get().floatValue();
    }

    /** 盔甲描边厚度 (像素，可浮点);0 表示不画。程序化注册 > 配置映射 > 全局配置。 */
    public static float armorThickness(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            Float t = OutlineColorRegistry.itemThickness(itemId);
            if (t != null) {
                return t;
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
        return c != null ? c : fallback;
    }

    private static ResourceLocation itemId(ItemStack stack) {
        return stack.getItemHolder().unwrapKey().map(key -> key.location()).orElse(null);
    }
}
