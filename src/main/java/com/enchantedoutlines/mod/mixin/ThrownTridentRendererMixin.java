package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.EnchantedOutlines;
import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ThrownTridentRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 三叉戟投掷物实体描边钩子。
 * <p>
 * 投掷出去的 {@link ThrownTrident} 由 {@link ThrownTridentRenderer} 直接渲染
 * TridentModel(实体模型),不经过 {@code ItemRenderer.render} → 手持 mixin 覆盖不到。
 * <p>
 * 在 render 的 HEAD 注入:复刻方法体开头的 Y/Z 旋转(投掷方向),再用三叉戟
 * 实体模型绕各 cube 中心放大壳描边(与盔甲同一套算法),本体随后覆盖。
 * <p>
 * <b>附魔判断用 {@code trident.isFoil()}(ID_FOIL 布尔随 spawn 数据同步,原版渲染器
 * 也靠它画箔光),不能用 {@code getPickupItemStackOrigin()} 的附魔</b>:客户端实体由
 * {@code (EntityType, Level)} 构造器创建,{@code pickupItemStack} 字段固定为
 * {@code getDefaultPickupItem()}(无附魔三叉戟)且不随 SynchedEntityData 同步 →
 * 客户端永远拿不到投掷物的附魔列表,颜色退化为物品固定色/默认色
 * ({@link ColorResolver#resolveFoilOnly})。
 */
@Mixin(ThrownTridentRenderer.class)
public abstract class ThrownTridentRendererMixin {

    /** 缓存的烘焙三叉戟模型根(EntityModelSet.bakeLayer 每次调用都会新建 ModelPart 树,缓存避免每帧分配)。 */
    @Unique
    private ModelPart cachedTridentRoot;

    @Inject(method = "render(Lnet/minecraft/world/entity/projectile/ThrownTrident;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void enchantedoutlines$thrownTridentOutline(ThrownTrident trident, float yRot, float partialTick,
                                                        PoseStack pose, MultiBufferSource buffers, int light,
                                                        CallbackInfo ci) {
        if (!Config.ENABLE.get() || !trident.isFoil()) {
            return;
        }
        ItemStack stack = trident.getPickupItemStackOrigin();
        if (stack.isEmpty()) {
            return;
        }
        // 客户端拿不到投掷物的附魔列表(pickupItemStack 不同步),颜色退化:
        // 物品禁用 → -1;物品固定色(程序化>配置) → 该色;否则默认色。
        int color = ColorResolver.resolveFoilOnly(stack);
        if (color == -1) {
            return;
        }
        float thickness = ColorResolver.thickness(stack);
        if (thickness <= 0f) {
            return;
        }

        // 复刻 ThrownTridentRenderer.render 开头的旋转(Y 偏航 + Z 俯仰)
        pose.pushPose();
        try {
            pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, trident.yRotO, trident.getYRot()) - 90.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, trident.xRotO, trident.getXRot()) + 90.0F));
            // 三叉戟实体模型 root(与 ThrownTridentRenderer.model 同一 bake 层)
            ModelPart root = cachedTridentRoot;
            if (root == null) {
                root = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.TRIDENT);
                cachedTridentRoot = root;
            }
            OutlineRenderer.INSTANCE.renderEntityModelOutline(pose, root, color, thickness);
        } finally {
            pose.popPose();
        }
    }
}
