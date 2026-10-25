package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.outline.OutlineRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 序列纹理当前帧号追踪(帧感知描边形状配套,2026-10-25)。
 * <p>
 * 目标为 {@code SpriteContents$AnimatedTexture.uploadFrame(int x, int y, int frameIndex)}
 * —— 原版序列纹理在帧切换时把新帧上传进 atlas 的唯一出口,第三个参数即<b>当前正在
 * 显示的帧格索引</b>(sheet 上的第几格)。调用点唯一语义清晰:
 * <ul>
 *   <li>{@code Ticker.tickAndUpload}:帧切换时调用(动画推进);</li>
 *   <li>{@code uploadFirstFrame}:atlas 加载时调用(首帧,参数即帧 0)。</li>
 * </ul>
 * 本 mixin 在 HEAD 捕获帧格索引,记入 {@link OutlineRenderer} 的
 * spriteFrameTracker(键 = 所属 SpriteContents),供 {@code shapeTextureFor} 按
 * 当前帧烘焙描边形状 → 描边随本体序列纹理动画。
 * <p>
 * <b>为什么不用 @Shadow 合成外类字段(2026-10-25 事故)</b>:首次实现以
 * {@code @Shadow private SpriteContents this$0} 挂 {@code SpriteContents$Ticker},
 * 编译期 Mixin AP 校验通过,但运行期目标类经 remap/distclean 后合成字段名不可靠
 * → {@code InvalidMixinException: @Shadow field this$0 was not located} 启动即崩。
 * 本版零 Shadow:外类引用由 {@link OutlineRenderer} 在运行期<b>按字段类型</b>定位
 * (扫描声明字段中类型恰为 SpriteContents 的唯一成员,不依赖编译名),对重映射免疫。
 * <p>
 * 与 Sodium "仅动画可见纹理"的协同:被门禁跳过的帧切换不触发本回调(tracker 保持
 * 旧帧),SodiumCompat 标记 active 的精灵正常推进 → 记录持续更新。单帧纹理(无
 * 动画)从不切换帧 → tracker 无记录 → 按 帧 0 处理(与旧行为一致)。插值动画的
 * 帧间过渡走 uploadInterpolatedFrame,不触发本回调:描边形状跟随最近一次离散
 * 帧切换,为可接受近似。
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public abstract class SpriteContentsAnimatedTextureMixin {

    /**
     * 帧上传点:捕获 uploadFrame 的第三参(当前切换到的帧格索引)并记录。
     * <p>
     * uploadFrame 为非静态内部类方法(AnimatedTexture ∈ SpriteContents),mixin
     * 实例即 AnimatedTexture 实例;所属 SpriteContents 由 OutlineRenderer 运行期
     * 按类型解析(见类注释"为什么不用 @Shadow")。
     *
     * @param x          精灵在 atlas 中的 x(mixin 不使用)
     * @param y          精灵在 atlas 中的 y(mixin 不使用)
     * @param frameIndex 当前切换到的帧格索引(sheet 上的第几格)
     * @param ci         回调信息
     */
    @Inject(method = "uploadFrame", at = @At("HEAD"))
    private void enchantedoutlines$recordFrame(int x, int y, int frameIndex, CallbackInfo ci) {
        OutlineRenderer.recordSpriteFrame(this, frameIndex);
    }
}
