package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;

import com.mojang.blaze3d.vertex.VertexConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版附魔紫光总开关(2026-11-01,「关闭紫光保留描边」)。
 * <p>
 * {@link Config#DISABLE_VANILLA_GLINT} 开启时,拦截 {@code ItemRenderer} 的三个
 * foil/glint buffer 静态中枢 —— 物品(含 GUI/手持/掉落物/展示框)、盾牌/三叉戟
 * BEWLR(BlockEntityWithoutLevelRenderer 与 Material.builtin 系)、投掷三叉戟实体
 * (ThrownTridentRenderer)、鞘翅(ElytraLayer)的全部紫光路径都经由这三个方法。
 * 拦截后返回与 {@code foil=false} 完全等价的普通 buffer:本体照常渲染,仅无紫光。
 * <p>
 * 彩色描边由本模组独立绘制,不受影响。盔甲的紫光<b>不经</b>这三个中枢
 * (HumanoidArmorLayer.renderGlint 直取 armorEntityGlint),由
 * {@link HumanoidArmorLayerMixin} 单独拦截。
 * <p>
 * 语义边界:本开关只影响<b>视觉</b>;附魔数据、工具提示、铁砧合成等逻辑均不动。
 * 关闭紫光后若同时关闭描边总开关,附魔将没有任何视觉提示(配置注释已说明)。
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererFoilMixin {

    /**
     * 拦截 {@code getFoilBuffer}(glint 半透明叠加,物品模型主路径)。
     * <p>
     * {@code foil=false} 时 vanilla 本就返回普通 buffer,注入无副作用。
     *
     * @param buffers    本体顶点缓冲源
     * @param renderType 本体普通 RenderType(vanilla 在 foil=false 时原样取用)
     * @param cull       vanilla 直通参数(本注入不读)
     * @param foil       是否需要紫光
     * @param cir        注入回调(命中开关时取消原方法,返回普通 buffer)
     */
    @Inject(method = "getFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            at = @At("HEAD"), cancellable = true)
    private static void enchantedoutlines$noGlint(MultiBufferSource buffers, RenderType renderType, boolean cull,
                                                  boolean foil, CallbackInfoReturnable<VertexConsumer> cir) {
        if (foil && Config.DISABLE_VANILLA_GLINT.get()) {
            cir.setReturnValue(buffers.getBuffer(renderType));
        }
    }

    /**
     * 拦截 {@code getFoilBufferDirect}(glint 直绘,BEWLR/实体路径)。
     *
     * @param buffers    本体顶点缓冲源
     * @param renderType 本体普通 RenderType(vanilla 在 foil=false 时原样取用)
     * @param cull       vanilla 直通参数(本注入不读)
     * @param foil       是否需要紫光
     * @param cir        注入回调(命中开关时取消原方法,返回普通 buffer)
     */
    @Inject(method = "getFoilBufferDirect(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            at = @At("HEAD"), cancellable = true)
    private static void enchantedoutlines$noGlintDirect(MultiBufferSource buffers, RenderType renderType,
                                                        boolean cull, boolean foil,
                                                        CallbackInfoReturnable<VertexConsumer> cir) {
        if (foil && Config.DISABLE_VANILLA_GLINT.get()) {
            cir.setReturnValue(buffers.getBuffer(renderType));
        }
    }

    /**
     * 拦截 {@code getArmorFoilBuffer}(盔甲/鞘翅双 buffer 叠绘路径)。
     *
     * @param buffers    本体顶点缓冲源
     * @param renderType 本体普通 RenderType(vanilla 在 foil=false 时原样取用)
     * @param foil       是否需要紫光
     * @param cir        注入回调(命中开关时取消原方法,返回普通 buffer)
     */
    @Inject(method = "getArmorFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;Z)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            at = @At("HEAD"), cancellable = true)
    private static void enchantedoutlines$noGlintArmor(MultiBufferSource buffers, RenderType renderType,
                                                       boolean foil, CallbackInfoReturnable<VertexConsumer> cir) {
        if (foil && Config.DISABLE_VANILLA_GLINT.get()) {
            cir.setReturnValue(buffers.getBuffer(renderType));
        }
    }
}
