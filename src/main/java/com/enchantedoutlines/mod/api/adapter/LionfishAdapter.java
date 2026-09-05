package com.enchantedoutlines.mod.api.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * LionfishAPI 适配器 (灾变 Cataclysm)。
 * <p>
 * 处理使用 LionfishAPI 的灾变模组物品。
 */
public class LionfishAdapter implements ItemModelAdapter {

    public static final LionfishAdapter INSTANCE = new LionfishAdapter();

    private static final String LIONFISH_MODEL =
            "com.enderanimator.lionfishapi.model.AdvancedModelBox";
    private static final String LIONFISH_ENTITY_MODEL =
            "com.enderanimator.lionfishapi.model.AdvancedEntityModel";

    @Override
    public String getName() {
        return "LionfishAdapter";
    }

    @Override
    public boolean matches(Item item) {
        try {
            // 检查物品是否有 LionfishAPI 的自定义渲染器
            // 通过检查是否有 AdvancedEntityModel 或 AdvancedModelBox 相关字段
            Class<?> itemClass = item.getClass();
            
            // 尝试反射检查是否为灾变武器
            for (java.lang.reflect.Field field : itemClass.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.contains("lionfish") || typeName.contains("enderanimator")) {
                    return true;
                }
            }
            
            // 检查是否有 BEWLR 渲染器且为 Lionfish
            return hasLionfishRenderer(item);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否有 Lionfish 渲染器。
     */
    private boolean hasLionfishRenderer(Item item) {
        try {
            // 尝试获取 IClientItemExtensions
            Class<?> extensionClass = Class.forName(
                    "net.neoforged.neoforge.client.extensions.common.IClientItemExtensions");
            Object extensions = item.getClass()
                    .getMethod("getClientExtensions")
                    .invoke(item);
            
            if (extensions == null) {
                return false;
            }
            
            java.lang.reflect.Method getMethod = extensionClass.getMethod("getCustomRenderer");
            Object customRenderer = getMethod.invoke(extensions);
            
            if (customRenderer == null) {
                return false;
            }
            
            // 检查渲染器是否为 Lionfish 类型
            String rendererClass = customRenderer.getClass().getName();
            return rendererClass.contains("lionfish") || 
                   rendererClass.contains("enderanimator") ||
                   rendererClass.contains("cataclysm");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean renderHand(PoseStack pose, BakedModel model, int color, float thickness) {
        // Lionfish 手持物品由 OutlineRenderer.renderBewlrEntityOutline 处理
        // 并检测为 Lionfish 后使用 renderLionfishPerCube
        return false;
    }

    /**
     * 检测模型是否为 LionfishAPI 模型。
     */
    public boolean isLionfishModel(Object model) {
        if (model == null) {
            return false;
        }
        
        String className = model.getClass().getName();
        return className.contains("lionfish") || 
               className.contains("enderanimator") ||
               className.contains("cataclysm");
    }

    /**
     * 获取 Lionfish 模型的 root。
     */
    @Nullable
    public Object getLionfishRoot(Object model) {
        try {
            // 优先调用 root() 方法
            java.lang.reflect.Method rootM = model.getClass().getMethod("root");
            Object root = rootM.invoke(model);
            if (root != null) {
                return root;
            }
            
            // 回退 root 字段
            java.lang.reflect.Field rootF = model.getClass().getDeclaredField("root");
            rootF.setAccessible(true);
            return rootF.get(model);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断是否为逐 cube 外扩模式。
     */
    public boolean isPerCubeMode() {
        return com.enchantedoutlines.mod.config.Config.BEWLR_3D_PER_CUBE.get();
    }

    @Nullable
    @Override
    public com.enchantedoutlines.mod.api.core.ItemTypeInfo resolve(net.minecraft.world.item.Item item) {
        if (!matches(item)) {
            return null;
        }
        
        // Lionfish 物品的类型信息构建
        return new com.enchantedoutlines.mod.api.core.ItemTypeInfo(
            item, false, false, false, true, false, false, null, null, this, null, null, false, false, false
        );
    }
}
