package com.enchantedoutlines.mod.outline;

import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.util.GsonHelper;

/**
 * 资源包轮廓定义(不可变)。
 * <p>
 * 由资源包 JSON 解析而来
 * ({@code assets/enchanted_outlines/outlines/<物品命名空间>/<物品路径>.json},
 * 见 {@link OutlineResources}),描述某物品描边的颜色(静态单色或时间循环动画)与厚度。
 * <p>
 * 动画为 keyframe 颜色循环:调色板首尾相接,按周期在相邻颜色间插值,每帧求值
 * (与 {@link ColorResolver} 的 RPG 虹灯同款"逐帧计算、零缓存"设计,开销同量级)。
 * 二期"UV 流光"字段预留:解析时未知 JSON 字段一律忽略,新增字段向后兼容。
 * <p>
 * 颜色格式 {@code #RRGGBB} 或 {@code #AARRGGBB};描边色恒强制不透明
 * (透明度由形状遮罩提供,与 {@link OutlineColorRegistry#registerItemColor} 语义一致)。
 */
public final class OutlineDef {

    /** 循环周期下限(毫秒):防止资源包写出高频闪烁定义。 */
    private static final int MIN_PERIOD_MS = 250;
    /** 循环周期上限(毫秒):防手滑写出超长周期导致 int 运算溢出。 */
    private static final int MAX_PERIOD_MS = 600000;
    /** 默认循环周期(毫秒)。 */
    private static final int DEFAULT_PERIOD_MS = 5000;

    /** keyframe 调色板(不透明 ARGB);单元素 = 静态色。 */
    private final int[] colors;
    /** 循环周期(毫秒)。 */
    private final int periodMs;
    /** 相位偏移(毫秒):同套装不同件错开相位可获得流动感。 */
    private final int offsetMs;
    /** true = keyframe 间 smoothstep 插值(缓入缓出),false = 线性。 */
    private final boolean smooth;
    /** 逐物品厚度(像素,非负);null = 未定义,沿用现有厚度优先级。 */
    private final Float thickness;

    private OutlineDef(int[] colors, int periodMs, int offsetMs, boolean smooth, Float thickness) {
        this.colors = colors;
        this.periodMs = periodMs;
        this.offsetMs = offsetMs;
        this.smooth = smooth;
        this.thickness = thickness;
    }

