package com.enchantedoutlines.mod.mixin.tacz;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.OutlineRenderer;
import com.enchantedoutlines.mod.outline.TaczGunOutline;

import com.tacz.guns.client.model.BedrockGunModel;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TACZ 枪械模型描边注入(2026-10-18 TaCZ 兼容重做)。
 * <p>
 * <b>为什么打在 TaCZ 内部</b>(旧算法"无用"的根因,均经反编译实证):
 * <ul>
 *   <li><b>第一人称被旁路</b>:TaCZ 打包的 simplebedrockmodel IFP 系统在
 *       {@code FirstPersonRenderHandler.onRenderHand} 里直接取消原版
 *       {@code RenderHandEvent} 并自行调用 {@code renderFirstPerson} —— 原版
 *       {@code ItemInHandRenderer.renderItem}(旧描边注入点)在第一人称<b>根本
 *       不会执行</b>;且 TaCZ 用 GameRenderer mixin 改了 bob/FOV(手持 FOV 与
 *       世界 FOV 分离),在渲染 pass 外猜 pose 必然错位。</li>
 *   <li><b>第三人称/世界几何被代码级修改</b>:LOD 模型按距离切换、附件动态挂载、
 *       改枪(Refit)实时变换 —— 资源文件与实际渲染不同步(旧 v2 捕获用 identity
 *       pose 调 {@code renderByItem},LOD 距离判定失真 → 捕获模型与本体不一致)。</li>
 * </ul>
 * <b>注入点</b>:{@link BedrockGunModel#render} 六参重载的 <b>HEAD</b> —— 第一人称
 * ({@code renderFirstPersonInner})与第三人称/GROUND/FIXED({@code renderByItem})
 * 两条链<b>唯一汇聚点</b>,参数自带物品/上下文/贴图,且此刻 PoseStack 即本体
 * 即将使用的<b>最终变换</b>(视角摆动、开镜进度、后座动画、LOD 选模全部已就位;
 * 方法体内激光/瞄具挂载块的 push/pop 均平衡,HEAD 时的矩阵 = super.render
 * 收到时的矩阵)。
 * <p>
 * <b>为什么必须 HEAD 而非 TAIL(实机反馈修复,2026-10-18)</b>:项目所有描边路径
 * 的统一绘制顺序 = <b>描边先画(壳不写深度)→ 本体后画盖住壳中心 → 只露外扩环</b>
 * (ItemRendererMixin javadoc 同款语义)。TAIL 注入会把顺序倒置:无光影模式下
 * 描边壳整体糊在枪身上(无深度遮蔽,"轮廓覆盖枪身"),光影模式下与本体同深度
 * 竞争(z-fighting 重叠闪烁)。HEAD 注入恢复统一顺序 —— 本体永远绘制在壳之上,
 * 结构上消除覆盖/闪烁/穿出三类症状。
 * 走查描边({@link TaczGunOutline})在此 pose 下读模型树自算几何 —— 本体怎么
 * 渲染,描边就怎么渲染(铁律 6.5);对 Accelerated Rendering 加速渲染环境同样
 * 有效(只读模型数据,不依赖本体顶点输出)。
 * <p>
 * GUI(背包 slot 2D 平面)不经此方法(TaCZ 走 {@code renderSlotTexture} 的
 * SlotModel 独立路径),仍由既有平坦捕获处理,互不干扰。
 * <p>
 * ⚠️ 本类及其目标仅由 {@link TaczMixinPlugin} 在 TACZ 存在时应用;TACZ 缺席时
 * 本类不会被加载,任何 TACZ 类型引用都不会被解析。
 */
@Mixin(value = BedrockGunModel.class, remap = false)
public abstract class TaczGunModelMixin {

    /**
     * 枪械模型渲染 HEAD 描边回调:在本体渲染<b>之前</b>、以本体即将使用的最终
     * pose 走查模型树发射描边壳(场景过滤/开关/颜色判定见 {@link TaczGunOutline});
     * 本体随后绘制并盖住壳中心,只露外扩环(与项目全部描边路径同序)。
     * <p>
     * 铁律 6.7 合规:描边异常不上抛(不破坏本体渲染),finally 强制 flush
     * (空批 no-op,正常路径零开销)。
     *
     * @param poseStack     本体渲染用 PoseStack(HEAD 时刻 = 本体最终变换,方法体内
     *                      挂载块 push/pop 平衡,与 super.render 收到时一致)
     * @param gunItem       正在渲染的枪械物品
     * @param transformType 显示上下文(第一/第三人称/GROUND/FIXED;GUI 不会到达此处)
     * @param renderType    本体使用的 RenderType(含枪械贴图)
     * @param light         本体光照
     * @param overlay      本体 overlay
     * @param ci            mixin 回调(不取消)
     */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V",
            at = @At("HEAD"), remap = false)
    private void enchantedoutlines$taczGunOutline(PoseStack poseStack, ItemStack gunItem, ItemDisplayContext transformType,
                                                  RenderType renderType, int light, int overlay, CallbackInfo ci) {
        if (gunItem.isEmpty() || !Config.ENABLE.get()) {
            return;
        }
        // 逐物品覆盖标记:深层混色决策(armorOutlineRenderType 的 mix/纯色判定)经此传递
        OutlineRenderer.markOutlinedItem(gunItem);
        try {
            TaczGunOutline.onGunModelRenderTail((BedrockGunModel) (Object) this, gunItem, transformType, renderType, poseStack);
        } catch (Throwable ignored) {
            // 铁律 6.7:描边异常不上抛;finally flush 保证批次闭合
        } finally {
            OutlineRenderer.INSTANCE.flushOutlineBatch();
            OutlineRenderer.clearOutlinedItem();
        }
    }
}
