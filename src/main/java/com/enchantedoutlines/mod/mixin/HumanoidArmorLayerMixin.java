package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.client.ForgeHooksClient;

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
     * 1.20.1 的 renderArmorPiece 是 6 参版本(1.21.1 才是 12 参):
     * (PoseStack, MultiBufferSource, LivingEntity, EquipmentSlot, int, HumanoidModel)
     * Forge patch 后方法体内(HEAD 之后)会:
     *   1. getArmorModelHook(entity, stack, slot, model) → ForgeHooksClient.getArmorModel;
     *   2. getArmorResource(entity, stack, slot, type) → ForgeHooksClient.getArmorTexture hook。
     * 描边必须复刻同一 hook(见 AGENTS.md 统一轮廓语义),否则模组自定义模型/纹理
     * (如永恒星光热泉石盔甲)描边缺角/实心/路径不存在。
     */
    @Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;)V",
            at = @At("HEAD"))
    private void enchantedoutlines$armorOutline(PoseStack pose, MultiBufferSource buffers,
                                                LivingEntity entity, EquipmentSlot slot, int light,
                                                HumanoidModel<?> armorModel, CallbackInfo ci) {
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
        // ⚠️ 本体在 renderArmorPiece 方法体内(HEAD 之后)通过 Forge hook 替换模型与纹理,
        // 必须调用同一 hook 才能与本体 100% 一致(见 AGENTS.md 统一轮廓语义):
        //   - 模型:ForgeHooksClient.getArmorModel → IClientItemExtensions.getGenericArmorModel
        //     → getHumanoidArmorModel。永恒星光热泉石盔甲返回带角的 ThermalSpringStoneArmorModel,
        //     直接用 HEAD 参数的原版 HumanoidArmorModel 描边会缺角/轮廓与本体不符;
        //   - 纹理:HumanoidArmorLayer.getArmorResource(public,Forge 新增)→
        //     ForgeHooksClient.getArmorTexture。热泉石盔甲自定义纹理路径
        //     textures/armor/...(非标准 textures/models/armor/),必须走同一解析
        //     (getArmorResource 内部已包含该 hook),否则路径不存在 → 形状纹理读取
        //     失败 → 描边变实心错误轮廓。
        Model hookModel = ForgeHooksClient.getArmorModel(entity, stack, slot, armorModel);
        HumanoidModel<?> outlineModel = hookModel instanceof HumanoidModel<?> humanoid ? humanoid : armorModel;
        // 基础材质即可(描边只取 alpha 遮罩;overlay 染色层形状相同)。
        // getArmorResource 是 1.20.1 Forge 为 HumanoidArmorLayer 新增的 public 方法,
        // 与本体重叠调用同一解析路径(内部含 ForgeHooksClient.getArmorTexture hook)。
        ResourceLocation texture = ((HumanoidArmorLayer<?, ?, ?>) (Object) this)
                .getArmorResource(entity, stack, slot, null);

        // HEAD 注入发生在方法体的 copyPropertiesTo(把父模型姿势复制到盔甲模型)之前,
        // 先手动复制姿势,否则描边壳会沿用默认姿势而与本体错位。
        // 泛型捕获问题用 raw 类型绕过(copyPropertiesTo 擦除后参数即 HumanoidModel)。
        @SuppressWarnings({"rawtypes", "unchecked"})
        HumanoidModel parent = (HumanoidModel) ((HumanoidArmorLayer<?, ?, ?>) (Object) this).getParentModel();
        parent.copyPropertiesTo(outlineModel);

        OutlineRenderer.INSTANCE.renderArmorOutline(pose, outlineModel, texture, color, thickness, slot);
    }
}
