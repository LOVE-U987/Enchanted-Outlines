package com.enchantedoutlines.mod.outline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Blackgear Platform 渲染期模型替换兼容(2026-11-08,issue #4)。
 * <p>
 * <b>根因</b>:Vanilla Backport 等基于 Blackgear Platform 的模组,通过
 * {@code ItemRendererRegistry} 为特定物品(如 1.21.11 移植的矛)注册"渲染期模型替换":
 * <ul>
 *   <li>{@code ItemRenderer.render} 8 参的 {@code @ModifyVariable} 对注册物品调用
 *       {@code Renderer.renderFirstPerson(stack, context, shaper)},手持 context 提交
 *       专用模型(如 {@code <material>_spear_in_hand},32x32 贴图、专属 display 变换);</li>
 *   <li>{@code ItemRenderer.getModel} 的 {@code @ModifyVariable} 只咨询
 *       {@code renderThirdPerson}(矛恒 pass),<b>不包含</b>上述替换。</li>
 * </ul>
 * 于是描边侧经 {@code getModel} 解析只拿到 GUI 平面模型(16x16),而本体手持渲染的是
 * in_hand 模型(32x32)—— 手持描边 = GUI 物品剪影、本体 = 手持剪影,形状/朝向/比例
 * 全部错位。用户因 in_hand 贴图分辨率更高而归因为"不同分辨率物品描边缩放错误"。
 * GUI/掉落物/展示框不受影响:平台对这三个 context 恒 pass,描边与本体同为 GUI 平面模型。
 * <p>
 * <b>修复</b>:在描边侧镜像本体 {@code render} 期的模型解析 —— 反射查询注册表并调用
 * 同一 {@code renderFirstPerson} 接口,提交(submit)结果作为描边模型,pass 则保持
 * {@code getModel} 原结果(与本体逐调用一致,幂等)。另探测平台
 * {@code DynamicItemRenderer}(本体 {@code render} 被直接 cancel 后全自绘的物品),
 * 命中时由调用方跳过平面描边(宁可无描边,不画错位描边,对齐铁律 6.9 原则)。
 * <p>
 * <b>依赖形态</b>:类名/方法名只以字符串与反射触达,无编译期硬依赖;平台缺席时
 * 一次探测后全部调用零成本返回。反射句柄一次解析静态缓存(平台类在启动期加载完毕,
 * 会话内不变化;与资源重载无关,无需登记 {@code invalidateCaches})。
 * <b>不缓存</b>解析出的 {@link BakedModel}(资源重载后实例失效,每帧现查)。
 * <p>
 * 已知边界:平台把矛等物品注册进 {@code ItemRendererRegistry} 的新架构(v2,1.21.1);
 * 旧架构(1.20.1 分支的 {@code GameRendering.HAND_HELD_MODELS})不在本类处理范围。
 */
public final class PlatformCompat {

    /** 平台模型替换注册表类(1.21.1 v2 架构,仅以字符串形式出现)。 */
    private static final String REGISTRY_CLASS = "com.blackgear.platform.client.v2.render.ItemRendererRegistry";
    /** 平台全自定义渲染注册表类(仅以字符串形式出现)。 */
    private static final String DYNAMIC_CLASS = "com.blackgear.platform.client.v2.render.DynamicItemRenderer";
    /** 平台事件结果持有器类(isCancelled/getValue 判定"提交替换")。 */
    private static final String RESULT_HOLDER_CLASS = "com.blackgear.platform.core.util.event.ResultHolder";

    /** 反射解析只做一次:true = 已尝试过(失败同样置位,避免逐帧重试开销)。 */
    private static boolean resolved;
    /** 模型替换注册表单例({@code INSTANCE.get()});null = 平台缺席/不兼容。 */
    private static Object registryInstance;
    /** 注册表查询方法 {@code get(ItemLike) → Renderer}。 */
    private static Method registryGet;
    /** 渲染器能力判定 {@code Renderer.shouldUse()}。 */
    private static Method rendererShouldUse;
    /** 渲染期模型解析 {@code Renderer.renderFirstPerson(ItemStack, ItemDisplayContext, ItemModelShaper)}。 */
    private static Method rendererRenderFirstPerson;
    /** 结果判定 {@code ResultHolder.isCancelled()}。 */
    private static Method holderIsCancelled;
    /** 结果取值 {@code ResultHolder.getValue()}。 */
    private static Method holderGetValue;
    /** 全自定义渲染注册表单例({@code INSTANCE.get()});null = 平台缺席/不兼容。 */
    private static Object dynamicInstance;
    /** 全自定义渲染注册表查询方法 {@code get(ItemLike) → DynamicRenderer}。 */
    private static Method dynamicGet;

    private PlatformCompat() {
    }

    /**
     * 镜像本体渲染期的模型替换解析:返回"本体在该 context 下实际使用的模型"。
     * <p>
     * 与 {@code ItemRenderer.render} 上的平台 {@code @ModifyVariable} 逐调用一致
     * (同一注册表、同一 {@code renderFirstPerson}、同一 {@code ItemModelShaper}):
     * 平台未注册该物品 / {@code shouldUse()} 为 false / 返回 pass 时原样返回
     * {@code fallback};返回 submit 时用其 {@code getValue()} 替换。GUI 等 context
     * 平台通常 pass → 恒 no-op。任何反射失败静默回退 {@code fallback}。
     *
     * @param stack    当前渲染的物品
     * @param context  当前显示上下文(与本体 render 传入的一致)
     * @param fallback 描边原本解析出的模型(通常来自 {@code ItemRenderer.getModel})
     * @return 与本体渲染一致的模型;平台缺席/未注册/失败时返回 fallback
     */
    public static BakedModel resolveRenderModel(ItemStack stack, ItemDisplayContext context, BakedModel fallback) {
        if (stack == null || stack.isEmpty()) {
            return fallback;
        }
        ensureResolved();
        if (registryInstance == null || registryGet == null || rendererRenderFirstPerson == null) {
            return fallback;
        }
        try {
            Object renderer = registryGet.invoke(registryInstance, stack.getItem());
            if (renderer == null || !isShouldUse(renderer)) {
                return fallback;
            }
            ItemModelShaper shaper = Minecraft.getInstance().getItemRenderer().getItemModelShaper();
            Object holder = rendererRenderFirstPerson.invoke(renderer, stack, context, shaper);
            if (holder == null || !Boolean.TRUE.equals(holderIsCancelled.invoke(holder))) {
                return fallback;
            }
            Object value = holderGetValue.invoke(holder);
            return value instanceof BakedModel model ? model : fallback;
        } catch (Throwable ignored) {
            // 平台版本差异/调用异常:静默回退,不影响本体渲染
            return fallback;
        }
    }

    /**
     * 物品是否由平台 {@code DynamicItemRenderer} 接管(本体 render 被 cancel 后全自绘)。
     * <p>
     * 此类物品的占位模型没有实际几何意义,平面描边必然错位;调用方据此跳过描边
     * (宁可无描边,不画错位描边)。
     *
     * @param stack 当前渲染的物品
     * @return true = 平台全自定义渲染物品;平台缺席/未注册返回 false
     */
    public static boolean hasDynamicRenderer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ensureResolved();
        if (dynamicInstance == null || dynamicGet == null) {
            return false;
        }
        try {
            Object renderer = dynamicGet.invoke(dynamicInstance, stack.getItem());
            return renderer != null && isShouldUse(renderer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 渲染器是否启用({@code shouldUse()} 接口默认实现返回 true)。
     *
     * @param renderer 平台注册的渲染器实例
     * @return true = 启用;句柄缺失/调用失败返回 false
     */
    private static boolean isShouldUse(Object renderer) {
        if (rendererShouldUse == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(rendererShouldUse.invoke(renderer));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 惰性解析平台反射句柄(一次性;平台缺席/版本不兼容时各句柄保持 null,
     * 后续调用走零成本短路)。模型替换与全自定义渲染两套句柄独立解析,
     * 一侧失败不影响另一侧。
     */
    private static synchronized void ensureResolved() {
        if (resolved) {
            return;
        }
        resolved = true;
        resolveModelRegistry();
        resolveDynamicRegistry();
    }

    /**
     * 解析 {@code ItemRendererRegistry}(模型替换)相关句柄。
     * <p>
     * 全部句柄在同一 try 内解析:任一缺失(平台版本不符)即整组作废,
     * 避免半初始化状态在调用侧产生意外行为。
     */
    private static void resolveModelRegistry() {
        try {
            Class<?> registryClass = Class.forName(REGISTRY_CLASS);
            Object instance = singletonOf(registryClass);
            Class<?> rendererClass = Class.forName(REGISTRY_CLASS + "$Renderer");
            Class<?> holderClass = Class.forName(RESULT_HOLDER_CLASS);
            Method get = registryClass.getMethod("get", ItemLike.class);
            Method shouldUse = rendererClass.getMethod("shouldUse");
            Method renderFirstPerson = rendererClass.getMethod("renderFirstPerson",
                    ItemStack.class, ItemDisplayContext.class, ItemModelShaper.class);
            Method isCancelled = holderClass.getMethod("isCancelled");
            Method getValue = holderClass.getMethod("getValue");
            // 全部解析成功才落盘,保证一组句柄的原子性
            registryInstance = instance;
            registryGet = get;
            rendererShouldUse = shouldUse;
            rendererRenderFirstPerson = renderFirstPerson;
            holderIsCancelled = isCancelled;
            holderGetValue = getValue;
        } catch (Throwable ignored) {
            // 平台缺席/版本不兼容:保持 null(调用侧短路,行为与修复前一致)
            registryInstance = null;
            registryGet = null;
            rendererShouldUse = null;
            rendererRenderFirstPerson = null;
            holderIsCancelled = null;
            holderGetValue = null;
        }
    }

    /**
     * 解析 {@code DynamicItemRenderer}(全自定义渲染)相关句柄。
     * <p>
     * 与模型替换句柄独立:其一不可用不影响另一侧。
     */
    private static void resolveDynamicRegistry() {
        try {
            Class<?> dynamicClass = Class.forName(DYNAMIC_CLASS);
            Object instance = singletonOf(dynamicClass);
            Method get = dynamicClass.getMethod("get", ItemLike.class);
            dynamicInstance = instance;
            dynamicGet = get;
        } catch (Throwable ignored) {
            dynamicInstance = null;
            dynamicGet = null;
        }
    }

    /**
     * 读取平台注册表类的 {@code public static final Supplier INSTANCE} 字段并取值。
     *
     * @param registryClass 平台注册表类(ItemRendererRegistry / DynamicItemRenderer)
     * @return 注册表单例
     * @throws Exception 字段缺失/类型不符/取值失败(由调用方统一兜底)
     */
    private static Object singletonOf(Class<?> registryClass) throws Exception {
        Field instanceField = registryClass.getField("INSTANCE");
        Object supplier = instanceField.get(null);
        Method supplierGet = com.google.common.base.Supplier.class.getMethod("get");
        return supplierGet.invoke(supplier);
    }
}
