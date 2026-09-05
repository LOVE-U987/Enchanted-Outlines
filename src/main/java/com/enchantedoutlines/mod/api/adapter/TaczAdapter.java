package com.enchantedoutlines.mod.api.adapter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * TACZ (The Arsenal Collection Z) 专项适配器。
 * <p>
 * 处理 TACZ 模组的特殊渲染需求。
 * <p>
 * TACZ 使用 GeoLib/AnimateGeoItemRenderer 渲染武器，需要特殊处理 Geo 骨骼描边。
 */
public class TaczAdapter implements ItemModelAdapter {

    public static final TaczAdapter INSTANCE = new TaczAdapter();

    @Override
    public String getName() {
        return "TaczAdapter";
    }

    @Override
    public int getPriority() {
        // TACZ 优先级最高，确保 Geo 路径优先处理
        return 100;
    }

    @Override
    public boolean matches(Item item) {
        try {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) {
                return false;
            }
            // TACZ 物品通常在 thearsenalcollection 或 tac 命名空间下
            String itemIdStr = itemId.toString();
            if (itemIdStr.startsWith("thearsenalcollection:") || itemIdStr.startsWith("tac:")) {
                // 检查是否为 Geo 渲染（AnimateGeoItemRenderer）
                return isTaczGeoItem(item);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查 TACZ 物品是否使用 Geo 渲染器。
     */
    private boolean isTaczGeoItem(Item item) {
        try {
            // 检查是否有 Geo 相关的字段或渲染器
            Class<?> itemClass = item.getClass();
            
            // 尝试获取 IClientItemExtensions 并检查是否为 Geo 渲染器
            Class<?> extensionClass = Class.forName(
                    "net.neoforged.neoforge.client.extensions.common.IClientItemExtensions");
            Object extensions = itemClass.getMethod("getClientExtensions").invoke(item);
            
            if (extensions != null) {
                java.lang.reflect.Method getMethod = extensionClass.getMethod("getCustomRenderer");
                Object customRenderer = getMethod.invoke(extensions);
                
                if (customRenderer != null) {
                    String rendererClass = customRenderer.getClass().getName();
                    // 检查是否为 Geo 渲染器
                    return rendererClass.contains("geckolib") || 
                           rendererClass.contains("AnimateGeo") ||
                           rendererClass.contains("tacz.guns") && rendererClass.contains("renderer");
                }
            }
            
            // 检查类名是否包含 Geo 相关标识
            String className = itemClass.getName();
            return className.contains("geckolib") || className.contains("Geo");
            
        } catch (Exception e) {
            // 如果无法确定，默认为 Geo 物品（TACZ 武器主要使用 Geo）
            return true;
        }
    }

    /**
     * 检查 TACZ 物品的特殊纹理格式。
     */
    public boolean hasSpecialTexture(ItemStack stack) {
        // TACZ 可能有特殊的武器纹理格式
        // TODO: 具体实现
        return false;
    }

    /**
     * 获取 TACZ 物品的自定义渲染参数。
     */
    @Nullable
    public TaczRenderParams getRenderParams(ItemStack stack) {
        // 返回特殊的渲染参数
        return null;
    }

    /**
     * TACZ 渲染参数。
     */
    public static class TaczRenderParams {
        /** 是否需要特殊处理枪管 */
        public boolean specialBarrel = false;
        /** 是否需要特殊处理瞄准镜 */
        public boolean specialScope = false;
        /** 是否需要特殊处理枪托 */
        public boolean specialStock = false;
    }

    @Nullable
    @Override
    public com.enchantedoutlines.mod.api.core.ItemTypeInfo resolve(net.minecraft.world.item.Item item) {
        if (!matches(item)) {
            return null;
        }
        
        // TACZ 使用 GeoLib/AnimateGeoItemRenderer 渲染武器，所以是 Geo 物品
        // TACZ 不应该走 BEWLR 路径，应直接由 GeoAdapter 处理描边
        // ⚠️ 关键：isBewlr 必须为 false，避免 ItemModelRegistry 设置 isBewlr=true
        //     导致进入 renderBewlrEntityOutline() 后 fallback 失败
        // 类型信息：isBewlr=false, isGeo=true, isAzureLib=false, isLionfish=false, isTacz=true
        // 参数顺序：item, isBewlr, isGeo, isAzureLib, isLionfish, isTacz, isFlat,
        //           bewlrAdapter, geoAdapter, lionfishAdapter, taczAdapter, hazenAdapter, isArmor, isElytra, isProjectile
        return new com.enchantedoutlines.mod.api.core.ItemTypeInfo(
            item, false, true, false, false, true, false, null, GeoAdapter.INSTANCE, null, TaczAdapter.INSTANCE, null, false, false, false
        );
    }
}
