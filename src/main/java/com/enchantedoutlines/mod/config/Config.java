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
            .defineInRange("thickness", 2.0, 0.0, 8.0);

    /** 盔甲描边厚度(名义像素,支持小数;实际偏移 = 值 × 0.5)。 */
    public static final ModConfigSpec.DoubleValue ARMOR_THICKNESS = BUILDER
            .comment("Outline thickness for worn armor in pixels beyond the model (0-8, decimal allowed).",
                    "盔甲描边超出模型的像素数,支持小数,0 关闭盔甲描边扩张。")
            .defineInRange("armorThickness", 4.0, 0.0, 8.0);

    /** 多附魔取色模式 */
    public static final ModConfigSpec.ConfigValue<String> MERGE_MODE = BUILDER
            .comment("Color selection when an item has multiple enchantments: highest | first",
                    "多附魔时以哪个附魔颜色为准:highest(最高等级)或 first(列表首个)。")
            .define("mergeMode", "highest");

    /**
     * 附魔光效是否根据物品每个像素的颜色确定(光影兼容模式)。
     * <p>
     * 开启光影且 Iris 默认配置(不允许未知 shader)时,描边只能采样物品贴图
     * (BLOCK_SHEET)来获得形状 → 内置 fsh 的 {@code 描边色 × 物品贴图像素色}
     * 会让描边色与物品本身的颜色混合(如红色描边在钻石剑上呈黄绿色)。
     * 这是"形状与颜色共用同一张贴图"的硬限制。
     * <ul>
     *   <li>true(默认):保留形状(贴合物品轮廓),接受颜色混合 —— 混合出的颜色
     *       往往独特有趣。对扁平物品(剑/弓)与 3D 物品(方块等)都生效;</li>
     *   <li>false:描边为纯描边色 —— 扁平物品由 CPU 读取贴图 alpha 生成
     *       "描边色形状纹理"保留物品轮廓;3D 物品形状由几何决定本就保留。</li>
     * </ul>
     * 盾牌/三叉戟近似盒模型恒为纯色(盒模型无贴图遮罩,采样方块图集会被光影
     * 误判为方块材质而反射)。无光影 / 已开启 Iris 的 allowUnknownShaders 时,
     * 自定义 shader 可分离形状与颜色,描边始终为纯色,本配置不影响。
     */
    public static final ModConfigSpec.BooleanValue ITEM_PIXEL_COLOR_GLINT = BUILDER
            .comment("Mix outline color with each item pixel color (shader-compat mode only).",
                    "附魔光效是否根据物品每个像素的颜色确定(仅光影兼容模式下生效):",
                    "true = 描边色与物品贴图像素颜色混合(扁平物品与 3D 物品均生效,保留物品形状,颜色混合后独特有趣);",
                    "false = 描边为纯色:扁平物品仍保留物品形状(需读取贴图 alpha,首次渲染该物品有一次性的小开销),3D 物品形状由几何决定。",
                    "盾牌/三叉戟近似盒模型恒为纯色。")
            .define("itemPixelColorGlint", true);

    /**
     * 盔甲/鞘翅/投掷物实体的描边是否与纹理颜色混合(光影兼容模式)。
     * <p>
     * 与 {@link #ITEM_PIXEL_COLOR_GLINT} 同机制:开启光影且不允许未知 shader 时,
     * 实体描边采样自身纹理获得 alpha 遮罩 → 内置 fsh 的 {@code 描边色 × 纹理色}
     * 会让描边色与纹理颜色混合。
     * <ul>
     *   <li>true(默认):采样盔甲/鞘翅/三叉戟原纹理(形状贴合单层纹理镂空,接受颜色混合);</li>
     *   <li>false:描边为纯描边色(形状由模型几何决定,不再贴合纹理镂空)。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue ARMOR_PIXEL_COLOR_GLINT = BUILDER
            .comment("Mix armor outline color with the armor texture (shader-compat mode only).",
                    "盔甲/鞘翅/投掷物描边是否与纹理颜色混合(仅光影兼容模式下生效):",
                    "true = 采样原纹理 alpha 遮罩(贴合单层纹理轮廓,颜色与纹理混合);",
                    "false = 描边为纯描边色(形状由模型几何决定)。")
            .define("armorPixelColorGlint", true);

    /**
     * 关闭混色(纯色描边)时是否按物体颜色降低曝光亮度(仅光影兼容模式下生效)。
     * <p>
     * 混色开启时描边 = 描边色 × 物品贴图像素色,物品贴图多为暗/中性色 → 描边被
     * 天然压暗("根据物体颜色降低曝光亮度")。关闭混色后描边为纯色,emissive 全亮
     * 渲染下亮色描边(粉/金/白)在明亮场景中严重过曝刺眼。
     * 开启本项:纯色描边按物品贴图的<b>平均感知亮度</b>压暗 RGB(色相不变):
     * 暗色物品(铁剑等)描边显著变暗,亮色物品(金苹果等)基本不变;
     * 关闭本项:纯色描边保持原始亮度(可能出现曝光)。
     */
    public static final ModConfigSpec.BooleanValue OUTLINE_EXPOSURE_REDUCE = BUILDER
            .comment("Reduce pure-color outline exposure by the item color (shader-compat mode only).",
                    "关闭'附魔光效混合物品颜色'时,描边为纯色且 emissive 全亮,亮色描边在明亮场景下会过曝刺眼。",
                    "true = 纯色描边按物品贴图平均亮度压暗(暗色物品的描边显著变暗,亮色物品基本不变),色相不变;",
                    "false = 纯色描边保持原始亮度。")
            .define("outlineExposureReduce", true);

    // ==================== 颜色 ====================

    /** 默认描边色 */
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_COLOR = BUILDER
            .comment("Default outline color as hex RGB (RRGGBB).",
                    "未单独配置颜色的附魔所用的默认描边色。")
            .define("defaultColor", "FFC0CB");

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
