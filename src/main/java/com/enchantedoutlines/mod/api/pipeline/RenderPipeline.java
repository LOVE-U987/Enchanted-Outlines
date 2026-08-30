package com.enchantedoutlines.mod.api.pipeline;

import com.enchantedoutlines.mod.api.adapter.ItemModelAdapter;
import com.enchantedoutlines.mod.api.algo.ShapeResult;
import com.enchantedoutlines.mod.api.core.ItemTypeInfo;
import com.enchantedoutlines.mod.api.core.OutlineRenderingApi;
import com.enchantedoutlines.mod.outline.OutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.block.model.BakedModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 渲染管线。
 * <p>
 * 负责协调各适配器和算法，执行完整的渲染流程。
 */
public final class RenderPipeline {

    public static final RenderPipeline INSTANCE = new RenderPipeline();

    private final OutlineRenderer renderer;
    private final ShapeDetector shapeDetector;

    private RenderPipeline() {
        this.renderer = OutlineRenderer.INSTANCE;
        this.shapeDetector = ShapeDetector.INSTANCE;
    }

    /**
     * 检测物品形状。
     */
    public ShapeResult detectShape(ItemStack stack, BakedModel model, ItemTypeInfo typeInfo) {
        return shapeDetector.detect(stack, model, typeInfo);
    }

    /**
     * 渲染 GUI 物品。
     */
    public void renderGuiOutline(PoseStack pose, BakedModel model, int x, int y, int quadSize,
                                  int color, float thickness) {
        renderer.renderOutline(pose, model, x, y, quadSize, color, thickness);
    }

    /**
     * 渲染手持物品。
     */
    public void renderHandOutline(BakedModel model, PoseStack pose, int color, float thickness) {
        renderer.renderHandOutline(model, pose, color, thickness);
    }

    /**
     * 渲染 BEWLR 物品。
     */
    public boolean renderBewlrOutline(ItemStack stack, PoseStack pose, int color, float thickness) {
        return renderer.renderBewlrEntityOutline(stack, pose, color, thickness);
    }

    /**
     * 渲染盔甲。
     */
    public boolean renderArmorOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                       EquipmentSlot slot, int color, float thickness,
                                       HumanoidModel<?> baseModel) {
        // 检查是否为 AzureLib 盔甲
        if (renderer.isAzureLibArmor(stack)) {
            return renderer.renderAzureLibArmorOutline(pose, stack, entity, slot, color, thickness, baseModel);
        }
        
        // 检查是否为 GeckoLib 盔甲
        if (renderer.isGeckoLibArmor(stack)) {
            return renderer.renderGeckoLibArmorOutline(pose, stack, entity, slot, color, thickness, baseModel);
        }
        
        // 默认处理
        return false;
    }

    /**
     * 渲染投掷物。
     */
    public boolean renderProjectileOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                            int color, float thickness) {
        // TODO: 实现投掷物渲染
        return false;
    }

    /**
     * 渲染鞘翅。
     */
    public boolean renderElytraOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                        int color, float thickness) {
        // TODO: 实现鞘翅渲染
        return false;
    }

    /**
     * 清空缓存。
     */
    public void invalidateCaches() {
        renderer.invalidateCaches();
    }
}