    /**
     * 从 JSON 解析轮廓定义。
     *
     * @param root 资源包 JSON 根元素
     * @return 解析后的定义
     * @throws IllegalArgumentException JSON 结构/字段不合法(调用方负责记录 warn 并跳过)
     */
    public static OutlineDef parse(JsonElement root) {
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("顶层必须是 JSON 对象");
        }
        JsonObject json = root.getAsJsonObject();
        String mode = GsonHelper.getAsString(json, "mode", "static");
        boolean cycle = switch (mode) {
            case "static" -> false;
            case "cycle" -> true;
            default -> throw new IllegalArgumentException("mode 必须是 \"static\" 或 \"cycle\",实为 \"" + mode + "\"");
        };
        String easing = GsonHelper.getAsString(json, "easing", "linear");
        boolean smooth = switch (easing) {
            case "linear" -> false;
            case "smooth" -> true;
            default -> throw new IllegalArgumentException("easing 必须是 \"linear\" 或 \"smooth\",实为 \"" + easing + "\"");
        };
        int periodMs = clampPeriod(GsonHelper.getAsInt(json, "period_ms", DEFAULT_PERIOD_MS));
        int offsetMs = GsonHelper.getAsInt(json, "offset_ms", 0);
        Float thickness = parseThickness(json);
        int[] colors = parseColors(json, cycle);
        return new OutlineDef(colors, periodMs, offsetMs, smooth, thickness);
    }

    /**
     * 解析并钳制循环周期。
     *
     * @param raw JSON 中的 period_ms 原始值
     * @return 钳制到 [MIN_PERIOD_MS, MAX_PERIOD_MS] 后的周期
     */
    private static int clampPeriod(int raw) {
        return Math.max(MIN_PERIOD_MS, Math.min(MAX_PERIOD_MS, raw));
    }

    /**
     * 解析逐物品厚度字段(可选)。
     *
     * @param json 定义 JSON 对象
     * @return 厚度(非负);未定义返回 null
     * @throws IllegalArgumentException 厚度为负或非数值
     */
    private static Float parseThickness(JsonObject json) {
        if (!json.has("thickness")) {
            return null;
        }
        float value = GsonHelper.getAsFloat(json, "thickness");
        if (value < 0.0f) {
            throw new IllegalArgumentException("thickness 不能为负,实为 " + value);
        }
        return value;
    }

    /**
     * 解析颜色字段:static 模式取 {@code color},cycle 模式取 {@code colors} 数组。
     * 单元素调色板合法(等价静态色)。
     *
     * @param json  定义 JSON 对象
     * @param cycle 是否为 cycle 模式
     * @return 不透明 ARGB 颜色数组(至少一个元素)
     * @throws IllegalArgumentException 颜色缺失/格式错误/数组为空
     */
    private static int[] parseColors(JsonObject json, boolean cycle) {
        if (cycle) {
            if (!json.has("colors") || !json.get("colors").isJsonArray()) {
                throw new IllegalArgumentException("cycle 模式必须提供 colors 数组");
            }
            JsonArray array = json.getAsJsonArray("colors");
            if (array.isEmpty()) {
                throw new IllegalArgumentException("colors 数组不能为空");
            }
            int[] colors = new int[array.size()];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = parseColor(GsonHelper.convertToString(array.get(i), "colors[" + i + "]"));
            }
            return colors;
        }
        return new int[] { parseColor(GsonHelper.getAsString(json, "color")) };
    }

    /**
     * 解析单个颜色字符串。
     *
     * @param text {@code #RRGGBB} 或 {@code #AARRGGBB}(# 可省略)
     * @return 不透明 ARGB(透明度被强制为 FF:描边 alpha 由形状遮罩提供)
     * @throws IllegalArgumentException 十六进制格式不合法
     */
    private static int parseColor(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        long value;
        try {
            value = Long.parseLong(hex.toLowerCase(Locale.ROOT), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("颜色 \"" + text + "\" 不是合法的十六进制");
        }
        if (hex.length() != 6 && hex.length() != 8) {
            throw new IllegalArgumentException("颜色 \"" + text + "\" 需为 #RRGGBB 或 #AARRGGBB");
        }
        return (int) (value | 0xFF000000L);
    }

    /**
     * 该定义是否为颜色循环动画。
     *
     * @return true = cycle 且调色板多于一个颜色(扁平路径将改走白色烘焙 + 顶点色)
     */
    public boolean isAnimated() {
        return this.colors.length >= 2;
    }

    /**
     * 静态颜色(不透明 ARGB)。动画定义返回调色板首色(仅作兜底,动画应查 {@link #colorAt})。
     *
     * @return 不透明 ARGB 颜色
     */
    public int color() {
        return this.colors[0];
    }

    /**
     * 求值某一时刻的颜色:沿调色板循环插值。每帧调用(与虹灯同款零缓存设计)。
     *
     * @param nowMs 当前时间戳(System.currentTimeMillis())
     * @return 该时刻的不透明 ARGB 颜色
     */
    public int colorAt(long nowMs) {
        int[] c = this.colors;
        if (c.length == 1) {
            return c[0];
        }
        long t = Math.floorMod(nowMs - this.offsetMs, this.periodMs);
        double pos = (double) t / this.periodMs * c.length;
        int index = (int) pos;
        float frac = (float) (pos - index);
        int from = c[index % c.length];
        int to = c[(index + 1) % c.length];
        float f = this.smooth ? frac * frac * (3.0f - 2.0f * frac) : frac;
        return lerpRgb(from, to, f);
    }

    /**
     * 逐物品厚度。
     *
     * @return 厚度(像素,非负);未定义返回 null(调用方沿用现有厚度优先级)
     */
    public Float thickness() {
        return this.thickness;
    }

    /**
     * RGB 通道线性插值。
     *
     * @param from 起始不透明 ARGB
     * @param to   目标不透明 ARGB
     * @param f    插值系数 0..1
     * @return 插值后的不透明 ARGB
     */
    private static int lerpRgb(int from, int to, float f) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
