package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 手持描边钩子(第一/第三人称世界渲染)。
 * <p>
 * 注入 {@link ItemRenderer#render} 的 HEAD——第一人称(主手/副手)、第三人称
 * (玩家左右手)、掉落物(GROUND)与物品展示框(FIXED)的物品渲染都汇聚于此。
 * 描边用壳渲染,壳不写深度,物品本体随后覆盖中心,露出外扩环。
 * <ul>
 *   <li>手持/掉落物(GROUND):boost=1.0 半透明发光(第一人称近距美观);</li>
 *   <li>物品展示框(FIXED):物品是背景物体(贴墙/距玩家远),半透明描边在明亮场景中
 *       几乎不可见 → 硬切不透明(OutlineCutout,非透明像素 alpha=1.0 纯色,偏移不变)。</li>
 * </ul>
 * 跳过:GUI(由 {@link GuiGraphicsMixin} 处理)、NONE 等上下文、无附魔、
 * 其余 BEWLR 自定义渲染物品(钓鱼竿等占位模型无形状)。
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    /** 复刻 ItemRenderer.render 在 GUI/GROUND/FIXED 下对特殊物品的模型替换(与 ItemRenderer 同源)。 */
    private static final ModelResourceLocation TRIDENT_MODEL =
            ModelResourceLocation.inventory(ResourceLocation.withDefaultNamespace("trident"));
    private static final ModelResourceLocation SPYGLASS_MODEL =
            ModelResourceLocation.inventory(ResourceLocation.withDefaultNamespace("spyglass"));

    @SuppressWarnings("deprecation") // getTransforms 在 1.21.1 已过时但 vanilla ItemRenderer 同款使用,无更优替代
    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At("HEAD"))
    private void enchantedoutlines$renderHandOutline(ItemStack stack, ItemDisplayContext context, boolean leftHand,
                                                     PoseStack pose, MultiBufferSource buffers,
                                                     int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (stack.isEmpty() || !Config.ENABLE.get()) {
            return;
        }
        // 手持 + 掉落物 + 物品展示框(FIXED);GUI 由 GuiGraphicsMixin 负责
        if (!context.firstPerson()
                && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                && context != ItemDisplayContext.GROUND
                && context != ItemDisplayContext.FIXED) {
            return;
        }
        int color = ColorResolver.resolve(stack);
        if (color == -1) {
            return;
        }
        // ItemRenderer.render 对 GUI/GROUND/FIXED 三类上下文做三叉戟/望远镜平面模型替换
        boolean guiLike = context == ItemDisplayContext.GUI
                || context == ItemDisplayContext.GROUND
                || context == ItemDisplayContext.FIXED;

        // 盾牌/三叉戟是 BEWLR(占位模型无形状),用近似盒模型描边;其余 BEWLR 物品跳过。
        // 三叉戟:
        //  手持(含投掷)本体 = BEWLR 渲染 TridentModel 实体模型 → 用 S(1,-1,-1) 翻转盒描边;
        //  掉落物/展示框本体 = ItemRenderer.render 内部替换为 TRIDENT_MODEL(平面 generated
        //  模型,走 model lists)→ 描边也用 TRIDENT_MODEL 平面放大壳,与本体一致。
        if (stack.is(Items.SHIELD)) {
            model = OutlineRenderer.INSTANCE.shieldModel(model.getTransforms());
        } else if (stack.is(Items.TRIDENT)) {
            if (guiLike) {
                model = getModel(TRIDENT_MODEL);
            } else {
                model = OutlineRenderer.INSTANCE.tridentModel(model.getTransforms());
            }
        } else if (stack.is(Items.SPYGLASS) && guiLike) {
            // 掉落物/展示框望远镜:本体同样被 ItemRenderer.render 替换为 SPYGLASS_MODEL(平面)
            model = getModel(SPYGLASS_MODEL);
        } else if (model.isCustomRenderer()) {
            return;
        }
        float thickness = ColorResolver.thickness(stack);
        if (thickness <= 0f) {
            return;
        }

        // 复刻 render 方法体的 display 变换(HEAD 注入时尚未应用)
        pose.pushPose();
        try {
            model.getTransforms().getTransform(context).apply(leftHand, pose);
            pose.translate(-0.5F, -0.5F, -0.5F);
            if (context == ItemDisplayContext.FIXED) {
                // 物品展示框:物品是背景物体(贴墙/距玩家远),半透明描边(即使 boost 抬升)
                // 在明亮场景中仍透出背景几乎不可见 → 硬切不透明:非透明像素一律
                // alpha=1.0 纯色,轮廓完全不透明。偏移不变。
                OutlineRenderer.INSTANCE.renderHandOutline(model, pose, color, thickness, 2.0f, true);
            } else {
                OutlineRenderer.INSTANCE.renderHandOutline(model, pose, color, thickness);
            }
        } finally {
            pose.popPose();
        }
    }

    /** 从 ModelManager 取模型(与 ItemRenderer.render 内部替换逻辑同源)。 */
    private static BakedModel getModel(ModelResourceLocation mrl) {
        ModelManager manager = Minecraft.getInstance().getItemRenderer().getItemModelShaper().getModelManager();
        return manager.getModel(mrl);
    }
}
