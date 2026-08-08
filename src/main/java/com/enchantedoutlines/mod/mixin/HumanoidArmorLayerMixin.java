package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.EnchantedOutlines;
import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 盔甲穿戴描边钩子(穿在玩家身上的盔甲)。
 * <p>
 * 注入 {@link HumanoidArmorLayer#renderArmorPiece} 的 HEAD(每个盔甲槽位渲染一次):
 * 在本体渲染前,用同一盔甲模型的<b>绕人形几何中心放大壳</b>以描边色渲染,
 * 本体随后覆盖中心,露出外扩环。只描带附魔的盔甲部件。
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

    /**
     * NeoForge 把 render 改为直接调用 12 参 renderArmorPiece(6 参旧重载已 @Deprecated
     * 且运行时不再被调用)。必须注入 12 参版本,否则永远不会触发:
     * (PoseStack, MultiBufferSource, LivingEntity, EquipmentSlot, int, HumanoidModel, float×6)
     */
    @Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at = @At("HEAD"))
    private void enchantedoutlines$armorOutline(PoseStack pose, MultiBufferSource buffers,
                                                LivingEntity entity, EquipmentSlot slot, int light,
                                                HumanoidModel<?> armorModel,
                                                float limbSwing, float limbSwingAmount, float partialTick,
                                                float ageInTicks, float netHeadYaw, float headPitch,
                                                CallbackInfo ci) {
        if (!Config.ENABLE.get()) {
            return;
        }
        ItemStack stack = entity.getItemBySlot(slot);
        if (!(stack.getItem() instanceof ArmorItem armorItem)) {
            return;
        }
        if (armorItem.getEquipmentSlot() != slot) {
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
        ArmorMaterial material = armorItem.getMaterial().value();
        var layers = material.layers();
        if (layers.isEmpty()) {
            return;
        }
        // 基础材质即可(描边只取 alpha 遮罩;染色 layer 形状相同)
        ResourceLocation texture = layers.get(0).texture(slot == EquipmentSlot.LEGS);

        // HEAD 注入发生在方法体的 copyPropertiesTo(把父模型姿势复制到盔甲模型)之前,
        // 先手动复制姿势,否则描边壳会沿用默认姿势而与本体错位。
        // 泛型捕获问题用 raw 类型绕过(copyPropertiesTo 擦除后参数即 HumanoidModel)。
        @SuppressWarnings({"rawtypes", "unchecked"})
        HumanoidModel parent = (HumanoidModel) ((HumanoidArmorLayer<?, ?, ?>) (Object) this).getParentModel();
        parent.copyPropertiesTo(armorModel);

        OutlineRenderer.INSTANCE.renderArmorOutline(pose, armorModel, texture, color, thickness, slot);
    }
}
