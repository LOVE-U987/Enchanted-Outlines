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
            ModelResourceLocation.inventory(ResourceLocation.withDefaultNamespace("trident"));
    private static final ModelResourceLocation SPYGLASS_MODEL =
            ModelResourceLocation.inventory(ResourceLocation.withDefaultNamespace("spyglass"));

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
        // ⚠️ 先取 GUI 视角子模型(2026-08-09 XIM 资源包兼容):资源包可用
        // neoforge:separate_transforms loader 把物品拆成多视角子模型(灾变武器 GUI = 2D
        // 平面 item/generated,手持 = 3D builtin/entity base)。本体 GuiGraphics.renderItem
        // → ItemRenderer.render 渲染前就是 model.applyTransform(GUI) 按视角选子模型,描边
        // 必须拿同一子模型:否则 getQuads() 取到 base(空几何)无描边、isCustomRenderer()
        // 恒 false 不走 BEWLR 分支。用临时 PoseStack 只取子模型(display 变换由
        // renderOutline 内部基于子模型 getTransforms() 应用,与本体 applyTransform 一致);
        // 对普通 BakedModel applyTransform 默认返回 this,行为不变。
        {
            BakedModel guiSub = model.applyTransform(ItemDisplayContext.GUI, new PoseStack(), false);
            model = guiSub;
        }
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
