package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鞘翅穿戴描边钩子。
 * <p>
 * 注入 {@link ElytraLayer#render} 的 TAIL:当 chest 槽位是<b>附魔鞘翅</b>时,
 * 对左右两翼逐 cube 绕自身包围盒中心放大壳描边。
 * <p>
 * <b>为什么用 TAIL 而不是 HEAD</b>:描边必须与本体姿态 100% 一致。本体渲染流程是
 * {@code translate(0,0,0.125) → copyPropertiesTo → setupAnim → renderToBuffer},
 * 其中 EMF(Entity Model Features)/资源包(如 FA+Player 的 elytra.jem)的自定义动画
 * 是在 {@code renderToBuffer → EMFModelPartWithState.render → root.animate()} 内部应用的。
 * HEAD 注入时动画尚未应用,需要复刻 setupAnim + 手动触发动画,任何细微差异都会错位;
 * TAIL 注入时本体已渲染完毕,{@code elytraModel} 的姿态就是本体实际渲染用的最终姿态,
 * 直接复用即可,无需复刻任何逻辑。
 * <p>
 * <b>依赖说明</b>:本实现<b>不依赖 EMF(Entity Model Features)及其前置</b>
 * (如 ETF),也不引用任何 EMF/ETF 类 —— 无编译期/运行期硬依赖。
 * 有 EMF 时,本体最终姿态已包含其自定义动画,描边自动同步;无 EMF 时,
 * 本体姿态即原版 {@code setupAnim} 结果,描边同样正确。同一套代码两种场景通用。
 * <p>
 * 鞘翅本体由 {@code ElytraLayer} 直接渲染 ElytraModel(不经 ItemRenderer),
 * 且 ElytraLayer 没有对鞘翅本身的渲染 mixin → 需单独注入。
 */
@Mixin(ElytraLayer.class)
public abstract class ElytraLayerMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("TAIL"))
    private void enchantedoutlines$elytraOutline(PoseStack pose, MultiBufferSource buffers, int light,
                                                 LivingEntity entity, float limbSwing, float limbSwingAmount,
                                                 float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
                                                 CallbackInfo ci) {
        if (!Config.ENABLE.get()) {
            return;
        }
        ItemStack stack = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (!stack.is(Items.ELYTRA) || !stack.hasFoil()) {
            return;
        }
        int color = ColorResolver.resolve(stack);
        if (color == -1) {
            return;
        }
        float thickness = ColorResolver.armorThickness(stack);
        if (thickness <= 0f) {
            return;
        }

        // 关键:必须使用 ElytraLayer 正在渲染本体用的同一个 elytraModel 实例,
        // 描边与本体姿势 100% 同步(static 缓存的独立实例会因跨帧/多实体渲染
        // 姿势残留而错位)。
        ElytraModel<?> elytra = ((ElytraLayerAccessor) (Object) this).enchantedoutlines$getElytraModel();
        if (elytra == null) {
            return;
        }

        // TAIL 时机:本体 renderToBuffer 已执行完毕,elytraModel 的姿态已是最终姿态
        // (包括原版 setupAnim 与 EMF/FA+Player 自定义动画)。直接复用渲染描边,
        // 无需复刻 copyPropertiesTo/setupAnim/EMF animate —— 与本体 100% 一致。
        // 深度测试保证:本体(cutout,先画,写深度)遮挡描边放大壳的内侧表面,
        // 描边(translucent,后画)只显示外扩边缘,不会污染本体表面。
        pose.pushPose();
        try {
            pose.translate(0.0F, 0.0F, 0.125F);
            // 左右翼片逐 cube 放大壳描边
            ElytraModelAccessor acc = (ElytraModelAccessor) elytra;
            ModelPart[] wings = {acc.enchantedoutlines$getLeftWing(), acc.enchantedoutlines$getRightWing()};
            OutlineRenderer.INSTANCE.renderElytraOutline(pose, wings, color, thickness);
        } finally {
            pose.popPose();
        }
    }
}
