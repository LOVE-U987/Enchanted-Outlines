package com.enchantedoutlines.mod.outline;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/**
 * Enchanted Outlines 公开扩展 API(供其他模组注册描边规则)。
 * <p>
 * 静态注册表,任意模组可在客户端初始化阶段直接调用;或在
 * {@link OutlineColorEvent} 事件中批量注册(推荐,时序有保证)。
 * <p>
 * 程序化注册的规则<b>优先于配置文件</b>(便于模组覆盖玩家的 TOML 设置)。
 * 模组物品/模组附魔的 id 直接可用,无需任何适配。
 */
public final class OutlineColorRegistry {

    private static final Map<ResourceLocation, Integer> ENCHANT_COLORS = new HashMap<>();
    private static final Map<ResourceLocation, Integer> ITEM_COLORS = new HashMap<>();
    private static final Map<ResourceLocation, Float> ITEM_THICKNESS = new HashMap<>();
    private static final Set<ResourceLocation> DISABLED_ITEMS = new HashSet<>();

    private OutlineColorRegistry() {
    }

    /** 为某个附魔(id,含模组附魔)注册描边颜色,优先于配置里的 enchantColors。 */
    public static void registerEnchantmentColor(ResourceLocation enchantment, int argb) {
        ENCHANT_COLORS.put(enchantment, argb | 0xFF000000);
    }

    /** 为某个物品(id,含模组物品)固定描边颜色,覆盖该物品的附魔取色。 */
    public static void registerItemColor(ResourceLocation item, int argb) {
        ITEM_COLORS.put(item, argb | 0xFF000000);
    }

    /** 为某个物品单独设置描边厚度(像素,可浮点),覆盖全局 thickness 配置。 */
    public static void registerItemThickness(ResourceLocation item, float pixels) {
        ITEM_THICKNESS.put(item, Math.max(0f, pixels));
    }

    /** 禁用某个物品的描边(即使它带有附魔)。 */
    public static void disableItem(ResourceLocation item) {
        DISABLED_ITEMS.add(item);
    }

    // ==================== 内部查询(包内可见,由 ColorResolver 调用) ====================

    static Integer enchantmentColor(ResourceLocation enchantment) {
        return ENCHANT_COLORS.get(enchantment);
    }

    static Integer itemColor(ResourceLocation item) {
        return ITEM_COLORS.get(item);
    }

    static Float itemThickness(ResourceLocation item) {
        return ITEM_THICKNESS.get(item);
    }

    static boolean isItemDisabled(ResourceLocation item) {
        return DISABLED_ITEMS.contains(item);
    }
}
