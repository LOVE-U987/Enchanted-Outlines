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
        // ⚠️ 玩家手持(第一/第三人称)物品:若当前由 ItemInHandRenderer.renderItem 驱动
        // (ItemInHandLayer 玩家手持层 / 原版第一人称 renderHandsWithItems),描边已在
        // ItemInHandRendererMixin HEAD 渲染 —— 该注入点 pose 已含 Better Combat/
        // PlayerAnimator 的攻击动画变换,描边与本体 100% 同步(issue #2)。此处跳过,
        // 避免"静态位置描边 + 动画位置描边"重复。GROUND/FIXED 不经 ItemInHandRenderer,
        // 仍由本方法处理。
        if (context.firstPerson()
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
            if (OutlineRenderer.isInItemInHandRenderer()) {
                return;
            }
        }
        int color = ColorResolver.resolve(stack);
        if (color == -1) {
            return;
        }
        // ItemRenderer.render 对 GUI/GROUND/FIXED 三类上下文做三叉戟/望远镜平面模型替换
        boolean guiLike = context == ItemDisplayContext.GUI
                || context == ItemDisplayContext.GROUND
                || context == ItemDisplayContext.FIXED;
        boolean bewlrHandheld = false; // 手持 BEWLR 物品:本体 = BEWLR 自定义实体模型

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
        }
        float thickness = ColorResolver.thickness(stack);
        if (thickness <= 0f) {
            return;
        }

        // 复刻 render 方法体的 display 变换(HEAD 注入时尚未应用)。
        // ⚠️ 必须用 applyTransform 而非手动 getTransforms().apply:资源包可用
        // neoforge:separate_transforms loader 把物品拆成多视角子模型(如 XIM 资源包把
        // 灾变武器 GUI/FIXED 换成 2D 平面、手持保留 3D builtin/entity base)。
        // 本体 ItemRenderer.render 渲染前就是 model.applyTransform(context) 按视角选子模型;
        // 描边必须拿同一子模型,否则 getQuads() 取到 base(空几何)无描边、isCustomRenderer()
        // 恒 false 不进 BEWLR 分支(2026-08-09 XIM 资源包不兼容根因)。
        // 先 pushPose 再应用变换:描边后 popPose 恢复,本体随后自行应用同一变换,不重复。
        pose.pushPose();
        try {
            model = model.applyTransform(context, pose, leftHand);
            pose.translate(-0.5F, -0.5F, -0.5F);
            if (model.isCustomRenderer()) {
                // 子模型是 builtin/entity(手持 base 或 GUI 无平面变体):本体由 BEWLR 渲染
                // 自定义实体模型 → 3D 放大壳描边。
                //   GUI/GROUND/FIXED:有平面模型变体的用平面描边(背包/掉落物图标清晰,
                //   与本体一致);⚠️ 无平面变体(bewlr3dPrefer 已废弃)才用 3D 放大壳
                //   (灾变武器 GUI 本体就是 BEWLR 3D,3D 描边才对齐)。
                if (guiLike) {
                    BakedModel inventoryModel = OutlineRenderer.INSTANCE.inventoryModelFor(stack);
                    if (inventoryModel != null) {
                        model = inventoryModel;
                    } else {
                        // 无平面变体(GROUND/FIXED 下本体仍是 BEWLR 3D,如灾变武器)→ 放大壳
                        bewlrHandheld = true;
                    }
                } else {
                    // 手持:本体 = BEWLR 3D 实体模型 → 3D 放大壳描边
                    bewlrHandheld = true;
                }
            }
            // 子模型不是 custom renderer(如 separate_transforms 的 2D 平面 GUI 子模型,
            // 本体就是普通平面渲染)→ 保持 model,下方走 renderHandOutline 平面描边,与本体一致。

            if (bewlrHandheld) {
                // 本体手持 = BEWLR 自定义实体模型(模组渲染器内部无额外姿态变换,直接
                // renderToBuffer)→ 反射模型做逐 cube/整体放大壳,与本体同变换对齐。
                if (!OutlineRenderer.INSTANCE.renderBewlrEntityOutline(stack, pose, color, thickness)) {
                    return; // 拿不到模型:跳过(与修复前一致)
                }
            } else if (context == ItemDisplayContext.FIXED) {
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
