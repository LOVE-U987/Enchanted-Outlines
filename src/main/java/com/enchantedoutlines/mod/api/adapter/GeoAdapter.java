package com.enchantedoutlines.mod.api.adapter;

import com.enchantedoutlines.mod.outline.OutlineRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.block.model.BakedModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Geo 骨骼适配器 (GeckoLib/AzureLib)。
 * <p>
 * 处理使用 GeoLib 协议自定义渲染的模组物品/盔甲。
 */
public class GeoAdapter implements ItemModelAdapter {

    private static final String GEO_RENDER_PROVIDER =
            "software.bernie.geckolib.animatable.client.GeoRenderProvider";
    private static final String AZURE_REGISTRY =
            "mod.azure.azurelib.common.render.armor.AzArmorRendererRegistry";

    @Override
    public String getName() {
        return "GeoAdapter";
    }

    @Override
    public boolean canHandle(Item item) {
        // 检查是否为 Geo 物品
        return isGeoItem(item) || isGeoArmor(item);
    }

    /**
     * 判断是否为 Geo 物品。
     */
    private boolean isGeoItem(Item item) {
        try {
            Class<?> providerClass = Class.forName(GEO_RENDER_PROVIDER);
            java.lang.reflect.Method of = providerClass.getMethod("of", Item.class);
            Object provider = of.invoke(null, item);
            return provider != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否为 Geo 盔甲。
     */
    private boolean isGeoArmor(Item item) {
        try {
            Class<?> providerClass = Class.forName(GEO_RENDER_PROVIDER);
            java.lang.reflect.Method of = providerClass.getMethod("of", Item.class);
            Object provider = of.invoke(null, item);
            
            if (provider == null) {
                return false;
            }
            
            // 尝试获取盔甲渲染器
            for (java.lang.reflect.Method m : provider.getClass().getMethods()) {
                if (m.getName().equals("getGeoArmorRenderer")) {
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否为 AzureLib 盔甲。
     */
    public boolean isAzureLib(Item item) {
        try {
            Class<?> regClass = Class.forName(AZURE_REGISTRY);
            java.lang.reflect.Method getOrNull = regClass.getMethod("getOrNull", ItemStack.class);
            Object renderer = getOrNull.invoke(null, new ItemStack(item));
            return renderer != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean canHandleGui(ItemStack stack) {
        return canHandle(stack.getItem()) && !isAzureLib(stack.getItem());
    }

    @Override
    public boolean renderGui(PoseStack pose, BakedModel model, int x, int y, int quadSize,
                              int color, float thickness) {
        // Geo 物品 GUI 由 OutlineRenderer.renderGeoItemOutline 处理
        return false;
    }

    @Override
    public boolean renderHand(PoseStack pose, BakedModel model, int color, float thickness) {
        return false;
    }

    @Override
    public boolean renderArmor(PoseStack pose, ItemStack stack, LivingEntity entity,
                                EquipmentSlot slot, int color, float thickness,
                                HumanoidModel<?> baseModel) {
        // AzureLib 盔甲由 OutlineRenderer.renderAzureLibArmorOutline 处理
        // GeckoLib 盔甲由 OutlineRenderer.renderGeckoLibArmorOutline 处理
        return false;
    }

    @Nullable
    @Override
    public BakedModel getCustomModel(ItemStack stack, @Nullable net.minecraft.world.level.Level world,
                                      @Nullable net.minecraft.world.entity.Entity entity) {
        try {
            if (isAzureLib(stack.getItem())) {
                // AzureLib 物品
                Class<?> regClass = Class.forName(AZURE_REGISTRY);
                java.lang.reflect.Method getOrNull = regClass.getMethod("getOrNull", ItemStack.class);
                Object renderer = getOrNull.invoke(null, stack);
                
                if (renderer == null) {
                    return null;
                }
                
                java.lang.reflect.Method providerM = renderer.getClass().getMethod("provider");
                Object provider = providerM.invoke(renderer);
                
                java.lang.reflect.Method provideBakedModel = provider.getClass()
                        .getMethod("provideBakedModel", Object.class, Object.class);
                return (BakedModel) provideBakedModel.invoke(provider, entity, stack);
            } else if (isGeoItem(stack.getItem())) {
                // GeckoLib 物品
                Class<?> providerClass = Class.forName(GEO_RENDER_PROVIDER);
                java.lang.reflect.Method of = providerClass.getMethod("of", Item.class);
                Object provider = of.invoke(null, stack.getItem());
                
                java.lang.reflect.Method getGeoModel = provider.getClass().getMethod("getGeoModel");
                Object geoModel = getGeoModel.invoke(provider);
                
                java.lang.reflect.Method getModelResource = geoModel.getClass()
                        .getMethod("getModelResource", Object.class);
                Object loc = getModelResource.invoke(geoModel, stack.getItem());
                
                if (!(loc instanceof net.minecraft.resources.ResourceLocation mrl)) {
                    return null;
                }
                
                java.lang.reflect.Method getTextureResource = geoModel.getClass()
                        .getMethod("getTextureResource", Object.class);
                Object tex = getTextureResource.invoke(geoModel, stack.getItem());
                
                if (!(tex instanceof net.minecraft.resources.ResourceLocation texture)) {
                    return null;
                }
                
                java.lang.reflect.Method getBakedModel = geoModel.getClass()
                        .getMethod("getBakedModel", net.minecraft.resources.ResourceLocation.class);
                Object baked = getBakedModel.invoke(geoModel, mrl);
                
                // 将 GeoModel 转换为 BakedModel(简化处理)
                return null;
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
