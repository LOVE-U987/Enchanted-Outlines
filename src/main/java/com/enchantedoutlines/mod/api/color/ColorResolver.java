package com.enchantedoutlines.mod.api.color;

import com.enchantedoutlines.mod.config.Config;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 颜色解析器。
 * <p>
 * 按优先级解析描边颜色:RPG 虹灯 → 数据包颜色 → 配置颜色。
 */
public final class ColorResolver {

    public static final ColorResolver INSTANCE = new ColorResolver();

    /** 数据包颜色缓存。 */
    private final Map<ResourceLocation, Integer> dataPackColors = new HashMap<>();

    /** 物品颜色缓存。 */
    private final Map<ResourceLocation, Integer> itemColorCache = new HashMap<>();

    /** 附魔颜色缓存。 */
    private final Map<ResourceLocation, Integer> enchantColorCache = new HashMap<>();

    /** 是否启用 RPG 虹灯。 */
    private boolean rpgRainbowEnabled = false;

    /** 最后计算时间 (用于 RPG 虹灯)。 */
    private long lastRainbowCalc = 0;

    /** 物品 → 最后计算时间 (独立于全局，避免冲突)。 */
    private final Map<ResourceLocation, Long> itemRainbowTime = new HashMap<>();

    private ColorResolver() {
        // 初始化时加载数据包颜色
        loadDataPackColors();
    }

    /**
     * 解析物品的描边颜色。
     *
     * @param stack 物品栈
     * @return 描边颜色 (ARGB)
     */
    public int resolve(ItemStack stack) {
        ResourceLocation itemId = stack.getItem().getRegistryName();

        // 1. RPG 虹灯 (优先级最高)
        if (rpgRainbowEnabled && Config.ENABLE_RPG_RAINBOW.get()) {
            return resolveRainbowColor(stack);
        }

        // 2. 数据包颜色
        Integer dataPackColor = dataPackColors.get(itemId);
        if (dataPackColor != null) {
            return dataPackColor;
        }

        // 3. 配置颜色 (物品级)
        Integer itemColor = Config.itemColor(itemId);
        if (itemColor != null) {
            return itemColor;
        }

        // 4. 附魔颜色
        List<ResourceLocation> enchantIds = getEnchantments(stack);
        if (!enchantIds.isEmpty()) {
            for (ResourceLocation enchantId : enchantIds) {
                Integer enchantColor = Config.enchantColorMap().get(enchantId);
                if (enchantColor != null) {
                    return enchantColor;
                }
            }
        }

        // 5. 默认颜色
        return Config.defaultColorArgb();
    }

    /**
     * 解析 RPG 虹灯颜色。
     */
    private int resolveRainbowColor(ItemStack stack) {
        ResourceLocation itemId = stack.getItem().getRegistryName();
        long now = System.currentTimeMillis();
        long itemTime = itemRainbowTime.getOrDefault(itemId, 0L);
        
        // 每个物品的彩虹进度独立
        if (now - itemTime > 100) { // 每 100ms 更新一次
            itemRainbowTime.put(itemId, now);
            lastRainbowCalc = now;
        }

        long time = now % 5000; // 5 秒周期
        float hue = (time + stack.hashCode()) / 5000f;
        
        // HSB 转 RGB
        int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f);
        return 0xFF000000 | rgb;
    }

    /**
     * 获取物品的附魔列表。
     */
    private List<ResourceLocation> getEnchantments(ItemStack stack) {
        List<ResourceLocation> enchantIds = new ArrayList<>();
        
        try {
            net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack.getEnchantmentMap())
                    .forEach((enchant, level) -> {
                        ResourceLocation id = enchant.getRegistryName();
                        if (id != null) {
                            enchantIds.add(id);
                        }
                    });
        } catch (Exception e) {
            // 兼容不同版本的附魔 API
        }
        
        return enchantIds;
    }

    /**
     * 加载数据包颜色。
     */
    public void loadDataPackColors() {
        dataPackColors.clear();
        // TODO: 从 assets/enchanted_outlines/color/pack.json 加载
        // 简化处理：目前为空
    }

    /**
     * 更新数据包颜色。
     */
    public void updateDataPackColors(Map<ResourceLocation, Integer> colors) {
        dataPackColors.putAll(colors);
    }

    /**
     * 是否启用 RPG 虹灯。
     */
    public boolean isRpgRainbowEnabled() {
        return rpgRainbowEnabled;
    }

    /**
     * 设置 RPG 虹灯启用状态。
     */
    public void setRpgRainbowEnabled(boolean enabled) {
        this.rpgRainbowEnabled = enabled;
    }

    /**
     * 清空缓存。
     */
    public void invalidateCache() {
        itemColorCache.clear();
        itemRainbowTime.clear();
    }

    /**
     * HSB 转 ARGB。
     */
    public static int hsbToArgb(float hue, float saturation, float brightness) {
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        return 0xFF000000 | rgb;
    }
}
