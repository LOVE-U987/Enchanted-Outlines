package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI 物品描边钩子。
 * <p>
 * 注入 {@link GuiGraphics} 私有 7 参 {@code renderItem} 的 HEAD——这是所有公开
 * renderItem 重载(含快捷栏 HUD、背包、容器界面)的唯一漏斗。描边 pass 在物品本体
 * 之前写入同一个 BufferSource,flush 时先绘制 → 描边垫底、物品覆盖中心,露出外扩环。
 * <p>
 * 跳过:总开关关闭、物品为空、无附魔、entity == null(renderFakeItem / JEI 幽灵)、
 * BEWLR 自定义渲染物品(盾牌、钓鱼竿等,占位模型无形状)。
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {

    /** 复刻 ItemRenderer.render 在 GUI 下对特殊物品的模型替换(与 ItemRenderer 同源)。 */
    private static final ModelResourceLocation TRIDENT_MODEL =
            new ModelResourceLocation(ResourceLocation.withDefaultNamespace("trident"), "inventory");
    private static final ModelResourceLocation SPYGLASS_MODEL =
            new ModelResourceLocation(ResourceLocation.withDefaultNamespace("spyglass"), "inventory");

    @Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
            at = @At("HEAD"))
    private void enchantedoutlines$renderOutline(LivingEntity entity, Level level, ItemStack stack,
                                                 int x, int y, int seed, int quadSize, CallbackInfo ci) {
        if (stack.isEmpty() || entity == null || !Config.ENABLE.get()) {
            return;
        }
        int color = ColorResolver.resolve(stack);
        if (color == -1) {
            return;
        }
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, level, entity, seed);
        float thickness = ColorResolver.thickness(stack);
        if (thickness <= 0f) {
            return;
        }
        // 三叉戟/望远镜在 GUI 下用完整模型渲染,复刻 ItemRenderer.render 的替换;
        // 盾牌是 BEWLR,用近似盒模型描边
        if (stack.is(Items.TRIDENT)) {
            model = itemRenderer.getItemModelShaper().getModelManager().getModel(TRIDENT_MODEL);
        } else if (stack.is(Items.SPYGLASS)) {
            model = itemRenderer.getItemModelShaper().getModelManager().getModel(SPYGLASS_MODEL);
        } else if (stack.is(Items.SHIELD)) {
            // 传当前解析出的模型 transforms(GUI 下 blocking/non-blocking 同用 gui display,无差异)
            model = OutlineRenderer.INSTANCE.shieldModel(model.getTransforms());
        }
        // ⚠️ 1.20.1 的 BakedModel 接口没有 applyTransform(1.21.1 NeoForge 独有),且
        // forge:separate_transforms 的 Baked 模型(1.20.1)getQuads 直接委托 base(几何
        // 非空)、isCustomRenderer 委托 base → 本体 GUI 渲染与描边取同一模型即一致,
        // 无需子模型切换(GUI display 变换由 renderOutline 内部按 model.getTransforms()
        // 应用,与本体 ItemRenderer.render 的 getTransforms().apply 一致)。
        if (model.isCustomRenderer()) {
            // BEWLR 物品(GUI,如永恒星光月弧长枪):本体在 ItemRenderer.render 内被替换成
            // 平面 inventory 模型渲染,描边用同一模型才能与物品图标本体对齐
            // (见 OutlineRenderer.inventoryModelFor)。无平面变体(如灾变武器,本体 GUI
            // 由 BEWLR 3D 渲染)→ 用 renderBewlrEntityOutline 反射模型做放大壳。
            // ⚠️ 有平面变体时必须用平面描边(bewlr3dPrefer 已废弃):本体 GUI 是平面
            // 模型,3D 放大壳套上去必然错位(2026-08-09 长枪背包错位根因)。
            BakedModel inventoryModel = OutlineRenderer.INSTANCE.inventoryModelFor(stack);
            if (inventoryModel == null) {
                GuiGraphics self = (GuiGraphics) (Object) this;
                PoseStack pose = self.pose();
                pose.pushPose();
                try {
                    // 复刻 renderOutline 的 GUI 3D 变换链(x/y 格中心 + scale + GUI display)
                    pose.translate(x + 8, y + 8, 150 + quadSize);
                    pose.scale(16.0F, -16.0F, 16.0F);
                    model.getTransforms().getTransform(ItemDisplayContext.GUI).apply(false, pose);
                    pose.translate(-0.5F, -0.5F, -0.5F);
                    if (!OutlineRenderer.INSTANCE.renderBewlrEntityOutline(stack, pose, color, thickness)) {
                        return;
                    }
                } finally {
                    pose.popPose();
                }
                return; // 已画完放大壳
            }
            model = inventoryModel;
        }

        GuiGraphics self = (GuiGraphics) (Object) this;
        OutlineRenderer.INSTANCE.renderOutline(
                self.pose(), model, x, y, quadSize, color, thickness);
    }
}
