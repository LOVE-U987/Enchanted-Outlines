package com.enchantedoutlines.mod.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Enchanted Outlines 配置定义 (COMMON 类型)。
 * <p>
 * 颜色以十六进制字符串 "RRGGBB" 存储，便于玩家直接编辑 TOML;
 * 运行时通过 {@link #defaultColorArgb()} 等取解析后的 ARGB。
 * 模组物品/模组附魔的 id 可直接写入配置，无需写代码。
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ==================== 通用 ====================

    /** 总开关 */
    public static final ModConfigSpec.BooleanValue ENABLE = BUILDER
            .comment("Master switch for outline rendering.",
                    "关闭后任何界面都不再渲染描边。")
            .define("enable", true);

    /** 物品描边厚度 (名义像素，支持小数;实际偏移 = 值 × 0.5，因贴图边缘渐变像素也会计入视觉宽度)。GUI / 手持 / 投掷物通用。 */
    public static final ModConfigSpec.DoubleValue THICKNESS = BUILDER
            .comment("Outline thickness for items in pixels beyond the item sprite (0-8, decimal allowed).",
                    "物品 (GUI/手持/投掷物) 描边超出物品贴图的像素数，支持小数，0 关闭描边扩张。",
                    "实际偏移 = 值 × 0.5(贴图边缘渐变像素也会计入视觉宽度，1 即约半像素细线)。")
            .defineInRange("thickness", 1.0, 0.0, 8.0);

    /** 盔甲描边厚度 (名义像素，支持小数;实际偏移 = 值 × 0.5)。 */
    public static final ModConfigSpec.DoubleValue ARMOR_THICKNESS = BUILDER
            .comment("Outline thickness for worn armor in pixels beyond the model (0-8, decimal allowed).",
                    "盔甲描边超出模型的像素数，支持小数，0 关闭盔甲描边扩张。")
            .defineInRange("armorThickness", 8.0, 0.0, 20.0);

    /** 多附魔取色模式 */
    public static final ModConfigSpec.ConfigValue<String> MERGE_MODE = BUILDER
            .comment("Color selection when an item has multiple enchantments: highest | first",
                    "多附魔时以哪个附魔颜色为准:highest(最高等级) 或 first(列表首个)。")
            .define("mergeMode", "highest");

    /**
     * 附魔光效是否根据物品每个像素的颜色确定 (光影兼容模式)。
     */
    public static final ModConfigSpec.BooleanValue ITEM_PIXEL_COLOR_GLINT = BUILDER
            .comment("Mix outline color with each item pixel color (shader-compat mode only).",
                    "附魔光效是否根据物品每个像素的颜色确定 (仅光影兼容模式下生效):",
                    "true = 描边色与物品贴图像素颜色混合 (扁平物品与 3D 物品均生效，保留物品形状，颜色混合后独特有趣);",
                    "false = 描边为纯色:扁平物品仍保留物品形状 (需读取贴图 alpha，首次渲染该物品有一次性的小开销),3D 物品形状由几何决定。",
                    "盾牌/三叉戟近似盒模型恒为纯色。")
            .define("itemPixelColorGlint", true);

    /**
     * 盔甲/鞘翅/投掷物实体的描边是否与纹理颜色混合 (光影兼容模式)。
     */
    public static final ModConfigSpec.BooleanValue ARMOR_PIXEL_COLOR_GLINT = BUILDER
            .comment("Mix armor outline color with the armor texture (shader-compat mode only).",
                    "盔甲/鞘翅/投掷物描边是否与纹理颜色混合 (仅光影兼容模式下生效):",
                    "true = 采样原纹理 alpha 遮罩 (贴合单层纹理轮廓，颜色与纹理混合);",
                    "false = 描边为纯描边色 (形状由模型几何决定)。")
            .define("armorPixelColorGlint", true);

    /**
     * 盔甲描边是否按<b>固定厚度</b>均匀外扩 (默认 true)。
     */
    public static final ModConfigSpec.BooleanValue ARMOR_UNIFORM_EXPAND = BUILDER
            .comment("Uniform armor outline thickness (per-cube self-adaptive scale).",
                    "盔甲描边是否按固定厚度均匀外扩:",
                    "true = 每个盔甲部件按自身尺寸自适应放大，模组细分模型 (如热泉石盔甲) 描边与普通盔甲一样厚 (默认);",
                    "false = 固定放大系数 (旧算法，小部件描边偏薄)。")
            .define("armorUniformExpand", true);

    /**
     * 关闭混色 (纯色描边) 时是否按物体颜色降低曝光亮度 (仅光影兼容模式下生效)。
     */
    public static final ModConfigSpec.BooleanValue OUTLINE_EXPOSURE_REDUCE = BUILDER
            .comment("Reduce pure-color outline exposure by the item color (shader-compat mode only).",
                    "关闭'附魔光效混合物品颜色'时，描边为纯色且 emissive 全亮，亮色描边在明亮场景下会过曝刺眼。",
                    "true = 纯色描边按物品贴图平均亮度压暗 (暗色物品的描边显著变暗，亮色物品基本不变),色相不变;",
                    "false = 纯色描边保持原始亮度。")
            .define("outlineExposureReduce", true);

    // ==================== BEWLR 3D 物品描边 ====================

    /** BEWLR 3D 物品描边放大系数。 */
    public static final ModConfigSpec.DoubleValue BEWLR_3D_SCALE = BUILDER
            .comment("Outline inflate scale for BEWLR 3D items (enchanted custom-model weapons).",
                    "BEWLR 3D 物品 (模组自定义模型武器，如月弧长枪/灾变武器) 的描边放大系数。",
                    "3D 描边是几何放大壳，外扩量 = 系数×厚度×点到中心距离;细长武器需要更大的基础系数才明显。",
                    "0.1-1.0 之间调节，越大描边越粗。")
            .defineInRange("bewlr3dScale", 0.1, 0.05, 1.0);

    /** BEWLR 3D 描边是否用<b>逐 cube 顶点法线外扩</b>(默认)。 */
    public static final ModConfigSpec.BooleanValue BEWLR_3D_PER_CUBE = BUILDER
            .comment("Use per-cube vertex-normal expansion for BEWLR 3D outlines (default true).",
                    "BEWLR 3D 描边算法:true = 逐 cube 顶点法线外扩 (等厚贴身，端部不膨胀，默认);",
                    "false = 整体包围盒放大壳 (旧算法，细长武器端部外扩偏多)。")
            .define("bewlr3dPerCube", true);

    /** 是否启用 Hazen'N Stuff 全武器兼容描边模式 (默认开启)。 */
    public static final ModConfigSpec.BooleanValue ENABLE_HAZEN_COMPAT = BUILDER
            .comment("Enable Hazen'N Stuff full-weapon outline compatibility (default true).",
                    "Hazen'H Stuff (hazennstuff:) 全武器描边兼容模式:",
                    "true = 对其 BEWLR/Geo 武器 (星怒等 GeckoLib 模型) 启用描边 —— 先走贴图 alpha",
                    "采样轮廓 (形状由本体贴图决定, 扁平 quad 不会膨胀成整块平面), 拿不到再回退",
                    "渲染捕获兜底, 覆盖任意模型类型的相关武器;",
                    "false = 保持旧行为, 该物品武器一律不描边 (宁可无描边, 不渲染错位轮廓).",
                    "普通扁平物品与 AzureLib 盔甲不受本开关影响。")
            .define("enableHazenCompat", true);

    /** 是否启用 TACZ(The Arsenal Collection Z) 枪械描边兼容模式 (默认开启)。 */
    public static final ModConfigSpec.BooleanValue ENABLE_TACZ_COMPAT = BUILDER
            .comment("Enable TACZ gun outline compatibility (default true).",
                    "TACZ 枪械描边兼容模式:",
                    "true = 对 TACZ 武器启用描边 —— GUI/背包/手持/掉落物等上下文走 BEWLR 渲染捕获兜底,",
                    "绕捕获到的几何外扩一圈壳; 若某姿态下捕获出现错位可临时关闭此项。",
                    "false = 保持旧行为, TACZ 物品一律不描边 (宁可无描边, 不渲染错位轮廓).",
                    "普通扁平物品与 AzureLib 盔甲不受本开关影响。")
            .define("enableTaczCompat", false);

    // ==================== RPG 虹灯 ====================

    /** 是否启用 RPG 虹灯 (动态彩虹描边)——优先级最高。 */
    public static final ModConfigSpec.BooleanValue ENABLE_RPG_RAINBOW = BUILDER
            .comment("Enable RPG rainbow effect (dynamic rainbow outlines).",
                    "启用 RPG 虹灯效果 (动态彩虹描边):",
                    "true = 描边颜色随时间动态变化，形成彩虹效果 (优先级最高，覆盖所有其他颜色配置);",
                    "false = 使用正常颜色配置。")
            .define("enableRPGRainbow", false);

    // ==================== 颜色 ====================

    /** 默认描边色 */
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_COLOR = BUILDER
            .comment("Default outline color as hex RGB (RRGGBB).",
                    "未单独配置颜色的附魔所用的默认描边色。")
            .define("defaultColor", "FFC0CB");

    /** 逐附魔颜色映射 */
    public static final ModConfigSpec.ConfigValue<String> ENCHANT_COLORS = BUILDER
            .comment("Per-enchantment colors as id=RRGGBB, comma separated.",
                    "逐附魔颜色:id=RRGGBB，逗号分隔;未列出的附魔使用默认颜色。")
            .define("enchantColors",
                    "minecraft:sharpness=FFD700,minecraft:fire_aspect=FF6A00,"
                            + "minecraft:silk_touch=F0F8FF,minecraft:efficiency=F5DEB3,"
                            + "minecraft:protection=87CEEB,minecraft:unbreaking=9ACD32,"
                            + "minecraft:mending=9FE2BF,minecraft:fortune=FFD700,"
                            + "minecraft:looting=FFB6C1,minecraft:smite=FFE4B5");

    /** 逐物品颜色映射 (覆盖附魔取色，含模组物品) */
    public static final ModConfigSpec.ConfigValue<String> ITEM_COLORS = BUILDER
            .comment("Per-item outline colors as itemid=RRGGBB, comma separated.",
                    "逐物品描边颜色:itemid=RRGGBB，逗号分隔;覆盖该物品的附魔取色 (含模组物品)。")
            .define("itemColors", "");

    /** 白名单：只对列表内的物品描边 (默认空 = 全部物品)。 */
    public static final ModConfigSpec.ConfigValue<String> ENABLED_ITEMS = BUILDER
            .comment("Comma separated item ids that may get an outline; empty = all items.",
                    "白名单：逗号分隔的物品 id，只有列表内的物品才会描边;为空 = 全部物品 (默认)。",
                    "支持通配符:'minecraft:*' 匹配 minecraft 命名空间下所有物品，如 'minecraft:di*' 匹配以 di 开头的物品。",
                    "黑名单 (disabledItems) 在白名单之后过滤。")
            .define("enabledItems", "");

    /** 永不描边的物品 id(支持 minecraft:* 通配符) */
    public static final ModConfigSpec.ConfigValue<String> DISABLED_ITEMS = BUILDER
            .comment("Comma separated item ids that never get an outline; supports 'minecraft:*' wildcards.",
                    "永不描边的物品 id，逗号分隔;支持通配符：如 'minecraft:*' 禁用整个 minecraft 命名空间，",
                    "'minecraft:di*' 禁用路径以 di 开头的物品。")
            .define("disabledItems", "");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 由主类在 {@code ModConfigEvent} 中捕获，用于配置界面即时写盘。 */
    public static ModConfig MOD_CONFIG;

    /** 立即把当前配置值保存到磁盘 (配置界面每次修改后调用)。 */
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
    private static volatile List<Pattern> cachedEnabledPatterns = null;
    private static volatile List<Pattern> cachedDisabledPatterns = null;

    private Config() {
    }

    /** 配置重载后调用，清空解析缓存。 */
    public static void invalidateCache() {
        cachedDefaultColor = null;
        cachedEnchantColors = null;
        cachedItemColors = null;
        cachedEnabledPatterns = null;
        cachedDisabledPatterns = null;
    }

    /** 默认描边色 (ARGB，完全不透明)。非法值时退回青绿色。 */
    public static int defaultColorArgb() {
        Integer c = cachedDefaultColor;
        if (c == null) {
            int rgb = parseHex(DEFAULT_COLOR.get());
            c = (rgb < 0 ? 0x55FFFF : rgb) | 0xFF000000;
            cachedDefaultColor = c;
        }
        return c;
    }

    /** 附魔颜色映射 (ResourceLocation → ARGB)。 */
    public static Map<ResourceLocation, Integer> enchantColorMap() {
        Map<ResourceLocation, Integer> m = cachedEnchantColors;
        if (m == null) {
            m = parseColorMap(ENCHANT_COLORS.get());
            cachedEnchantColors = m;
        }
        return m;
    }

    /** 物品颜色映射 (ResourceLocation → ARGB),未配置返回空表。 */
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

    /** 物品是否允许描边 (白名单)。 */
    public static boolean isItemEnabled(ResourceLocation item) {
        List<Pattern> list = cachedEnabledPatterns;
        if (list == null) {
            list = parseIdPatterns(ENABLED_ITEMS.get());
            cachedEnabledPatterns = list;
        }
        return list.isEmpty() || matchesAny(list, item);
    }

    /** 物品是否被配置禁用描边 (黑名单，支持 minecraft:* 通配符)。 */
    public static boolean isItemDisabled(ResourceLocation item) {
        List<Pattern> list = cachedDisabledPatterns;
        if (list == null) {
            list = parseIdPatterns(DISABLED_ITEMS.get());
            cachedDisabledPatterns = list;
        }
        return matchesAny(list, item);
    }

    public static boolean isHighestMerge() {
        return "highest".equalsIgnoreCase(MERGE_MODE.get());
    }

    // ==================== 解析 ====================

    /** 解析 id=RRGGBB 逗号分隔映射 (附魔/物品通用)。 */
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

    /** 解析逗号分隔的物品 id / 通配符列表为匹配模式。 */
    private static List<Pattern> parseIdPatterns(String raw) {
        List<Pattern> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        for (String part : raw.split(",")) {
            String seg = part.trim();
            if (seg.isEmpty()) {
                continue;
            }
            list.add(compilePattern(seg));
        }
        return list;
    }

    /** 把单个 id/通配符条目编译为正则 (^...$，* → .*)。 */
    private static Pattern compilePattern(String seg) {
        StringBuilder sb = new StringBuilder("^");
        int start = 0;
        for (int i = 0; i < seg.length(); i++) {
            if (seg.charAt(i) == '*') {
                sb.append(Pattern.quote(seg.substring(start, i))).append(".*");
                start = i + 1;
            }
        }
        sb.append(Pattern.quote(seg.substring(start))).append("$");
        return Pattern.compile(sb.toString());
    }

    /** 物品 id 是否匹配模式列表中的任意一个。 */
    private static boolean matchesAny(List<Pattern> patterns, ResourceLocation item) {
        String s = item.toString();
        for (Pattern p : patterns) {
            if (p.matcher(s).matches()) {
                return true;
            }
        }
        return false;
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
