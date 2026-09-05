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
 *   <li>RPG 虹灯 (配置 {@code ENABLE_RPG_RAINBOW},优先级最高)→ 动态彩虹色</li>
 *   <li>物品不在白名单 (配置，支持 {@code minecraft:*} 通配符)→ 不描边 (-1)</li>
 *   <li>物品被禁用 (程序化或配置)→ 不描边 (-1)</li>
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

    /** 物品 → 最后计算时间 (独立于全局，避免冲突)。 */
    private static final java.util.Map<ResourceLocation, Long> RAINBOW_CACHE = new java.util.HashMap<>();

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

        // 1. RPG 虹灯 (优先级最高，仅对附魔物品生效)
        if (Config.ENABLE_RPG_RAINBOW.get()) {
            return resolveRainbowColor(stack);
        }

        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            if (!Config.isItemEnabled(itemId)
                    || OutlineColorRegistry.isItemDisabled(itemId) || Config.isItemDisabled(itemId)) {
                return -1;
            }
            Integer itemColor = OutlineColorRegistry.itemColor(itemId);
            if (itemColor != null) {
                return itemColor;
            }
            Integer configItemColor = Config.itemColor(itemId);
            if (configItemColor != null) {
                return configItemColor;
            }
        }

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

    /**
     * 解析 RPG 虹灯颜色。
     * <p>
     * 基于物品 ID + 时间的 HSB 转 RGB 渐变，5 秒周期。
     */
    private static int resolveRainbowColor(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId == null) {
            return Config.defaultColorArgb();
        }

        long now = System.currentTimeMillis();
        Long lastCalc = RAINBOW_CACHE.get(itemId);

        // 每 100ms 更新一次 (避免每帧计算)
        if (lastCalc == null || now - lastCalc > 100) {
            RAINBOW_CACHE.put(itemId, now);
        }

        // 5 秒周期，每个物品的 hue 偏移基于 hashCode
        long time = now % 5000;
        float hue = (time + stack.getItem().hashCode()) / 5000f;

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
            if (!Config.isItemEnabled(itemId)
                    || OutlineColorRegistry.isItemDisabled(itemId) || Config.isItemDisabled(itemId)) {
                return -1;
            }
            Integer itemColor = OutlineColorRegistry.itemColor(itemId);
            if (itemColor != null) {
                return itemColor;
            }
            Integer configItemColor = Config.itemColor(itemId);
            if (configItemColor != null) {
                return configItemColor;
            }
        }
        return Config.defaultColorArgb();
    }

    /** 物品描边厚度 (像素，可浮点);0 表示不画。 */
    public static float thickness(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            Float t = OutlineColorRegistry.itemThickness(itemId);
            if (t != null) {
                return t;
            }
        }
        return Config.THICKNESS.get().floatValue();
    }

    /** 盔甲描边厚度 (像素，可浮点);0 表示不画。 */
    public static float armorThickness(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            Float t = OutlineColorRegistry.itemThickness(itemId);
            if (t != null) {
                return t;
            }
        }
        return Config.ARMOR_THICKNESS.get().floatValue();
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

    /** 清空缓存 (资源重载/进出世界时调用)。 */
    public static void invalidateCache() {
        RAINBOW_CACHE.clear();
    }
}
