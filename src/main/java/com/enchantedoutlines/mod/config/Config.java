package com.enchantedoutlines.mod.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Enchanted Outlines 配置定义(COMMON 类型)。
 * <p>
 * 颜色以十六进制字符串 "RRGGBB" 存储,便于玩家直接编辑 TOML;
 * 运行时通过 {@link #defaultColorArgb()} 等取解析后的 ARGB。
 * 模组物品/模组附魔的 id 可直接写入配置,无需写代码。
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ==================== 通用 ====================

    /** 总开关 */
    public static final ModConfigSpec.BooleanValue ENABLE = BUILDER
            .comment("Master switch for outline rendering.",
                    "关闭后任何界面都不再渲染描边。")
            .define("enable", true);

    /** 物品描边厚度(名义像素,支持小数;实际偏移 = 值 × 0.5,因贴图边缘渐变像素也会计入视觉宽度)。GUI / 手持 / 投掷物通用。 */
    public static final ModConfigSpec.DoubleValue THICKNESS = BUILDER
            .comment("Outline thickness for items in pixels beyond the item sprite (0-8, decimal allowed).",
                    "物品(GUI/手持/投掷物)描边超出物品贴图的像素数,支持小数,0 关闭描边扩张。",
                    "实际偏移 = 值 × 0.5(贴图边缘渐变像素也会计入视觉宽度,1 即约半像素细线)。")
            .defineInRange("thickness", 1.0, 0.0, 8.0);

    /** 盔甲描边厚度(名义像素,支持小数;实际偏移 = 值 × 0.5)。 */
    public static final ModConfigSpec.DoubleValue ARMOR_THICKNESS = BUILDER
            .comment("Outline thickness for worn armor in pixels beyond the model (0-8, decimal allowed).",
                    "盔甲描边超出模型的像素数,支持小数,0 关闭盔甲描边扩张。")
            .defineInRange("armorThickness", 2.0, 0.0, 8.0);

    /** 多附魔取色模式 */
    public static final ModConfigSpec.ConfigValue<String> MERGE_MODE = BUILDER
            .comment("Color selection when an item has multiple enchantments: highest | first",
                    "多附魔时以哪个附魔颜色为准:highest(最高等级)或 first(列表首个)。")
            .define("mergeMode", "highest");

    // ==================== 颜色 ====================

    /** 默认描边色 */
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_COLOR = BUILDER
            .comment("Default outline color as hex RGB (RRGGBB).",
                    "未单独配置颜色的附魔所用的默认描边色。")
            .define("defaultColor", "55FFFF");

    /** 逐附魔颜色映射 */
    public static final ModConfigSpec.ConfigValue<String> ENCHANT_COLORS = BUILDER
            .comment("Per-enchantment colors as id=RRGGBB, comma separated.",
                    "逐附魔颜色:id=RRGGBB,逗号分隔;未列出的附魔使用默认颜色。")
            .define("enchantColors",
                    "minecraft:sharpness=FFD700,minecraft:fire_aspect=FF6A00,"
                            + "minecraft:silk_touch=F0F8FF,minecraft:efficiency=F5DEB3,"
                            + "minecraft:protection=87CEEB,minecraft:unbreaking=9ACD32,"
                            + "minecraft:mending=9FE2BF,minecraft:fortune=FFD700,"
                            + "minecraft:looting=FFB6C1,minecraft:smite=FFE4B5");

    /** 逐物品颜色映射(覆盖附魔取色,含模组物品) */
    public static final ModConfigSpec.ConfigValue<String> ITEM_COLORS = BUILDER
            .comment("Per-item outline colors as itemid=RRGGBB, comma separated.",
                    "逐物品描边颜色:itemid=RRGGBB,逗号分隔;覆盖该物品的附魔取色(含模组物品)。")
            .define("itemColors", "");

    /** 永不描边的物品 id */
    public static final ModConfigSpec.ConfigValue<String> DISABLED_ITEMS = BUILDER
            .comment("Comma separated item ids that never get an outline.",
                    "永不描边的物品 id,逗号分隔。")
            .define("disabledItems", "");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 由主类在 {@code ModConfigEvent} 中捕获,用于配置界面即时写盘。 */
    public static ModConfig MOD_CONFIG;

    /** 立即把当前配置值保存到磁盘(配置界面每次修改后调用)。 */
    public static void save() {
        if (MOD_CONFIG != null) {
            try {
                MOD_CONFIG.getLoadedConfig().save();
            } catch (Exception e) {
                LOGGER.error("Failed to save config", e);
            }
        }
    }

    // ==================== 解析缓存 ====================

    private static volatile Integer cachedDefaultColor = null;
    private static volatile Map<ResourceLocation, Integer> cachedEnchantColors = null;
    private static volatile Map<ResourceLocation, Integer> cachedItemColors = null;
    private static volatile Set<ResourceLocation> cachedDisabledItems = null;

    private Config() {
    }

    /** 配置重载后调用,清空解析缓存。 */
    public static void invalidateCache() {
        cachedDefaultColor = null;
        cachedEnchantColors = null;
        cachedItemColors = null;
        cachedDisabledItems = null;
    }

    /** 默认描边色(ARGB,完全不透明)。非法值时退回青绿色。 */
    public static int defaultColorArgb() {
        Integer c = cachedDefaultColor;
        if (c == null) {
            int rgb = parseHex(DEFAULT_COLOR.get());
            c = (rgb < 0 ? 0x55FFFF : rgb) | 0xFF000000;
            cachedDefaultColor = c;
        }
        return c;
    }

    /** 附魔颜色映射(ResourceLocation → ARGB)。 */
    public static Map<ResourceLocation, Integer> enchantColorMap() {
        Map<ResourceLocation, Integer> m = cachedEnchantColors;
        if (m == null) {
            m = parseColorMap(ENCHANT_COLORS.get());
            cachedEnchantColors = m;
        }
        return m;
    }

    /** 物品颜色映射(ResourceLocation → ARGB),未配置返回空表。 */
    public static Map<ResourceLocation, Integer> itemColorMap() {
        Map<ResourceLocation, Integer> m = cachedItemColors;
        if (m == null) {
            m = parseColorMap(ITEM_COLORS.get());
            cachedItemColors = m;
        }
        return m;
    }

    /** 物品描边颜色;未配置返回 null。 */
    public static Integer itemColor(ResourceLocation item) {
        return itemColorMap().get(item);
    }

    /** 物品是否被配置禁用描边。 */
    public static boolean isItemDisabled(ResourceLocation item) {
        Set<ResourceLocation> set = cachedDisabledItems;
        if (set == null) {
            set = parseIdList(DISABLED_ITEMS.get());
            cachedDisabledItems = set;
        }
        return set.contains(item);
    }

    public static boolean isHighestMerge() {
        return "highest".equalsIgnoreCase(MERGE_MODE.get());
    }

    // ==================== 解析 ====================

    /** 解析 id=RRGGBB 逗号分隔映射(附魔/物品通用)。 */
    private static Map<ResourceLocation, Integer> parseColorMap(String raw) {
        Map<ResourceLocation, Integer> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String part : raw.split(",")) {
            String seg = part.trim();
            if (seg.isEmpty()) {
                continue;
            }
            int eq = seg.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            ResourceLocation id;
            try {
                id = ResourceLocation.parse(seg.substring(0, eq).trim());
            } catch (RuntimeException ignored) {
                continue;
            }
            int rgb = parseHex(seg.substring(eq + 1).trim());
            if (rgb < 0) {
                continue;
            }
            map.put(id, 0xFF000000 | rgb);
        }
        return map;
    }

    /** 解析逗号分隔的物品 id 列表。 */
    private static Set<ResourceLocation> parseIdList(String raw) {
        Set<ResourceLocation> set = new HashSet<>();
        if (raw == null) {
            return set;
        }
        for (String part : raw.split(",")) {
            String seg = part.trim();
            if (seg.isEmpty()) {
                continue;
            }
            try {
                set.add(ResourceLocation.parse(seg));
            } catch (RuntimeException ignored) {
            }
        }
        return set;
    }

    /** 解析 RRGGBB(容错 # 前缀与 3 位简写)。非法返回 -1。 */
    public static int parseHex(String hex) {
        if (hex == null) {
            return -1;
        }
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        if (h.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
