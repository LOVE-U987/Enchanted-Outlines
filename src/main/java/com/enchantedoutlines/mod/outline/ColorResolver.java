package com.enchantedoutlines.mod.outline;

import java.util.Map;

import com.enchantedoutlines.mod.config.Config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 附魔物品 → 描边颜色与厚度。
 * <p>
 * 解析优先级:
 * <ol>
 *   <li>物品被禁用(程序化或配置)→ 不描边(-1)</li>
 *   <li>物品固定颜色(程序化 &gt; 配置)→ 使用该色</li>
 *   <li>附魔取色(程序化 &gt; 配置;mergeMode 决定 highest/first)→ 使用该色</li>
 *   <li>默认色兜底</li>
 * </ol>
 * 厚度:程序化逐物品厚度 &gt; 全局配置 thickness。
 * <p>
 * 1.20.1 附魔读取走 {@code EnchantmentHelper.getEnchantments(ItemStack)}
 * (返回 {@code Map<Enchantment, Integer>}),颜色按附魔的 ResourceLocation
 * ({@code BuiltInRegistries.ENCHANTMENT.getKey})匹配 → 模组附魔天然可用。
 */
public final class ColorResolver {

    private ColorResolver() {
    }

    /**
     * @return 描边 ARGB 颜色;无附魔或物品被禁用时返回 -1(不描边)
     */
    public static int resolve(ItemStack stack) {
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
        if (enchantments.isEmpty()) {
            return -1;
        }

        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            if (OutlineColorRegistry.isItemDisabled(itemId) || Config.isItemDisabled(itemId)) {
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
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                int level = entry.getValue();
                if (level > bestLevel) {
                    bestLevel = level;
                    bestColor = colorFor(entry.getKey(), fallback);
                }
            }
            return bestColor;
        }

        // first 模式:取列表首个附魔的颜色
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            return colorFor(entry.getKey(), fallback);
        }
        return fallback;
    }

    /**
     * 投掷物实体(如飞行的 {@link net.minecraft.world.entity.projectile.ThrownTrident})
     * 的降级取色。
     * <p>
     * 客户端上 {@code ThrownTrident} 由 {@code (EntityType, Level)} 构造器创建,
     * {@code pickupItemStack} 字段固定为 {@code getDefaultPickupItem()}(无附魔三叉戟),
     * 且该字段<b>不随 SynchedEntityData 同步</b> → 客户端拿不到附魔列表,无法按附魔取色。
     * 唯一可靠线索是 {@code ThrownTrident.isFoil()}(ID_FOIL 布尔,spawn 时随
     * ClientboundSetEntityDataPacket 同步,原版渲染器也靠它画箔光)。
     * <p>
     * 解析退化为:物品禁用 → -1;物品固定色(程序化 &gt; 配置)→ 该色;否则默认色。
     * 有附魔与否的判断由调用方(实体 mixin)用 {@code isFoil()} 完成。
     */
    public static int resolveFoilOnly(ItemStack stack) {
        ResourceLocation itemId = itemId(stack);
        if (itemId != null) {
            if (OutlineColorRegistry.isItemDisabled(itemId) || Config.isItemDisabled(itemId)) {
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

    /** 物品描边厚度(像素,可浮点);0 表示不画。 */
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

    /** 盔甲描边厚度(像素,可浮点);0 表示不画。 */
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

    /**
     * 取附魔的颜色(程序化注册优先于配置;未配置回退默认色)。
     *
     * @param enchantment 附魔对象(1.20.1 直接是 Enchantment,无 Holder)
     * @param fallback    默认描边色
     */
    private static int colorFor(Enchantment enchantment, int fallback) {
        ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(enchantment);
        if (id == null) {
            return fallback;
        }
        Integer color = OutlineColorRegistry.enchantmentColor(id);
        if (color != null) {
            return color;
        }
        Map<ResourceLocation, Integer> map = Config.enchantColorMap();
        Integer c = map.get(id);
        return c != null ? c : fallback;
    }

    /** 物品注册 ID(1.20.1 经 {@link BuiltInRegistries#ITEM},无 Holder);无注册项返回 null。 */
    private static ResourceLocation itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }
}
