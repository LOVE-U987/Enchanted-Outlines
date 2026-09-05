package com.enchantedoutlines.mod.api.adapter;

import com.enchantedoutlines.mod.api.core.ItemTypeInfo;
import com.enchantedoutlines.mod.outline.OutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * BEWLR (Better Entity World Layer Renderer) 适配器。
 * <p>
 * 处理使用 BEWLR 协议自定义渲染的模组物品 (永恒星光/灾变等)。
 */
public class BewlrAdapter implements ItemModelAdapter {

    public static final BewlrAdapter INSTANCE = new BewlrAdapter();

    private static final String BEWLR_EXTENSION_CLASS =
            "net.neoforged.neoforge.client.extensions.common.IClientItemExtensions";

    @Override
    public String getName() {
        return "BewlrAdapter";
    }

    @Override
    public boolean matches(Item item) {
        try {
            // 检查物品是否有 IClientItemExtensions 扩展
            Class<?> extensionClass = Class.forName(BEWLR_EXTENSION_CLASS);
            Object extensions = item.getClass()
                    .getMethod("getClientExtensions")
                    .invoke(item);
            
            if (extensions == null) {
                return false;
            }
            
            // 检查是否有自定义渲染器
            java.lang.reflect.Method getMethod = extensionClass
                    .getMethod("getCustomRenderer");
            Object customRenderer = getMethod.invoke(extensions);
            
            return customRenderer != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean canHandleGui(ItemStack stack) {
        // 检查是否有 inventory 模型变体
        return hasInventoryModel(stack);
    }

    /**
     * 检查物品是否有 inventory 模型变体。
     */
    private boolean hasInventoryModel(ItemStack stack) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // 尝试查找 <itemId>_inventory 模型
        String inventoryModelId = itemId.toString() + "#inventory";
        
        try {
            ModelManager modelManager = net.minecraft.client.Minecraft.getInstance().getModelManager();
            ModelResourceLocation modelLocation = new ModelResourceLocation(itemId, "inventory");
            
            // NeoForge 20.x: hasModel() 已移除，用 getModel() 判断存在性
            BakedModel model = modelManager.getModel(modelLocation);
            return model != null && !model.getClass().getSimpleName().equals("MissingModel");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean renderGui(PoseStack pose, BakedModel model, int x, int y, int quadSize,
                              int color, float thickness) {
        // BEWLR 物品若有 inventory 模型，使用其渲染
        // 否则跳过 (由通用扁平渲染处理)
        String itemId = model.getClass().getSimpleName();
        // 简化处理：直接委托给 OutlineRenderer 的通用处理
        // 实际应使用 inventory 模型
        return false;
    }

    @Override
    public boolean renderHand(PoseStack pose, BakedModel model, int color, float thickness) {
        // BEWLR 手持物品由 OutlineRenderer.renderBewlrEntityOutline 处理
        // 此处返回 false 表示不处理
        return false;
    }

    @Override
    public boolean renderArmor(PoseStack pose, ItemStack stack, 
                                net.minecraft.world.entity.LivingEntity entity,
                                net.minecraft.world.entity.EquipmentSlot slot,
                                int color, float thickness,
                                net.minecraft.client.model.HumanoidModel<?> baseModel) {
        // BEWLR 盔甲由其他适配器处理
        return false;
    }

    @Nullable
    @Override
    public BakedModel getCustomModel(ItemStack stack, 
                                      @Nullable net.minecraft.world.level.Level world,
                                      @Nullable net.minecraft.world.entity.Entity entity) {
        // BEWLR 物品的自定义模型处理在 OutlineRenderer 中进行
        // 此处返回 null 表示使用默认模型
        return null;
    }

    @Nullable
    @Override
    public ItemTypeInfo resolve(Item item) {
        if (!matches(item)) {
            return null;
        }
        
        // BEWLR 物品的类型信息构建
        return new ItemTypeInfo(
            item, true, false, false, false, false, false, this, null, null, null, null, false, false, false
        );
    }
}
