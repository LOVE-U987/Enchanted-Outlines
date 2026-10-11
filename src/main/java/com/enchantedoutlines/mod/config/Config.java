package com.enchantedoutlines.mod.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
     * <p>
     * <b>2026-10-02 语义修正(用户决策)</b>:本开关仅<b>光影兼容模式</b>下生效
     * (混色依据配置);<b>不开光影时代码级强制纯色</b>(renderFlatOutlineShape 的
     * mix 计算含 needVanillaShaderFallback 门禁,无视本开关)。
     */
    public static final ModConfigSpec.BooleanValue ITEM_PIXEL_COLOR_GLINT = BUILDER
            .comment("Mix outline color with each item pixel color (shader-compat mode only; no-shader mode is always pure color).",
                    "附魔光效是否根据物品每个像素的颜色确定 (仅光影兼容模式下生效;不开光影时描边恒为纯色):",
                    "true = 光影模式下描边色与物品贴图像素颜色混合 (扁平物品与 3D 物品均生效，保留物品形状，颜色混合后独特有趣);",
                    "false = 光影模式下描边为纯色:扁平物品仍保留物品形状 (需读取贴图 alpha，首次渲染该物品有一次性的小开销),3D 物品形状由几何决定。",
                    "盾牌/三叉戟近似盒模型恒为纯色。")
            .define("itemPixelColorGlint", true);

    /**
     * 盔甲/鞘翅/投掷物实体的描边是否与纹理颜色混合 (光影兼容模式)。
     * <p>
     * <b>2026-10-02 语义修正(用户决策)</b>:本开关仅<b>光影兼容模式</b>下生效;
     * <b>不开光影时代码级强制纯色</b>(armorOutlineRenderType 自定义 shader 分支
     * blend 恒 0,无视本开关)。
     */
    public static final ModConfigSpec.BooleanValue ARMOR_PIXEL_COLOR_GLINT = BUILDER
            .comment("Mix armor outline color with the armor texture (shader-compat mode only; no-shader mode is always pure color).",
                    "盔甲/鞘翅/投掷物描边是否与纹理颜色混合 (仅光影兼容模式下生效;不开光影时描边恒为纯色):",
                    "true = 光影模式下采样原纹理 alpha 遮罩 (贴合单层纹理轮廓，颜色与纹理混合);",
                    "false = 光影模式下描边为纯描边色 (形状由模型几何决定)。")
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

    /**
     * 是否启用 BEWLR 通用捕获算法 <b>v2(规范化捕获,2026-09-25)</b>,默认开启。
     * <p>
     * v2:用<b>空 PoseStack(identity)</b> 调模组 renderByItem 捕获,顶点落在渲染器
     * <b>本地模型空间</b>(与相机/GUI 像素空间无关)→ 守卫全 context 统一(跨度 +
     * 中心距离,GUI 也适用)、外扩量与兄弟路径像素语义一致(GUI 对齐标准 3D GUI 路径、
     * 手持对齐 Lionfish 逐 cube 路径)→ 按真实 pose 矩阵重放。渲染器强依赖真实 pose
     * 时自动回退 v1 旧算法(复制 pose 捕获最终坐标);守卫拒绝(本地空间垃圾)不回退。
     */
    public static final ModConfigSpec.BooleanValue BEWLR_CAPTURE_V2 = BUILDER
            .comment("Use universal BEWLR capture algorithm v2 - normalized capture (default true).",
                    "BEWLR 通用捕获算法 v2(规范化捕获):",
                    "true = 用空 PoseStack(identity)捕获 renderByItem 输出,得到与相机/GUI 像素空间",
                    "无关的本地模型空间几何 —— 守卫全 context 统一(异常巨大/错位几何一律拒绝),",
                    "描边厚度与其他路径像素语义一致,位置按真实 pose 重放与本体 100% 对齐;",
                    "渲染器强依赖真实 pose 时自动回退 v1 旧算法。",
                    "false = 使用 v1 旧算法(复制 pose 捕获最终绘制坐标,仅手持 context 有中心距离守卫)。")
            .define("bewlrCaptureV2", true);

    /**
     * 是否启用 BEWLR 捕获的<b>全局缓冲旁路</b>(v1-bypass,2026-09-26 重新设计,默认开启)。
     * <p>
     * 某些渲染器(如 TACZ BedrockModel.render)不写传入的 MultiBufferSource,而是
     * 直接写全局共享 bufferSource —— 普通捕获拿不到几何;旁路 = 捕获期间临时替换
     * RenderBuffers.bufferSource。<b>2026-09-26 重新设计</b>:旁路改由 v1 路径以
     * <b>真实 pose</b> 使用(捕获 = 本体最终绘制坐标,原样发射零重放)—— 旧 v2.1 的
     * identity+重放组合对"读 pose"型渲染器产出假定空间垃圾(2026-09-25 实测 TACZ
     * "巨大模型错位"回归根因),已移除。开启后旁路捕获的几何统计会走节流日志
     * (swap-stats)输出;出现错位可关闭本项回到无描边。
     */
    public static final ModConfigSpec.BooleanValue BEWLR_GLOBAL_BUFFER_SWAP = BUILDER
            .comment("Enable global-buffer-swap BEWLR capture (default true, redesigned as v1-bypass).",
                    "BEWLR 捕获的全局缓冲旁路(2026-09-26 v1-bypass,默认开启):",
                    "某些渲染器(TACZ BedrockModel.render)绕过传入的 buffer 直接写全局共享 bufferSource,",
                    "普通捕获拿不到几何;旁路 = 捕获期间临时替换 RenderBuffers.bufferSource。",
                    "2026-09-26 重新设计:旁路改由 v1 路径以真实 pose 使用(捕获 = 本体最终绘制坐标,",
                    "原样发射零重放)—— 旧 v2.1 的 identity+重放组合对'读 pose'型渲染器产出假定空间",
                    "垃圾(2026-09-25 实测'巨大模型错位'回归根因),已移除。开启后旁路捕获统计走",
                    "swap-stats 节流日志;出现错位可关闭本项回到无描边。")
            .define("bewlrGlobalBufferSwap", true);

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
            .define("enableTaczCompat", true);

    /**
     * 命名空间 → BEWLR 适配器注册表(namespace=kind,2026-10-02 通用化收官)。
     * <p>
     * 把派发链的手写 if-else 泛化为<b>配置驱动</b>:每个命名空间可指定适配器优先级,
     * 新模组出问题改配置即可换路径/关闭,<b>无需改代码</b>。未配置的命名空间走默认
     * capture 优先链。与 enableTaczCompat / enableHazenCompat 粗粒度开关叠加生效。
     */
    public static final ModConfigSpec.ConfigValue<String> BEWLR_NAMESPACE_ADAPTERS = BUILDER
            .comment("Namespace-to-adapter registry as namespace=kind, comma separated.",
                    "命名空间 → BEWLR 适配器注册表:namespace=kind，逗号分隔。",
                    "kind: capture = 捕获优先(默认,描边随动画);legacy = 反射/Geo 直取优先(旧行为);",
                    "off = 该命名空间一律无描边。未配置的命名空间走默认 capture 优先链。",
                    "新模组出问题改配置即可换路径/关闭,无需改代码。",
                    "示例: 'hazennstuff=legacy,tacz=capture,somemod=off'",
                    "与 enableTaczCompat / enableHazenCompat 粗粒度开关叠加生效。")
            .define("bewlrNamespaceAdapters", "");

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

    /** 逐物品描边厚度映射 (覆盖全局 thickness,含模组物品) */
    public static final ModConfigSpec.ConfigValue<String> ITEM_THICKNESSES = BUILDER
            .comment("Per-item outline thickness as itemid=pixels, comma separated.",
                    "逐物品描边厚度:itemid=像素数，逗号分隔;覆盖全局 thickness 配置 (含模组物品)。",
                    "程序化注册 (OutlineColorRegistry.registerItemThickness) 优先于本配置。",
                    "示例: 'minecraft:diamond_sword=2.5,tacz:modern_kinetic_gun=1.5'")
            .define("itemThicknesses", "");

    /** 强制纯色描边的物品列表 (即使光影模式开混色也恒纯色;支持通配符) */
    public static final ModConfigSpec.ConfigValue<String> SOLID_COLOR_ITEMS = BUILDER
            .comment("Items whose outline is ALWAYS pure color (even in shader-compat mode with mixing on); supports wildcards.",
                    "强制纯色描边的物品 id，逗号分隔;即使光影模式开着混色,这些物品的描边也恒为纯色。",
                    "支持通配符：'minecraft:*' 匹配整个命名空间。不开光影时所有描边本就恒为纯色，本列表只在光影模式下生效。")
            .define("solidColorItems", "");

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
    private static volatile Map<ResourceLocation, Float> cachedItemThicknesses = null;
    private static volatile List<Pattern> cachedEnabledPatterns = null;
    private static volatile List<Pattern> cachedDisabledPatterns = null;
    private static volatile List<Pattern> cachedSolidPatterns = null;
    private static volatile Map<String, String> cachedNamespaceAdapters = null;

    private Config() {
    }

    /** 配置重载后调用，清空解析缓存。 */
    public static void invalidateCache() {
        cachedDefaultColor = null;
        cachedEnchantColors = null;
        cachedItemColors = null;
        cachedItemThicknesses = null;
        cachedEnabledPatterns = null;
        cachedDisabledPatterns = null;
        cachedSolidPatterns = null;
        cachedNamespaceAdapters = null;
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

    /** 物品厚度映射 (ResourceLocation → 像素),未配置返回空表。 */
    public static Map<ResourceLocation, Float> itemThicknessMap() {
        Map<ResourceLocation, Float> m = cachedItemThicknesses;
        if (m == null) {
            m = parseThicknessMap(ITEM_THICKNESSES.get());
            cachedItemThicknesses = m;
        }
        return m;
    }

    /** 物品描边厚度 (配置映射 itemThicknesses);未配置返回 null。程序化注册优先于本配置。 */
    public static Float itemThickness(ResourceLocation item) {
        return itemThicknessMap().get(item);
    }

    /** 物品是否被配置强制为纯色描边 (solidColorItems 列表,支持通配符)。 */
    public static boolean isItemSolid(ResourceLocation item) {
        List<Pattern> list = cachedSolidPatterns;
        if (list == null) {
            list = parseIdPatterns(SOLID_COLOR_ITEMS.get());
            cachedSolidPatterns = list;
        }
        return matchesAny(list, item);
    }

    /**
     * 命名空间 → BEWLR 适配器 kind(capture/legacy/off);未配置返回 null。
     *
     * @param namespace 命名空间(如 tacz / hazennstuff)
     * @return 适配器 kind(小写);未配置返回 null(走默认 capture 优先链)
     */
    public static String namespaceAdapter(String namespace) {
        Map<String, String> m = cachedNamespaceAdapters;
        if (m == null) {
            m = parseAdapterMap(BEWLR_NAMESPACE_ADAPTERS.get());
            cachedNamespaceAdapters = m;
        }
        String kind = m.get(namespace);
        return kind != null ? kind.toLowerCase(Locale.ROOT) : null;
    }

    /** 解析 namespace=kind 逗号分隔映射(键值均小写归一)。 */
    private static Map<String, String> parseAdapterMap(String raw) {
        Map<String, String> map = new HashMap<>();
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
            map.put(seg.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                    seg.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
        }
        return map;
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

    /** 解析 id=像素数 逗号分隔映射 (逐物品厚度,值容错小数,负值按 0 处理)。 */
    private static Map<ResourceLocation, Float> parseThicknessMap(String raw) {
        Map<ResourceLocation, Float> map = new HashMap<>();
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
            try {
                map.put(id, Math.max(0f, Float.parseFloat(seg.substring(eq + 1).trim())));
            } catch (NumberFormatException ignored) {
                continue;
            }
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
