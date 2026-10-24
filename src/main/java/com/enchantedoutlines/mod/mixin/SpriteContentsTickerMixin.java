package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.renderer.texture.SpriteContents;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 序列纹理当前帧号追踪(帧感知描边形状配套,2026-10-25)。
 * <p>
 * 目标为 {@code SpriteContents$Ticker}(非静态内部类,编译器合成外类引用
 * {@code this$0} 指向所属 {@link SpriteContents})。原版
 * {@code tickAndUpload} 在帧切换时调用
 * {@code AnimatedTexture.uploadFrame(x, y, frameIndex)} 把新帧上传进 atlas,
 * 第三个参数即<b>当前正在显示的帧格索引</b>(sheet 上的第几格)。
 * 本 mixin 在该调用点捕获帧格索引,记入 {@link OutlineRenderer} 的
 * spriteFrameTracker(按 SpriteContents 弱引用),供 {@code shapeTextureFor}
 * 按当前帧烘焙描边形状 → 描边随本体序列纹理动画。
 * <p>
 * 与 Sodium "仅动画可见纹理"的协同:被门禁跳过的 tick 不触发本回调
 * (tracker 保持旧帧),SodiumCompat 标记 active 的精灵正常 tick → 记录持续更新。
 * 单帧纹理(无动画)不产生 uploadFrame 调用 → tracker 无记录 → 按 帧 0 处理
 * (与旧行为一致)。
 * <p>
 * 插值动画(interpolate=true)的帧间过渡走 uploadInterpolatedFrame,不触发本
 * 回调:描边形状跟随最近一次离散帧切换,为可接受近似。
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$Ticker")
public abstract class SpriteContentsTickerMixin {

    /**
     * 合成外类引用:指向所属 SpriteContents(非静态内部类的编译器生成字段)。
     * 用于把帧号记录到按 contents 索引的 tracker。
     */
    @Shadow
    @Final
    private SpriteContents this$0;

    /**
     * 帧切换上传点:捕获 uploadFrame 的第三参(当前显示的帧格索引)并记录。
     * <p>
     * handler 参数序 = 目标方法参数(x, y)+ CallbackInfo + 被调用方法参数
     * (uploadFrame 的 x, y, frameIndex)—— Mixin 标准的 INVOKE 参数捕获形态。
     *
     * @param x          目标方法参数:精灵在 atlas 中的 x(mixin 不使用)
     * @param y          目标方法参数:精灵在 atlas 中的 y(mixin 不使用)
     * @param ci         回调信息
     * @param uploadX    uploadFrame 的 x(与目标方法 x 相同,mixin 不使用)
     * @param uploadY    uploadFrame 的 y(与目标方法 y 相同,mixin 不使用)
     * @param frameIndex 当前切换到的帧格索引(sheet 上的第几格)
     */
    @Inject(method = "tickAndUpload",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/SpriteContents$AnimatedTexture;uploadFrame(III)V"))
    private void enchantedoutlines$recordFrame(int x, int y, CallbackInfo ci,
                                               int uploadX, int uploadY, int frameIndex) {
        OutlineRenderer.recordSpriteFrame(this$0, frameIndex);
    }
}
