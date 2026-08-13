package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
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
 * 玩家手持物品描边钩子({@link ItemInHandRenderer#renderItem} 层)。
 * <p>
 * <b>为什么需要本 mixin(Better Combat 兼容,issue #2):</b>Better Combat(含其依赖的
 * PlayerAnimator)在攻击动画期间,玩家手持物品的本体渲染经过
 * {@code ItemInHandLayer.renderArmWithItem} → {@code ItemInHandRenderer.renderItem}:
 * <pre>
 * ItemInHandLayer.renderArmWithItem
 *   ├─ translateToHand(arm, pose)                        手部变换
 *   ├─ mulPose/translate(...)                            固定变换
 *   ├─ [PlayerAnimator changeItemLocation]               动画变换(scale/translate/rotate)
 *   ├─ ItemInHandRenderer.renderItem(entity, stack, context, leftHand, pose, ...)
 *   │   ├─ ItemRenderer.renderStatic → ItemRenderer.render 8 参(本体,含 display transform)
 *   │   └─ [本 mixin HEAD 描边:pose 已含动画变换,与本体一致]
 * </pre>
 * 本体最终经过 {@code ItemRenderer.render} 8 参,但<b>动画变换是在 ItemInHandRenderer
 * 层传入的 pose 上</b>——若描边仍在 8 参 render 的 HEAD,一旦 PlayerAnimator 版本的
 * 变换时机/路径有差异(第一人称 THIRD_PERSON_MODEL 模式:取消原版第一人称、改由玩家
 * 实体 + PlayerItemInHandLayer 渲染),描边就会留在原版静态位置、与动画中的武器本体分离。
 * 本 mixin 把玩家手持描边提前到 {@code ItemInHandRenderer.renderItem} 的 HEAD:
 * 该注入点 pose 与本体<b>完全一致</b>(已含 Better Combat 动画变换),描边先画垫底、
 * 本体随后覆盖中心,与本体 100% 同步。
 * <p>
 * 同时用静态标记告知 {@link ItemRendererMixin} 跳过同一物品在 8 参 render HEAD 的
 * 重复描边(避免"静态描边 + 动画描边"同时出现)。
 * <p>
 * 覆盖范围:第一人称(原版 ItemInHandRenderer.renderHandsWithItems 与 PlayerAnimator
 * PlayerItemInHandLayer 都汇聚到此)与第三人称(ItemInHandLayer.renderArmWithItem)。
 * 掉落物(GROUND)/展示框(FIXED)不经本类,仍由 {@link ItemRendererMixin} 处理。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    /**
     * 手持描边钩子:在 {@code ItemInHandRenderer.renderItem} HEAD 渲染描边。
     * <p>
     * 此时 pose 已含 ItemInHandLayer 手部变换与 PlayerAnimator 动画变换(调用方应用),
     * 本体随后在同一 pose 上应用 display transform 渲染 → 描边与本体一致且跟随动画。
     *
     * @param entity    手持物品的实体(玩家)
     * @param stack     手持物品
     * @param context   渲染上下文(第一/第三人称手持)
     * @param leftHand  是否左手
     * @param pose      已含调用方变换的 PoseStack
     * @param buffers   本体顶点缓冲(描边用独立缓冲,不取用)
     * @param light     本体光照(描边恒全亮)
     */
    @Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void enchantedoutlines$renderHandOutline(LivingEntity entity, ItemStack stack,
                                                     ItemDisplayContext context, boolean leftHand,
                                                     PoseStack pose, MultiBufferSource buffers,
                                                     int light, CallbackInfo ci) {
        // 标记当前处于 ItemInHandRenderer 上下文,让 ItemRendererMixin 跳过重复描边
        OutlineRenderer.markItemInHandRender(true);
        try {
            // 描边失败绝不影响本体渲染:内部全链路 try-catch 兜底
            renderItemOutline(entity, stack, context, leftHand, pose);
        } catch (Throwable ignored) {
            // 描边是附加效果,任何异常都静默忽略
        }
    }

    /** 恢复标记(renderItem 方法体正常返回时)。 */
    @Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void enchantedoutlines$restoreFlag(LivingEntity entity, ItemStack stack,
                                               ItemDisplayContext context, boolean leftHand,
                                               PoseStack pose, MultiBufferSource buffers,
                                               int light, CallbackInfo ci) {
        OutlineRenderer.markItemInHandRender(false);
    }

    /**
     * 手持描边主体:解析模型并渲染(与 {@link ItemRendererMixin} 的手持分支同构)。
     * <p>
     * 模型解析用与本体 {@code renderStatic} 相同的 seed
     * ({@code entity.getId() + context.ordinal()}),保证动画物品/NBT 变体一致。
     *
     * @param entity    手持物品的实体(玩家)
     * @param stack     手持物品
     * @param context   渲染上下文(第一/第三人称手持)
     * @param leftHand  是否左手
     * @param pose      已含调用方变换的 PoseStack
     */
    private static void renderItemOutline(LivingEntity entity, ItemStack stack,
                                          ItemDisplayContext context, boolean leftHand,
                                          PoseStack pose) {
        if (stack.isEmpty() || !Config.ENABLE.get()) {
            return;
        }
        // 只处理玩家手持(第一/第三人称);GUI/GROUND/FIXED 不经 ItemInHandRenderer
        if (!context.firstPerson()
                && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
            return;
        }
        int color = ColorResolver.resolve(stack);
        if (color == -1) {
            return;
        }
        float thickness = ColorResolver.thickness(stack);
        if (thickness <= 0f) {
            return;
        }
        Level level = entity != null ? entity.level() : Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        // seed 与本体 renderItem → renderStatic 的 seed 完全一致
        int seed = entity.getId() + context.ordinal();
        BakedModel model = itemRenderer.getModel(stack, level, entity, seed);

        // 盾牌/三叉戟手持本体是 BEWLR(占位模型无几何),用近似盒模型描边
        // (transforms 取当前解析出的模型,blocking/投掷等 display 变化跟随本体)。
        if (stack.is(Items.SHIELD)) {
            model = OutlineRenderer.INSTANCE.shieldModel(model.getTransforms());
        } else if (stack.is(Items.TRIDENT)) {
            model = OutlineRenderer.INSTANCE.tridentModel(model.getTransforms());
        }

        // ⚠️ 必须用 applyTransform 复刻本体 render 的 display 变换(HEAD 注入时本体尚未
        // 应用);资源包 separate_transforms 子模型(如 XIM 把 GUI/FIXED 换 2D 平面)在
        // 手持下 applyTransform 返回对应视角子模型,与本体一致(见 ItemRendererMixin 注释)。
        pose.pushPose();
        try {
            model = model.applyTransform(context, pose, leftHand);
            pose.translate(-0.5F, -0.5F, -0.5F);
            if (model.isCustomRenderer()) {
                // 手持 BEWLR 物品(永恒星光/灾变武器等):反射模型做 3D 放大壳描边
                if (!OutlineRenderer.INSTANCE.renderBewlrEntityOutline(stack, pose, color, thickness)) {
                    return; // 拿不到模型:跳过(与 ItemRendererMixin 一致)
                }
            } else {
                OutlineRenderer.INSTANCE.renderHandOutline(model, pose, color, thickness);
            }
        } finally {
            pose.popPose();
        }
    }
}
