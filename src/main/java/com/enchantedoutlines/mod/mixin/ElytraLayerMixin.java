package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鞘翅穿戴描边钩子。
 * <p>
 * 注入 {@link ElytraLayer#render} 的 HEAD:当 chest 槽位是<b>附魔鞘翅</b>时,
 * 复刻方法体前置变换(translate z+0.125 + copyPropertiesTo + setupAnim),
 * 再对左右两翼逐 cube 绕自身包围盒中心放大壳描边,本体随后覆盖。
 * <p>
 * 鞘翅本体由 {@code ElytraLayer} 直接渲染 ElytraModel(不经 ItemRenderer),
 * 且 ElytraLayer 没有对鞘翅本身的渲染 mixin → 需单独注入。
 */
@Mixin(ElytraLayer.class)
public abstract class ElytraLayerMixin {

    /** 缓存的烘焙鞘翅模型(EntityModelSet.bakeLayer 每次调用会新建 ModelPart 树,缓存避免每帧分配)。 */
    @Unique
    private static ElytraModel<?> cachedElytra;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"))
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

        // 复刻方法体:translate + 从父模型复制属性 + setupAnim(让翼片姿态与本体一致)
        // 注意方法体 setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch),
        // partialTick 不在 setupAnim 参数里(它是第 3 个 float)。
        pose.pushPose();
        try {
            pose.translate(0.0F, 0.0F, 0.125F);
            ElytraModel<?> elytra = cachedElytra;
            if (elytra == null) {
                elytra = new ElytraModel<>(
                        Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.ELYTRA));
                cachedElytra = elytra;
            }
            EntityModel parent = (EntityModel) ((ElytraLayer) (Object) this).getParentModel();
            parent.copyPropertiesTo(elytra);
            // raw 类型绕过泛型捕获(setupAnim 擦除后参数即 LivingEntity)
            @SuppressWarnings("rawtypes")
            ElytraModel raw = elytra;
            raw.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            // 左右翼片逐 cube 放大壳描边
            ElytraModelAccessor acc = (ElytraModelAccessor) elytra;
            ModelPart[] wings = {acc.enchantedoutlines$getLeftWing(), acc.enchantedoutlines$getRightWing()};
            OutlineRenderer.INSTANCE.renderElytraOutline(pose, wings, color, thickness);
        } finally {
            pose.popPose();
        }
    }
}
