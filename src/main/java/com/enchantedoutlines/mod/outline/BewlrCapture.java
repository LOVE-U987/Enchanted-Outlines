package com.enchantedoutlines.mod.outline;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.enchantedoutlines.mod.EnchantedOutlines;
import com.enchantedoutlines.mod.config.Config;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * BEWLR <b>渲染捕获</b>描边子模块(通用模型获取算法的落地,任意模组零适配兜底)。
 * <p>
 * 2026-09-25 行为保持拆分:从 {@link OutlineRenderer} 拆出的 BEWLR 家族子模块之一。
 * 对<b>任何</b>摸不到模型对象的 BEWLR(TACZ 自绘 Bedrock / GeckoLib GeoItem /
 * AzureLib / 未来任意模组),不反射其内部字段,而是调用 MC 的唯一 BEWLR 出口 ——
 * {@code IClientItemExtensions.getCustomRenderer()} 的 {@code renderByItem} ——
 * 并把 MultiBufferSource 换成 {@link CaptureSource} 拦截写出的顶点流,再对捕获
 * 几何做<b>顶点法线平均外扩</b>描边壳。捕获/守卫/外扩语义与拆分前完全一致
 * (AGENTS.md 铁律 6.8:双守卫 + 拒绝留痕;批次闭合铁律 6.7)。
 * <p>
 * <b>这是"通用算法 v2"(规范化捕获)的主战场</b>:后续升级(空 PoseStack 捕获 →
 * 本地空间守卫 → 统一像素语义外扩 → 几何缓存)全部在本类内进行,不再与核心
 * 渲染器耦合。
 * <p>
 * 对 {@link OutlineRenderer} 的依赖(包私有):描边缓冲 {@code outlineBuffers}、
 * uniform 设置 {@code setOutlineAlphaBoost}、{@code geoOutlineRenderType}、共享常量、
 * {@code Position} 顶点键与反射工具 —— 经构造时注入的 renderer 实例访问。
 */
final class BewlrCapture {

    /** 宿主渲染器(描边缓冲/RenderType/uniform 的统一入口,循环引用:仅存引用不触构造)。 */
    private final OutlineRenderer renderer;

    /**
     * @param renderer 宿主渲染器(OutlineRenderer 单例;构造只存引用,无回调)
     */
    BewlrCapture(OutlineRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * BEWLR 渲染捕获的 AABB 最大允许跨度(模型单位)。
     * <p>
     * 正常情况下手持/GUI/掉落/展示框物品经 display 变换后的包围盒都在几个模型
     * 单位以内;自绘 BEWLR(如 TACZ 第一人称)在 display 后追加相机/动画预变换时,
     * 捕获顶点会被抬到相机空间 → 包围盒跨度动辄数十/数百单位。超过本阈值即判定
     * 捕获姿态错乱,返回 false 跳过描边(避免"天上巨大模型、本体无描边"事故)。
     */
    static final float CAPTURE_MAX_SPAN = 64.0f;

    /**
     * 手持 context(第一/第三人称)下,捕获 AABB 中心允许的最大离原点距离(模型单位)。
     * <p>
     * <b>中心距离守卫(2026-09-25,"头顶巨大模型"修复)</b>:跨度守卫只查"大小"不查
     * "位置"。某些自绘 BEWLR 在第一/第三人称会把顶点整体抬到相机/世界空间 —— 形状
     * 不大(跨度 < 64)但中心离视角原点很远,描边壳画在远处就是"巨大模型出现在
     * 玩家头顶/天上"。正常手持物品经 display 变换后离视角原点仅几单位(第一人称约
     * 1-2、第三人称约 4-6,攻击动画位移也在 ~10 以内),中心距离超过本阈值即判定
     * 捕获坐标系错误,跳过描边(宁可无描边,不画错位巨壳)。
     * <p>
     * ⚠️ 仅对手持 context 生效:GUI 是像素空间(中心离原点数百像素正常)、
     * GROUND/FIXED(掉落物/展示框)可合法地离相机数十单位,不能一刀切。
     */
    static final float CAPTURE_MAX_CENTER_DIST = 16.0f;

    /** 捕获守卫拒绝诊断日志的节流表(原因#物品ID#context → 上次打印时刻 ms)。 */
    private static final Map<String, Long> GUARD_LOG_THROTTLE = new ConcurrentHashMap<>();

    /**
     * 捕获守卫拒绝诊断日志(按 原因#物品#context 节流,10 秒窗口)。
     * <p>
     * 守卫拒绝 = 某模组渲染器输出了<b>非模型空间</b>顶点(相机/动画预变换)或异常
     * 巨大几何,描边被主动跳过(宁可无描边,不画错位巨壳)。这是定位"哪个模组/哪个
     * context 产生垃圾捕获"的唯一线索 —— info 级别便于用户报障时直接附上
     * {@code latest.log};10 秒节流保证守卫高频触发时也不刷屏(AGENTS.md #6)。
     * 拒绝本身是低频事件,该日志作为<b>常驻诊断</b>保留,不是临时调试代码。
     *
     * @param stack     被拒绝描边的物品(日志中打印注册 ID)
     * @param context   显示上下文
     * @param reason    拒绝原因:span = 跨度超限 / center-dist = 中心离原点过远
     * @param verts     捕获顶点 float 数(xyz 交错,日志里换算为顶点数 ÷3)
     * @param sx sy sz  AABB 三个轴的跨度
     * @param centerLen AABB 中心到原点距离(span 拒绝时无意义,传 -1)
     */
    private static void logCaptureReject(ItemStack stack, ItemDisplayContext context, String reason,
                                         int verts, float sx, float sy, float sz, float centerLen) {
        String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        long now = System.currentTimeMillis();
        Long last = GUARD_LOG_THROTTLE.put(reason + "#" + itemId + "#" + context, now);
        if (last != null && now - last < 10_000L) {
            return; // 10 秒窗口内同一(物品,context,原因)只打印一次
        }
        EnchantedOutlines.LOGGER.info(String.format(Locale.ROOT,
                "[Enchanted Outlines] BEWLR 描边捕获守卫拒绝(已跳过该次描边避免错位巨壳):"
                        + " item=%s context=%s reason=%s verts=%d span=(%.2f, %.2f, %.2f) centerDist=%.2f",
                itemId, context, reason, verts / 3, sx, sy, sz, centerLen));
    }

    /**
     * 节流通用诊断日志(10 秒窗口;<b>常驻诊断</b>类:仅低频事件使用,不刷屏,
     * 属 AGENTS.md #6"临时诊断必须清理"的合法例外)。
     *
     * @param key    节流键(原因#物品#context 等,复用守卫节流表)
     * @param detail 完整日志内容(自带 "[Enchanted Outlines]" 前缀)
     */
    private static void logCaptureDiag(String key, String detail) {
        long now = System.currentTimeMillis();
        Long last = GUARD_LOG_THROTTLE.put(key, now);
        if (last != null && now - last < 10_000L) {
            return; // 10 秒窗口内同键只打印一次
        }
        EnchantedOutlines.LOGGER.info(detail);
    }

    /**
     * 捕获几何统计(节流,swap-stats):全局缓冲防护统一后所有捕获均在替换窗口内
     * 进行(2026-10-02),本日志输出捕获几何的顶点数/AABB/中心距离用于定位 ——
     * 本地空间正常枪械约 ±2 单位、中心 <3;统计正常而描边仍错位 → 问题在重放/
     * 发射层;统计异常 → 捕获本身拿到垃圾几何(守卫会另行 logCaptureReject 留痕)。
     *
     * @param stack   被捕获物品
     * @param context 显示上下文
     * @param verts   捕获顶点流(xyz 交错)
     */
    private static void logSwapStats(ItemStack stack, ItemDisplayContext context, float[] verts) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < verts.length; i += 3) {
            minX = Math.min(minX, verts[i]);
            maxX = Math.max(maxX, verts[i]);
            minY = Math.min(minY, verts[i + 1]);
            maxY = Math.max(maxY, verts[i + 1]);
            minZ = Math.min(minZ, verts[i + 2]);
            maxZ = Math.max(maxZ, verts[i + 2]);
        }
        float centerLen = 0.5f * (float) Math.sqrt(
                (minX + maxX) * (minX + maxX)
                        + (minY + maxY) * (minY + maxY)
                        + (minZ + maxZ) * (minZ + maxZ));
        String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        logCaptureDiag("swap-stats#" + itemId + "#" + context,
                String.format(Locale.ROOT,
                        "[Enchanted Outlines] 旁路捕获几何(swap-stats 节流): item=%s context=%s verts=%d"
                                + " min=(%.2f,%.2f,%.2f) max=(%.2f,%.2f,%.2f) centerDist=%.2f",
                        itemId, context, verts.length / 3, minX, minY, minZ, maxX, maxY, maxZ, centerLen));
    }

    /**
     * v2 规范化捕获入口:<b>空 PoseStack(identity)</b> 捕获本地模型空间几何。
     * <p>
     * ⚠️ 全局缓冲防护已统一并入 {@link #capturePlain}(2026-10-02):直写全局
     * bufferSource 的渲染器(TACZ 型)在捕获窗口内写进捕获器而非真实管线 ——
     * 既根治"identity 顶点泄漏进渲染管线"的错位图形,又让这类渲染器直接被 v2
     * 捕获(每帧 1 次渲染调用,不再 v2/v1 各跑一遍)。
     * <p>
     * identity 对"读 pose"型渲染器(TACZ 第一人称,派发层已跳过)产出假定空间垃圾 →
     * 由 v2 守卫(铁律 6.9)统一拦下,不重试。
     *
     * @param stack   物品栈
     * @param context 当前显示上下文(渲染器按它选姿态/分支)
     * @return 捕获结果(positions = 本地模型空间坐标);拿不到返回 null
     */
    private static CapturedGeometry captureLocal(ItemStack stack, ItemDisplayContext context) {
        return capturePlain(stack, null, context);
    }

    /**
     * 普通捕获:调 MC 的唯一 BEWLR 出口 {@code renderByItem},把 MultiBufferSource
     * 换成捕获器拦截写出的顶点流(位置 + UV + 采样纹理)。
     * <p>
     * ⚠️ <b>全局缓冲统一防护(2026-10-02 审计修复,"视野错位图形"泄漏根治)</b>:
     * 直写全局 bufferSource 的渲染器(TACZ {@code BedrockModel.render} 型)若在
     * <b>未替换全局</b>的捕获窗口内被调用,会把顶点写进真实渲染管线 —— identity
     * 捕获(v2)泄漏的是本地模型坐标,flush 时被解释为相机/GUI 像素空间 →
     * 视野中央/原点错位图形;real-pose 捕获(v1)泄漏的是最终坐标(与本体重合,
     * 良性但每帧多跑一次完整渲染)。因此捕获窗口内<b>统一</b>把
     * {@code Minecraft.renderBuffers()} 的 bufferSource 字段替换为
     * {@link CaptureBufferSource}(渲染器无论写传入 buffer 还是取全局都进捕获器,
     * 每帧 1 次渲染调用);替换失败(字段不可访问)或门禁
     * {@code Config.BEWLR_GLOBAL_BUFFER_SWAP} 关闭时降级纯
     * {@link CaptureSource}(直写全局渲染器拿不到几何 → 由回退链处理)。
     * <p>
     * pose 语义按调用方分流:v1 传真实 pose(捕获 = 最终绘制坐标,原样发射零重放);
     * v2 传 null(identity,捕获本地模型空间,见 {@link #captureLocal})。identity 对
     * "读 pose"型渲染器产出假定空间垃圾 → 由 v2 守卫(铁律 6.9)统一拦下。
     * <p>
     * 渲染线程单线程:同步替换 / finally 还原;替换窗口内其他代码取全局 bufferSource
     * 也只会拿到无害的捕获 consumer(吞几何而非上屏)。final 实例字段经
     * setAccessible 反射写入(JVM 允许)。
     *
     * @param stack      物品栈
     * @param poseMatrix 本体 renderByItem 同款 pose 顶矩阵(v1 复制进捕获栈);
     *                   <b>null = 空 PoseStack(identity)捕获</b>(v2 规范化:得到渲染器
     *                   本地模型空间顶点,与相机/GUI 像素空间无关)
     * @param context    当前显示上下文(渲染器按它选姿态/分支)
     * @return 捕获结果;拿不到返回 null
     */
    private static CapturedGeometry capturePlain(ItemStack stack, Matrix4f poseMatrix, ItemDisplayContext context) {
        try {
            BlockEntityWithoutLevelRenderer bewlr = resolveBewlrRenderer(stack);
            if (bewlr == null) {
                return null;
            }
            // 渲染器需要 PushPose/PopPose 环境,给它一个全新 PoseStack;
            // v1:初始矩阵 = 当前 pose(捕获最终绘制坐标);v2:保持 identity(捕获本地坐标)。
            PoseStack capturePose = new PoseStack();
            if (poseMatrix != null) {
                capturePose.last().pose().set(poseMatrix);
            }
            // 全局缓冲统一防护:门禁开启时替换 RenderBuffers.bufferSource(见类 javadoc);
            // 字段不可访问/替换失败 → swapCap 保持 null → 降级纯 CaptureSource。
            java.lang.reflect.Field globalField = null;
            MultiBufferSource.BufferSource original = null;
            CaptureBufferSource swapCap = null;
            if (Config.BEWLR_GLOBAL_BUFFER_SWAP.get()) {
                globalField = OutlineRenderer.findField(RenderBuffers.class, "bufferSource");
                if (globalField != null) {
                    try {
                        globalField.setAccessible(true);
                        RenderBuffers renderBuffers = Minecraft.getInstance().renderBuffers();
                        original = (MultiBufferSource.BufferSource) globalField.get(renderBuffers);
                        if (original != null) {
                            swapCap = new CaptureBufferSource();
                            globalField.set(renderBuffers, swapCap);
                        }
                    } catch (Throwable t) {
                        // 替换失败(字段 final / 类型不符等):还原到"未替换"状态降级纯 plain
                        swapCap = null;
                        original = null;
                    }
                }
            }
            try {
                if (swapCap != null) {
                    bewlr.renderByItem(stack, context, capturePose, swapCap,
                            OutlineRenderer.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    CapturedGeometry result = swapCap.finish();
                    if (result != null) {
                        logSwapStats(stack, context, result.positions());
                    }
                    return result;
                }
                CaptureSource cap = new CaptureSource();
                bewlr.renderByItem(stack, context, capturePose, cap,
                        OutlineRenderer.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                return cap.finish();
            } finally {
                if (globalField != null && swapCap != null) {
                    try {
                        globalField.set(Minecraft.getInstance().renderBuffers(), original);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
            // 捕获失败(渲染器强依赖上下文/类型不匹配等):返回 null,由调用方回退 Geo 或跳过
            return null;
        }
    }

    /**
     * 通用 BEWLR 渲染器解析:按 MC 两条标准注册通路拿渲染器(不反射模组内部字段):
     * <ol>
     *   <li>{@code IClientItemExtensions.of(stack).getCustomRenderer()} —— 大多数 BEWLR
     *       (TACZ GunItemRendererWrapper、灾变 CMItemstackRenderer、永恒星光等);</li>
     *   <li>GeckoLib GeoItem —— 不暴露 custom renderer,但 {@code GeoRenderProvider
     *       .of(item).getGeoItemRenderer()} 返回其 GeoItemRenderer(本身 extends
     *       BlockEntityWithoutLevelRenderer);Hazen 星怒(StarfuryItem implements
     *       GeoItem)走此路。</li>
     * </ol>
     * 两条都不中返回 null(非 BEWLR/无渲染器),调用方跳过。
     * <p>
     * 供本类捕获路径与 {@link TaczOutline}(TACZ Bedrock 模型描边)共用。
     *
     * @param stack 物品
     * @return BEWLR 渲染器;非 BEWLR / 解析失败返回 null
     */
    static BlockEntityWithoutLevelRenderer resolveBewlrRenderer(ItemStack stack) {
        // 通路 1:IClientItemExtensions(TACZ/灾变/永恒星光/普通 BEWLR)
        try {
            IClientItemExtensions ext = IClientItemExtensions.of(stack);
            if (ext != null) {
                BlockEntityWithoutLevelRenderer r = ext.getCustomRenderer();
                if (r != null) {
                    return r;
                }
            }
        } catch (Throwable ignored) {
        }
        // 通路 2:GeckoLib GeoItem(GeoRenderProvider.getGeoItemRenderer)
        try {
            Item item = stack.getItem();
            Class<?> providerClass = Class.forName("software.bernie.geckolib.animatable.client.GeoRenderProvider");
            java.lang.reflect.Method of = OutlineRenderer.findMethod(providerClass, "of", Item.class);
            if (of == null) {
                return null;
            }
            of.setAccessible(true);
            Object provider = of.invoke(null, item);
            if (provider == null) {
                return null;
            }
            java.lang.reflect.Method getRenderer = null;
            for (java.lang.reflect.Method m : provider.getClass().getMethods()) {
                if (m.getName().equals("getGeoItemRenderer")) {
                    getRenderer = m;
                    break;
                }
            }
            if (getRenderer == null) {
                return null;
            }
            getRenderer.setAccessible(true);
            Object r = getRenderer.invoke(provider);
            return r instanceof BlockEntityWithoutLevelRenderer bewlr ? bewlr : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 捕获结果(2026-09-25 v2.1;2026-10-11 增加层来源标记):顶点位置流 + UV 流
     * + 渲器采样的纹理 + 每 quad 来源层。
     * <ul>
     *   <li>positions:xyz 交错,长度 = 顶点数×3(QUADS 流,12 float/quad);</li>
     *   <li>uvs:uv 交错,长度 = 已记录 UV 顶点数×2 —— 渲染器不写 UV 时比顶点少,
     *       发射时按下标防御(缺 UV 顶点用 0);</li>
     *   <li>texture:从渲染器用的 RenderType 反射提取的采样纹理(贴图形状描边用),
     *       <b>跳过附魔 glint 层</b>(半透明噪声图,禁作 alpha 遮罩源 —— 2026-10-11
     *       "紫色光斑不贴轮廓"根因),按 getBuffer 顺序取首个非 glint 纹理;
     *       提取失败/无纹理为 null → 纯几何描边;</li>
     *   <li>quadSources:每 quad 的来源层编号(按 getBuffer 顺序,基础层=0)——
     *       同一几何被渲染器写入多层的叠加判定(glint/glowmask 跨层重复 = 叠绘,
     *       同层重复 = 零厚度贴片正反面)。</li>
     * </ul>
     */
    record CapturedGeometry(float[] positions, float[] uvs, ResourceLocation texture,
                            int[] quadSources) {
        int vertexCount() {
            return positions.length / 3;
        }
    }

    /**
     * 从 RenderType 提取其采样的纹理 location(v2.1"贴图形状描边"用)。
     * <p>
     * 反射链 {@code state → textureState → texture}(兼容 Optional 包装),失败返回
     * null。捕获到渲染器绑定的 RenderType 即知道本体采样哪张纹理(如 TACZ GUI 的
     * slot 贴图),描边可用同张纹理的 alpha 轮廓做形状,而不是纯几何矩形。
     *
     * @param renderType 渲染器请求的 RenderType
     * @return 采样纹理;反射链失败/无纹理返回 null
     */
    private static ResourceLocation extractTextureOf(RenderType renderType) {
        try {
            java.lang.reflect.Field stateF = OutlineRenderer.findField(renderType.getClass(), "state");
            if (stateF == null) {
                return null;
            }
            stateF.setAccessible(true);
            Object state = stateF.get(renderType);
            if (state == null) {
                return null;
            }
            java.lang.reflect.Field texF = OutlineRenderer.findField(state.getClass(), "textureState");
            if (texF == null) {
                return null;
            }
            texF.setAccessible(true);
            Object shard = texF.get(state);
            if (shard == null) {
                return null;
            }
            java.lang.reflect.Field locF = OutlineRenderer.findField(shard.getClass(), "texture");
            if (locF == null) {
                return null;
            }
            locF.setAccessible(true);
            Object tex = locF.get(shard);
            if (tex instanceof java.util.Optional<?> opt) {
                tex = opt.orElse(null);
            }
            // 字段读不到时的兜底:稳定 mojmap 方法 EmptyTextureStateShard.cutoutTexture()
            // (TextureStateShard 覆写返回采样纹理;Empty 版返回 Optional.empty)
            if (!(tex instanceof ResourceLocation)) {
                java.lang.reflect.Method m = OutlineRenderer.findMethod(shard.getClass(), "cutoutTexture");
                if (m != null) {
                    m.setAccessible(true);
                    Object viaMethod = m.invoke(shard);
                    if (viaMethod instanceof java.util.Optional<?> opt2) {
                        viaMethod = opt2.orElse(null);
                    }
                    tex = viaMethod;
                }
            }
            return tex instanceof ResourceLocation rl ? rl : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 捕获渲染的 MultiBufferSource:按 RenderType 缓存<b>独立</b> {@link CaptureVertexConsumer}
     * (与真实 {@code BufferSource} 语义一致),收集 addVertex 位置 + setUv,
     * 并记录渲染器采样的纹理;endBatch 为空操作(绝不上屏)。
     * <p>
     * ⚠️ 必须按 RenderType 区分 consumer(2026-09-06 星怒 GUI "Duplicate delegates"
     * 崩溃根因):模组渲染器内部可能用 {@code VertexMultiConsumer} 组合多个 buffer
     * (主渲染 + 附魔层),若 {@code getBuffer} 对所有 RenderType 返回<b>同一个实例</b>,
     * 组合器检测到重复 delegate 抛 {@code IllegalArgumentException}。每个 RenderType
     * 独立实例(即使它们指向同一块捕获数组)即可规避;渲染器把同一条顶点写入主+附魔
     * 两个 buffer 会重复计数,但对 AABB/壳描边无影响(重复顶点放大后重叠,视觉不变)。
     * <p>
     * ⚠️ 只支持 {@link VertexFormat.Mode#QUADS}(每 4 顶点一个 quad)流:
     * 汇总按 12 个 float 一组重建 quad,若渲染器以 TRIANGLES(每 3 顶点)等模式输出,
     * 分组会错乱成畸形几何。捕获时检测到非 QUADS 的 RenderType 即整体放弃(返回
     * null)——宁可无描边,不错位渲染。
     */
    private static final class CaptureSource implements MultiBufferSource {
        /** 所有 RenderType 共享的顶点池:每 (RenderType → consumer) 独立记录,finish 汇总。 */
        private final java.util.IdentityHashMap<RenderType, CaptureVertexConsumer> consumers =
                new java.util.IdentityHashMap<>();
        /** RenderType 的 getBuffer 请求顺序(基础层在前;纹理选择与层编号依赖此序)。 */
        private final java.util.List<RenderType> rtOrder = new java.util.ArrayList<>();
        /** false = 捕获流中出现非 QUADS 的 RenderType,放弃本次捕获。 */
        private boolean quadsOnly = true;

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            if (renderType.mode() != VertexFormat.Mode.QUADS) {
                this.quadsOnly = false;
            }
            if (!this.consumers.containsKey(renderType)) {
                this.rtOrder.add(renderType);
            }
            // 同一 RenderType 返回同一实例(渲染器会对同一 RT 连续写入);
            // 不同 RenderType 返回不同实例 → VertexMultiConsumer 不判重。
            return this.consumers.computeIfAbsent(renderType, rt -> new CaptureVertexConsumer());
        }

        /** 汇总捕获结果;无顶点或流非 QUADS 返回 null。 */
        CapturedGeometry finish() {
            return finishCapture(this.rtOrder, this.consumers, this.quadsOnly);
        }
    }

    /**
     * 捕获用 BufferSource(<b>全局缓冲防护</b>,直写全局型渲染器统一防护):
     * 某些自绘渲染器(如 TACZ {@code BedrockModel.render})不写传入的
     * MultiBufferSource,而是直接取 {@code Minecraft.renderBuffers().bufferSource()}
     * 写全局共享缓冲 —— 不替换全局的捕获既拿不到几何,还会让渲染器把顶点
     * 漏进真实渲染管线(错位图形)。捕获期间把 RenderBuffers.bufferSource
     * 字段临时替换成本类实例(渲染线程单线程,同步换/还原,见
     * {@link #capturePlain}),这些渲染器就会把顶点写进捕获器,
     * 其完整渲染链(前置变换/动画/配件)从构造上与本体一致。
     */
    private static final class CaptureBufferSource extends MultiBufferSource.BufferSource {
        private final java.util.IdentityHashMap<RenderType, CaptureVertexConsumer> consumers =
                new java.util.IdentityHashMap<>();
        /** RenderType 的 getBuffer 请求顺序(与 CaptureSource 同语义)。 */
        private final java.util.List<RenderType> rtOrder = new java.util.ArrayList<>();
        private boolean quadsOnly = true;

        CaptureBufferSource() {
            // 占位 fallback builder + 空 fixedBuffers:本类只做捕获,不真正上屏
            super(new ByteBufferBuilder(256), new LinkedHashMap<>());
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            if (renderType.mode() != VertexFormat.Mode.QUADS) {
                this.quadsOnly = false;
            }
            if (!this.consumers.containsKey(renderType)) {
                this.rtOrder.add(renderType);
            }
            return this.consumers.computeIfAbsent(renderType, rt -> new CaptureVertexConsumer());
        }

        /** endBatch 一律空操作:绝不上屏(捕获语义)。 */
        @Override
        public void endBatch() {
        }

        /** endBatch(RenderType) 一律空操作:绝不上屏(捕获语义)。 */
        @Override
        public void endBatch(RenderType renderType) {
        }

        /** 汇总捕获结果;无顶点或流非 QUADS 返回 null。 */
        CapturedGeometry finish() {
            return finishCapture(this.rtOrder, this.consumers, this.quadsOnly);
        }
    }

    /**
     * 汇总各 consumer 的位置/UV 流为 {@link CapturedGeometry}
     * (CaptureSource/CaptureBufferSource 共用)。
     * <p>
     * 2026-10-11 修订:按 {@code rtOrder}(getBuffer 请求顺序)合并 —— ① quad 的
     * 来源层编号(quadSources)随序记录,供方案 D 区分"跨层叠绘(glint/glowmask)"
     * 与"同层重复(零厚度贴片正反面)";② 采样纹理跳过附魔 glint 层(半透明噪声图,
     * 曾被"首个可提取纹理"规则误选 → alpha 遮罩完全失效,紫色光斑不贴轮廓)。
     */
    private static CapturedGeometry finishCapture(
            java.util.List<RenderType> rtOrder,
            java.util.IdentityHashMap<RenderType, CaptureVertexConsumer> consumers,
            boolean quadsOnly) {
        if (!quadsOnly) {
            return null;
        }
        // 先算总长,一次性分配;各 consumer 顶点顺序无关紧要(只做 AABB/壳/形状)。
        int total = 0;
        int totalUv = 0;
        for (RenderType rt : rtOrder) {
            CaptureVertexConsumer c = consumers.get(rt);
            if (c != null) {
                total += c.count;
                totalUv += c.uvCount;
            }
        }
        if (total == 0) {
            return null;
        }
        float[] merged = new float[total];
        float[] mergedUv = new float[totalUv];
        int[] quadSources = new int[total / 12];
        int off = 0;
        int uoff = 0;
        int qi = 0;
        for (int ci = 0; ci < rtOrder.size(); ci++) {
            CaptureVertexConsumer c = consumers.get(rtOrder.get(ci));
            if (c == null || c.count == 0) {
                continue;
            }
            System.arraycopy(c.buf, 0, merged, off, c.count);
            if (c.uvCount > 0) {
                System.arraycopy(c.uvs, 0, mergedUv, uoff, c.uvCount);
            }
            int quads = c.count / 12;
            for (int q = 0; q < quads; q++) {
                quadSources[qi++] = ci;
            }
            off += c.count;
            uoff += c.uvCount;
        }
        // 采样纹理:getBuffer 顺序取首个"非 glint"的可提取纹理(基础层优先;
        // glint 层是附魔闪光噪声图,作 alpha 遮罩源 = 遮罩失效)。
        ResourceLocation texture = null;
        for (RenderType rt : rtOrder) {
            ResourceLocation tex = extractTextureOf(rt);
            if (tex != null && !isGlintTexture(tex)) {
                texture = tex;
                break;
            }
        }
        return new CapturedGeometry(merged, mergedUv, texture, quadSources);
    }

    /**
     * 附魔 glint 层纹理判定(2026-10-11):vanilla 的附魔闪光层
     * (enchanted_glint_item/entity/glint 等)是半透明噪声图,叠加在本体之上,
     * <b>不是</b>本体几何纹理 —— 作 alpha 遮罩源会让形状遮罩完全失效。
     *
     * @param tex 待判定纹理
     * @return true = glint 层纹理(禁作遮罩源)
     */
    private static boolean isGlintTexture(ResourceLocation tex) {
        return tex != null && tex.getPath().contains("glint");
    }

    /**
     * 捕获用 VertexConsumer(v2.1):记录 addVertex(x,y,z) 位置 + setUv(u,v)
     * (贴图形状描边需要,渲染器不写 UV 时流比顶点短,发射按下标防御),
     * 其余 setColor/setUv1/setUv2/setNormal 一律忽略(顶点数上限保护)。
     */
    private static final class CaptureVertexConsumer implements VertexConsumer {
        /** 顶点数上限(约 30k 顶点 = 7.5k quad,防止失控放大壳)。 */
        private static final int MAX_FLOATS = 65536 * 3 * 4;

        private float[] buf = new float[1024 * 3];
        private int count = 0; // 已写位置 float 数(= 顶点数×3)
        private float[] uvs = new float[1024 * 2];
        private int uvCount = 0; // 已写 UV float 数(= 有 UV 的顶点数×2)

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count >= MAX_FLOATS) {
                return this; // 超限:忽略后续顶点
            }
            if (count + 3 > buf.length) {
                buf = java.util.Arrays.copyOf(buf, Math.min(buf.length * 2, MAX_FLOATS));
            }
            if (count + 3 <= buf.length) {
                buf[count++] = x;
                buf[count++] = y;
                buf[count++] = z;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (uvCount + 2 > uvs.length) {
                uvs = java.util.Arrays.copyOf(uvs, Math.min(uvs.length * 2, MAX_FLOATS / 3 * 2));
            }
            if (uvCount + 2 <= uvs.length) {
                uvs[uvCount++] = u;
                uvs[uvCount++] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    /**
     * 渲染捕获几何的描边壳(通用 BEWLR 兜底):v2/v1 双算法派发。
     * <p>
     * <b>v2 = 规范化捕获(默认,Config.BEWLR_CAPTURE_V2)</b>:
     * <ul>
     *   <li>用<b>空 PoseStack(identity)</b>调 renderByItem 捕获 → 顶点 = 渲染器
     *       <b>本地模型空间</b>坐标(仅含渲染器自己的内部预变换,与相机/GUI 像素空间
     *       无关)—— 已知模组的内部预变换(永恒星光 scale(1,-1,-1)、灾变
     *       translate(0.5,0.5,0.5)+scale(1,-1,-1) 等)缩放幅度都是 1,本地空间
     *       仍是标准模型单位;</li>
     *   <li><b>守卫全 context 统一</b>(span ≤ 64 且 |中心| ≤ 16):GUI 捕获的也是
     *       模型单位,不再需要 v1 的"仅手持查中心距离"例外 —— 任何 context 下
     *       "跨度巨大"或"中心远离原点"都只能是渲染器输出的垃圾("形状不大、
     *       位置很远"的相机空间垃圾在本地空间必然离原点极远,一律拦下);</li>
     *   <li>顶点法线平均外扩的<b>外扩量与兄弟路径像素语义一致</b>:GUI =
     *       thickness×THICKNESS_SCALE/16(与核心 renderGui3dInflate 同款,1 模型
     *       单位 = 16px),世界 context = thickness×THICKNESS_SCALE×BEWLR_3D_SCALE
     *       (与 Lionfish renderPerCube 同款)—— 不再是 v1 的 ∝maxSpan(随物体尺寸变化);</li>
     *   <li>展开后的本地顶点按<b>真实 pose 矩阵</b>重放(CPU transformPosition,与
     *       renderVertexNormalExpand 同构)→ 位置与 v1/本体 100% 一致。</li>
     * </ul>
     * 渲染器若强依赖真实 pose(读矩阵做朝向/相机运算,如 TACZ 第一人称),v2 捕获
     * 可能为空(渲染器拒画)或被守卫拒绝:前者自动回退 v1(今日行为),后者直接跳过
     * (本地空间垃圾,v1 也会画错,不重试)。
     * <p>
     * <b>v1 = 旧算法(回退)</b>:复制当前 pose 捕获 → 顶点 = 最终绘制坐标;手持
     * context 才有中心距离守卫(GUI 像素空间与 GROUND/FIXED 可远离原点,不能一刀切);
     * 外扩量 ∝ maxSpan。
     *
     * @param stack     物品(BEWLR)
     * @param pose      当前描边 pose(与本体 renderByItem 同款矩阵链)
     * @param context   当前显示上下文
     * @param color     描边 ARGB
     * @param thickness 描边厚度量(像素语义)
     * @return true = 捕获到几何并已绘制;false = 捕获失败 / 守卫拒绝
     */
    boolean renderCaptured(ItemStack stack, PoseStack pose, ItemDisplayContext context,
                           int color, float thickness) {
        if (Config.BEWLR_CAPTURE_V2.get()) {
            Boolean v2 = renderCapturedV2(stack, pose, context, color, thickness);
            if (v2 != null) {
                // true = v2 已绘制;false = v2 守卫拒绝(本地空间垃圾,v1 也必然错,不重试)
                return v2;
            }
            // null = v2 捕获失败(渲染器强依赖真实 pose 拒画/输出空)→ 回退 v1(今日行为)
        }
        return renderCapturedV1(stack, pose, context, color, thickness);
    }

    /**
     * v2:规范化捕获 —— 空 PoseStack 捕获本地模型空间几何(全局缓冲防护已统一并入
     * capturePlain,直写全局型渲染器同样能被本路径捕获)→ 全 context 统一守卫 →
     * 按几何形态双路发射:
     * <ul>
     *   <li><b>平坦几何</b>(单薄一轴,如 TACZ GUI 的 slot 贴片 / 扁平 2D 武器):法线
     *       外扩只会沿面法线(屏幕上不可见)→ <b>面内 8 方向偏移</b>;捕获到渲染器采样
     *       纹理时用 {@code armorOutlineRenderType} 贴图 alpha 形状描边(枪形轮廓),
     *       否则纯几何;</li>
     *   <li><b>立体几何</b>:顶点法线平均外扩 + 真实 pose 矩阵重放(原逻辑)。</li>
     * </ul>
     *
     * @param stack     物品(BEWLR)
     * @param pose      当前描边 pose(重放矩阵;捕获不用它)
     * @param context   当前显示上下文
     * @param color     描边 ARGB
     * @param thickness 描边厚度量(像素语义)
     * @return {@link Boolean#TRUE} = 已绘制;{@link Boolean#FALSE} = 守卫拒绝(不回退 v1);
     *         null = 捕获失败(渲染器强依赖真实 pose,回退 v1)
     */
    private Boolean renderCapturedV2(ItemStack stack, PoseStack pose, ItemDisplayContext context,
                                     int color, float thickness) {
        if (thickness <= 0f || renderer.outlineShader == null) {
            return null; // 前置失败与 v1 同判,交由 v1 统一返回 false
        }
        // 空 PoseStack(identity)捕获:顶点 = 渲染器本地模型空间坐标(相机无关);
        // 全局缓冲防护已统一并入 capturePlain(直写全局渲染器同样写进捕获器)
        CapturedGeometry geo = captureLocal(stack, context);
        float[] verts = geo == null ? null : geo.positions();
        if (verts == null || verts.length < 12) {
            return null; // 无几何或不足 1 个 quad:渲染器强依赖真实 pose 拒画 → 回退 v1
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < verts.length; i += 3) {
            float x = verts[i], y = verts[i + 1], z = verts[i + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        if (maxX - minX < 1e-6f && maxY - minY < 1e-6f && maxZ - minZ < 1e-6f) {
            return Boolean.FALSE; // 退化几何(不画;同一几何 v1 也会退化)
        }
        // ⚠️ 守卫(本地空间,全 context 统一):本地几何是标准模型单位(±几单位、
        // 中心近原点),不需要 v1 的"仅手持查中心距离"例外 —— GUI 捕获同样是模型单位,
        // 任何 context 下"跨度巨大"或"中心远离原点"都只能是渲染器输出的垃圾。
        if (maxX - minX > CAPTURE_MAX_SPAN
                || maxY - minY > CAPTURE_MAX_SPAN
                || maxZ - minZ > CAPTURE_MAX_SPAN) {
            logCaptureReject(stack, context, "v2:span", verts.length,
                    maxX - minX, maxY - minY, maxZ - minZ, -1.0f);
            return Boolean.FALSE;
        }
        float centerLen = 0.5f * (float) Math.sqrt(
                (minX + maxX) * (minX + maxX)
                        + (minY + maxY) * (minY + maxY)
                        + (minZ + maxZ) * (minZ + maxZ));
        if (centerLen > CAPTURE_MAX_CENTER_DIST) {
            logCaptureReject(stack, context, "v2:center-dist", verts.length,
                    maxX - minX, maxY - minY, maxZ - minZ, centerLen);
            return Boolean.FALSE;
        }
        renderer.setOutlineAlphaBoost(2.0f);
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        // v2.1 平坦几何检测(2026-10-11 方案 C 修订):单薄一轴(厚度 < 2% 最大跨度)
        // <b>或</b>全部 quad 法线平行于同一轴(平面贴片,可带手柄/双面微厚度)→
        // 面内 8 方向偏移 + 贴图形状;立体几何 → 顶点法线平均外扩(原逻辑)。
        // ⚠️ 纯 AABB 跨度阈值对"2D 贴片 + 手柄小深度"的 2D 武器(Hazen 系列)漏判:
        // 贴片落入立体路径 → geoOutlineRenderType 纯白无遮罩 → 矩形 quad 的描边
        // = 矩形本身("2D 平面渲染为方形平面"事故,铁律 6.5 同源)→ 法线检测根治。
        float sx = maxX - minX, sy = maxY - minY, sz = maxZ - minZ;
        float maxSpan = Math.max(sx, Math.max(sy, sz));
        float minSpan = Math.min(sx, Math.min(sy, sz));
        if (minSpan < Math.max(1e-4f, maxSpan * 0.02f) || isFlatGeometry(verts)) {
            // ⚠️ 贴图形状必须拿到捕获纹理;提取失败(RenderType 无纹理状态/自定义绑定)
            // 时退 WHITE 会画成实心矩形("方形背景")→ 宁可无描边:回退 v1(v1 对同一
            // 平坦几何的发射同样不可见,最终无描边)。
            if (geo.texture() == null) {
                return null;
            }
            emitCapturedFlat(geo, pose, context, color, thickness, packedColor, sx, sy, sz);
            renderer.outlineBuffers.endBatch();
            return Boolean.TRUE;
        }
        // —— 方案 D(2026-10-11):混合几何双发射 ——
        // 先分类:零厚度贴片 quad(正/反面重合的重复 quad —— GeckoLib 零厚度 cube 的
        // 正反两面顶点位集相同)vs 有体积 quad。零厚度装饰 quad 沿自身面法线外扩 =
        // 与本体平行的纯白无遮罩矩形("2D 平面渲染为方形平面"事故:Hazen starfury
        // 翅膀 8×8×0 / volcano 主刃 17×32×0)→ 改走面内 8 方向偏移 + 贴图 alpha 形状;
        // 有体积 quad 保持顶点法线平均外扩(灾变/盔甲等真 3D 路径零影响)。
        int quads = verts.length / 12; // 尾数不足一个 quad 时丢弃(防御,与 v1 一致)
        int[] src = geo.quadSources();
        long[] sigs = new long[quads];
        // 签名 → 层编号 → 出现次数:同层重复 = 零厚度贴片正反面;跨层重复 = glint/glowmask 叠绘
        Map<Long, Map<Integer, Integer>> sigCounts = new HashMap<>(quads * 2);
        for (int q = 0; q < quads; q++) {
            sigs[q] = quadHash(verts, q * 12);
            sigCounts.computeIfAbsent(sigs[q], k -> new HashMap<>())
                    .merge(src[q], 1, Integer::sum);
        }
        boolean[] zeroThickness = new boolean[quads];
        boolean[] overlayDup = new boolean[quads];
        int flatQuads = 0, solidQuads = 0, overlayQuads = 0;
        for (int q = 0; q < quads; q++) {
            Map<Integer, Integer> perConsumer = sigCounts.get(sigs[q]);
            boolean cross = perConsumer.size() > 1; // 该几何被多层绘制(glint/glowmask 叠层)
            if (cross) {
                int earliest = Integer.MAX_VALUE;
                for (int id : perConsumer.keySet()) {
                    earliest = Math.min(earliest, id);
                }
                if (src[q] != earliest) {
                    // 非基础层的叠绘副本:跳过(保留基础层那份)—— 否则"重复 quad = 零厚度"
                    // 判定会被附魔双写全部误触发(2026-10-11:solid=0/zeroThick=ALL 根因)
                    overlayDup[q] = true;
                    overlayQuads++;
                    continue;
                }
            }
            zeroThickness[q] = perConsumer.getOrDefault(src[q], 0) >= 2;
            if (zeroThickness[q]) {
                flatQuads++;
            } else {
                solidQuads++;
            }
        }
        // 模型空间外扩量,与兄弟路径像素语义一致(见 renderCaptured javadoc):
        //   GUI = 标准 3D GUI 路径(renderGui3dInflate:thickness×THICKNESS_SCALE/16);
        //   世界 = Lionfish 逐 cube 路径(renderPerCube:thickness×THICKNESS_SCALE×BEWLR_3D_SCALE)。
        float offsetLocal = context == ItemDisplayContext.GUI
                ? thickness * OutlineRenderer.THICKNESS_SCALE / 16.0f
                : thickness * OutlineRenderer.THICKNESS_SCALE * OutlineRenderer.bewlrInflatePerThickness();
        // 展开后的本地顶点按真实 pose 矩阵重放(与 renderVertexNormalExpand 同构)
        Matrix4f poseMatrix = pose.last().pose();
        Vector3f tmp = new Vector3f(); // 复用,避免每顶点分配
        // ① 有体积 quad:顶点法线平均外扩(原逻辑,geoOutlineRenderType 纯几何)
        if (solidQuads > 0) {
            Map<OutlineRenderer.Position, Vector3f> normals = aggregateNormals(verts);
            VertexConsumer consumer = renderer.outlineBuffers
                    .getBuffer(renderer.geoOutlineRenderType(color));
            for (int q = 0; q < quads; q++) {
                if (zeroThickness[q] || overlayDup[q]) {
                    continue; // 零厚度贴片/跨层叠绘副本不进立体壳(法线外扩 = 方形环绕)
                }
                int b = q * 12;
                for (int v = 0; v < 4; v++) {
                    int i = b + v * 3;
                    Vector3f dir = normals.get(new OutlineRenderer.Position(verts[i], verts[i + 1], verts[i + 2]));
                    // ε 推挤(见 OutlineRenderer.NORMAL_EPSILON):外扩顶点额外沿法线推
                    // 1e-4,描边面与本体表面永不共面(防 z-fighting 边界情况)
                    float k = dir != null ? offsetLocal + OutlineRenderer.NORMAL_EPSILON : 0.0f;
                    Vector3f p = poseMatrix.transformPosition(
                            verts[i] + (dir != null ? dir.x() * k : 0.0f),
                            verts[i + 1] + (dir != null ? dir.y() * k : 0.0f),
                            verts[i + 2] + (dir != null ? dir.z() * k : 0.0f), tmp);
                    consumer.addVertex(p.x(), p.y(), p.z(), packedColor, 0.0f, 0.0f,
                            OverlayTexture.NO_OVERLAY, OutlineRenderer.FULL_BRIGHT, 0.0f, 1.0f, 0.0f);
                }
            }
            renderer.outlineBuffers.endBatch();
        }
        // ② 零厚度贴片 quad:面内 8 方向偏移 + 贴图 alpha 形状(与平坦路径同语义,
        // 描边跟随装饰轮廓);贴图缺失时跳过(禁退 WHITE —— alpha 全 1 = 方形背景,
        // 铁律 6.9);每段发射后即时 endBatch(铁律 6.7 批次闭合)。
        if (flatQuads > 0 && geo.texture() != null) {
            float d = offsetLocal; // 像素语义与 ① 一致(GUI /16,世界 ×BEWLR_3D_SCALE)
            float[] uvs = geo.uvs();
            VertexConsumer consumer = renderer.outlineBuffers
                    .getBuffer(renderer.armorOutlineRenderType(geo.texture(), color));
            for (int q = 0; q < quads; q++) {
                if (!zeroThickness[q] || overlayDup[q]) {
                    continue; // 只发射基础层的零厚度贴片(叠绘副本跳过)
                }
                int b = q * 12;
                int axis = quadNormalAxis(verts, b);
                if (axis < 0) {
                    continue; // 退化 quad(零面积):跳过
                }
                // 法线轴 → 面内两轴(8 方向偏移沿面内,不沿法线)
                float ux, uy, uz, vx, vy, vz;
                if (axis == 0) {
                    ux = 0; uy = 1; uz = 0; vx = 0; vy = 0; vz = 1;
                } else if (axis == 1) {
                    ux = 1; uy = 0; uz = 0; vx = 0; vy = 0; vz = 1;
                } else {
                    ux = 1; uy = 0; uz = 0; vx = 0; vy = 1; vz = 0;
                }
                for (float[] dir : OutlineRenderer.DIAG_OFFSETS) {
                    float ox = (ux * dir[0] + vx * dir[1]) * d;
                    float oy = (uy * dir[0] + vy * dir[1]) * d;
                    float oz = (uz * dir[0] + vz * dir[1]) * d;
                    for (int v = 0; v < 4; v++) {
                        int i = b + v * 3;
                        int uvBase = (i / 3) * 2;
                        float u = uvBase + 1 < uvs.length ? uvs[uvBase] : 0.0f;
                        float vv = uvBase + 1 < uvs.length ? uvs[uvBase + 1] : 0.0f;
                        Vector3f p = poseMatrix.transformPosition(
                                verts[i] + ox, verts[i + 1] + oy, verts[i + 2] + oz, tmp);
                        consumer.addVertex(p.x(), p.y(), p.z(), packedColor, u, vv,
                                OverlayTexture.NO_OVERLAY, OutlineRenderer.FULL_BRIGHT, 0.0f, 1.0f, 0.0f);
                    }
                }
            }
            renderer.outlineBuffers.endBatch();
        }
        // 节流验证信号(方案 D):下次实机看 flatmix# 确认分类与双发射生效
        var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        logCaptureDiag("flatmix#" + itemId,
                String.format(Locale.ROOT,
                        "[Enchanted Outlines] 混合几何双发射(mix 节流): item=%s total=%d solid=%d zeroThick=%d overlaySkip=%d tex=%s",
                        itemId, quads, solidQuads, flatQuads, overlayQuads, geo.texture()));
        if (solidQuads == 0 && (flatQuads == 0 || geo.texture() == null)) {
            return null; // 无可发射几何:交 v1(同平坦分支语义)
        }
        return Boolean.TRUE;
    }

    /**
     * 捕获几何是否"平坦贴片"(2026-10-11 方案 C,Hazen 方形平面描边修复)。
     * <p>
     * 判据:<b>全部有效 quad 的法线互相平行于同一坐标轴</b>(取绝对值比较,正反面
     * 相对的两片也算平行)。几何语义 = 一张平面贴片(可带微小厚度/手柄深度),
     * 法线外扩对它屏幕上不可见、纯几何壳会把矩形 quad 画成方形平面
     * (铁律 6.5 "扁平 cube 纯几何外扩 = 平面方形" 同源)→ 必须走平坦路径
     * (面内 8 方向偏移 + 贴图 alpha 形状)。
     * <p>
     * 立体模型(任意 quad 法线偏离主轴 > ~18°,或法线跨 ≥2 独立轴)→ false,
     * 保持顶点法线平均外扩(灾变/盔甲等真 3D 路径不受影响)。
     *
     * @param verts 捕获顶点(x,y,z 三元组,quad 为 4 顶点一组)
     * @return true = 平面贴片(走平坦路径);false = 立体几何
     */
    private static boolean isFlatGeometry(float[] verts) {
        int quads = verts.length / 12;
        int dominant = -1; // 主法线轴(0=x, 1=y, 2=z);-1 = 尚未确定
        for (int q = 0; q < quads; q++) {
            int b = q * 12;
            // quad 法线 = (v1-v0) × (v2-v0)(右手系,方向不重要,取绝对值比较)
            float ax = verts[b + 3] - verts[b], ay = verts[b + 4] - verts[b + 1],
                    az = verts[b + 5] - verts[b + 2];
            float bx = verts[b + 6] - verts[b], by = verts[b + 7] - verts[b + 1],
                    bz = verts[b + 8] - verts[b + 2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-7f) {
                continue; // 退化 quad(共线/零面积):不参与判定
            }
            nx /= len;
            ny /= len;
            nz /= len;
            int axis = Math.abs(nx) >= Math.abs(ny)
                    ? (Math.abs(nx) >= Math.abs(nz) ? 0 : 2)
                    : (Math.abs(ny) >= Math.abs(nz) ? 1 : 2);
            float axisComp = axis == 0 ? Math.abs(nx) : axis == 1 ? Math.abs(ny) : Math.abs(nz);
            // 0.95 ≈ 主轴偏角 18°:轴对齐 bedrock/geo 模型恒 1.0;弯曲/斜切面片
            // 超出锥角视为立体(宁可 3D 路径,不可把曲面当平面发 8 方向偏移)
            if (axisComp < 0.95f) {
                return false;
            }
            if (dominant == -1) {
                dominant = axis;
            } else if (dominant != axis) {
                return false; // 法线跨轴 → 立体
            }
        }
        return dominant != -1; // 至少一个有效 quad,且全部平行于同一轴
    }

    /**
     * quad 位置哈希(4 角量化到 1e-4 网格后做顺序无关混合,免分配)。
     * <p>
     * 零厚度贴片的正/反面重合对(GeckoLib 零厚度 cube 的正反两面,顶点位集相同)
     * 哈希一致 → 方案 D 按"同层同哈希出现 ≥2 次"识别零厚度贴片;真 3D cube 的
     * 相对两面坐标不同、各面四角组合互异,不会误判。用 XOR 混合实现顺序无关
     * (角点书写顺序不影响结果),量化容忍浮点尾差。
     *
     * @param verts 捕获顶点数组
     * @param base  该 quad 首顶点在数组中的下标(quad 占 12 个 float)
     * @return 64 位位置哈希
     */
    private static long quadHash(float[] verts, int base) {
        long h = 0;
        for (int v = 0; v < 4; v++) {
            int i = base + v * 3;
            long cx = Math.round(verts[i] * 10000f);
            long cy = Math.round(verts[i + 1] * 10000f);
            long cz = Math.round(verts[i + 2] * 10000f);
            long ch = (cx + 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
            ch ^= (cy + 0x7C4A7C159E3779B9L) * 0x94D049BB133111EBL;
            ch ^= (cz + 0xD1B54A32D192ED03L) * 0x9E3779B97F4A7C15L;
            h ^= ch;
        }
        return h;
    }

    /**
     * quad 法线主轴(由 (v1-v0)×(v2-v0) 取绝对值最大分量)。
     *
     * @param verts 捕获顶点数组
     * @param base  该 quad 首顶点下标
     * @return 0=x / 1=y / 2=z;退化 quad(零面积)返回 -1
     */
    private static int quadNormalAxis(float[] verts, int base) {
        float ax = verts[base + 3] - verts[base], ay = verts[base + 4] - verts[base + 1],
                az = verts[base + 5] - verts[base + 2];
        float bx = verts[base + 6] - verts[base], by = verts[base + 7] - verts[base + 1],
                bz = verts[base + 8] - verts[base + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float axN = Math.abs(nx), ayN = Math.abs(ny), azN = Math.abs(nz);
        if (axN < 1e-7f && ayN < 1e-7f && azN < 1e-7f) {
            return -1; // 零面积(共线顶点)
        }
        if (axN >= ayN && axN >= azN) {
            return 0;
        }
        return ayN >= azN ? 1 : 2;
    }

    /**
     * v2.1 平坦捕获几何发射:面内 8 方向偏移(v1/v2 的法线外扩对薄面片只会沿面法线,
     * 屏幕上不可见)。调用方已保证捕获纹理非 null(提取失败时本路径不被调用,
     * 见 renderCapturedV2 平坦分支)→ 用 {@code armorOutlineRenderType} 贴图 alpha
     * 形状描边 —— 轮廓跟随贴图形状(枪形)。UV 用捕获流(渲染器未写 UV 的顶点按
     * 0 防御)。偏移量像素语义与扁平物品标准路径一致。
     *
     * @param geo        捕获几何(positions/uvs/texture)
     * @param pose       真实 pose(重放矩阵)
     * @param context    显示上下文(GUI 像素语义 / 世界模型单位语义)
     * @param color      描边 ARGB
     * @param thickness  描边厚度
     * @param packedColor 描边色(ABGR 打包)
     * @param sx sy sz   AABB 三轴跨度(选面内两轴用)
     */
    private void emitCapturedFlat(CapturedGeometry geo, PoseStack pose, ItemDisplayContext context,
                                  int color, float thickness, int packedColor,
                                  float sx, float sy, float sz) {
        // 面内偏移量(模型单位):GUI = 像素语义(扁平物品标准路径 thickness×0.5px → /16);
        // 世界 = Lionfish 兄弟路径语义(×BEWLR_3D_SCALE)
        float d = context == ItemDisplayContext.GUI
                ? thickness * OutlineRenderer.THICKNESS_SCALE / 16.0f
                : thickness * OutlineRenderer.THICKNESS_SCALE * OutlineRenderer.bewlrInflatePerThickness();
        // 面内两轴 = 除最薄轴外的两根 AABB 轴(最薄轴即面法线)
        float ux, uy, uz, vx, vy, vz;
        if (sx <= sy && sx <= sz) {
            ux = 1; uy = 0; uz = 0; vx = 0; vy = 1; vz = 0;
        } else if (sy <= sx && sy <= sz) {
            ux = 1; uy = 0; uz = 0; vx = 0; vy = 0; vz = 1;
        } else {
            ux = 0; uy = 1; uz = 0; vx = 0; vy = 0; vz = 1;
        }
        // 采样纹理:调用方已保证非 null(见 renderCapturedV2 平坦分支的守卫)
        // 节流诊断(flat):保留,便于确认提取成功的纹理是否符合预期。
        ResourceLocation sampleTexture = geo.texture();
        logCaptureDiag("flat#" + sampleTexture,
                String.format(Locale.ROOT,
                        "[Enchanted Outlines] 平坦捕获几何贴图形状描边(flat 节流): sample=%s extracted=%s uvPairs=%d",
                        sampleTexture, geo.texture() != null, geo.uvs().length / 2));
        VertexConsumer consumer = renderer.outlineBuffers
                .getBuffer(renderer.armorOutlineRenderType(sampleTexture, color));
        Matrix4f poseMatrix = pose.last().pose();
        Vector3f tmp = new Vector3f();
        float[] verts = geo.positions();
        float[] uvs = geo.uvs();
        int quads = verts.length / 12;
        for (float[] dir : OutlineRenderer.DIAG_OFFSETS) {
            float ox = (ux * dir[0] + vx * dir[1]) * d;
            float oy = (uy * dir[0] + vy * dir[1]) * d;
            float oz = (uz * dir[0] + vz * dir[1]) * d;
            for (int q = 0; q < quads; q++) {
                int b = q * 12;
                for (int v = 0; v < 4; v++) {
                    int i = b + v * 3;
                    int uvBase = (i / 3) * 2;
                    float u = uvBase + 1 < uvs.length ? uvs[uvBase] : 0.0f;
                    float vv = uvBase + 1 < uvs.length ? uvs[uvBase + 1] : 0.0f;
                    Vector3f p = poseMatrix.transformPosition(
                            verts[i] + ox, verts[i + 1] + oy, verts[i + 2] + oz, tmp);
                    consumer.addVertex(p.x(), p.y(), p.z(), packedColor, u, vv,
                            OverlayTexture.NO_OVERLAY, OutlineRenderer.FULL_BRIGHT, 0.0f, 1.0f, 0.0f);
                }
            }
        }
    }

    /**
     * v1:旧算法 —— 复制当前 pose 捕获,顶点 = 最终绘制坐标(含 pose 与渲染器内部
     * 预变换),描边不再叠加外层变换,直接绕这些坐标做顶点法线平均外扩。
     * 外扩距离 = thickness × THICKNESS_SCALE × bewlrInflatePerThickness() × 物体
     * 半跨度(随物体尺寸成比例,跨 GUI/世界坐标空间一致);守卫仅手持 context 查
     * 中心距离(GUI 像素空间与 GROUND/FIXED 可合法远离原点,不能一刀切)。
     *
     * @param stack     物品(BEWLR)
     * @param pose      当前描边 pose(用于捕获;描边本体坐标已含其变换,不再叠加)
     * @param context   当前显示上下文
     * @param color     描边 ARGB
     * @param thickness 描边厚度量(像素语义)
     * @return true = 捕获到几何并已绘制;false = 捕获失败 / 无顶点 / 守卫拒绝
     */
    private boolean renderCapturedV1(ItemStack stack, PoseStack pose, ItemDisplayContext context,
                                     int color, float thickness) {
        if (thickness <= 0f || renderer.outlineShader == null) {
            return false;
        }
        CapturedGeometry geo = capturePlain(stack, pose.last().pose(), context);
        // 全局缓冲防护已并入 capturePlain(2026-10-02 统一):真实 pose + 全局替换窗口,
        // 直写全局渲染器(TACZ 型)捕获到的 = 本体最终绘制坐标 → 下方原样发射零重放。
        float[] verts = geo == null ? null : geo.positions();
        if (verts == null || verts.length < 12) {
            return false; // 无几何或不足 1 个 quad
        }
        // 计算全局 AABB 中心(最终绘制坐标)
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < verts.length; i += 3) {
            float x = verts[i], y = verts[i + 1], z = verts[i + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        if (maxX - minX < 1e-6f && maxY - minY < 1e-6f && maxZ - minZ < 1e-6f) {
            return false; // 退化
        }
        // ⚠️ 捕获尺寸守卫:某些自绘 BEWLR(如 TACZ 第一人称)在显示变换后套了
        // 相机/动画预变换,捕获顶点被抬到相机/世界空间 → AABB 尺寸异常大。
        // 绕这种中心放大只会画出"天上巨大模型、本体无描边"的错乱壳。任一轴
        // 跨度超过阈值(模型单位,正常手持/GUI 物品远小于此)即视为捕获失败,
        // 直接跳过该次描边(宁可无描边,不渲染错位巨壳)。
        if (maxX - minX > CAPTURE_MAX_SPAN
                || maxY - minY > CAPTURE_MAX_SPAN
                || maxZ - minZ > CAPTURE_MAX_SPAN) {
            logCaptureReject(stack, context, "span", verts.length,
                    maxX - minX, maxY - minY, maxZ - minZ, -1.0f);
            return false;
        }
        // ⚠️ 中心距离守卫(2026-09-25,手持 context 专用):见 CAPTURE_MAX_CENTER_DIST。
        // 手持物品正常离视角原点仅几单位;被抬到相机/世界空间的垃圾捕获"形状不大、
        // 位置很远",只有本守卫能拦住(span 守卫对它无能为力)。
        if (context.firstPerson()
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
            // |AABB 中心| = 0.5 × |(min+max)|,利用已有 min/max 免遍历
            float centerLen = 0.5f * (float) Math.sqrt(
                    (minX + maxX) * (minX + maxX)
                            + (minY + maxY) * (minY + maxY)
                            + (minZ + maxZ) * (minZ + maxZ));
            if (centerLen > CAPTURE_MAX_CENTER_DIST) {
                logCaptureReject(stack, context, "center-dist", verts.length,
                        maxX - minX, maxY - minY, maxZ - minZ, centerLen);
                return false;
            }
        }
        renderer.setOutlineAlphaBoost(2.0f);
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        VertexConsumer consumer = renderer.outlineBuffers.getBuffer(renderer.geoOutlineRenderType(color));
        // CaptureSource 只接受 QUADS 流且每 consumer 写满整 quad → 长度必为 12 的倍数;
        // 尾数不足一个 quad 时丢弃(防御)。
        int quads = verts.length / 12;

        // 共享顶点法线聚合(v1/v2 共用 helper,数学与核心 prepareExpand 同构)
        Map<OutlineRenderer.Position, Vector3f> normals = aggregateNormals(verts);
        // ⚠️ 法线外扩距离:随物体尺寸成比例(取包围盒最大半跨度),使不同尺寸/坐标空间
        // 的物品描边像素宽度一致 —— 替代旧的 AABB 中心倍率放大(端部鼓胀、易错位)。
        float maxSpan = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        float offset = thickness * OutlineRenderer.THICKNESS_SCALE
                * OutlineRenderer.bewlrInflatePerThickness() * maxSpan * 0.5f;
        for (int q = 0; q < quads; q++) {
            int b = q * 12;
            for (int v = 0; v < 4; v++) {
                int i = b + v * 3;
                Vector3f dir = normals.get(new OutlineRenderer.Position(verts[i], verts[i + 1], verts[i + 2]));
                // ε 推挤(见 OutlineRenderer.NORMAL_EPSILON):外扩顶点额外沿法线推
                // 1e-4,描边面与本体表面永不共面(防 z-fighting 边界情况)
                float k = dir != null ? offset + OutlineRenderer.NORMAL_EPSILON : 0.0f;
                float x = verts[i] + (dir != null ? dir.x() * k : 0.0f);
                float y = verts[i + 1] + (dir != null ? dir.y() * k : 0.0f);
                float z = verts[i + 2] + (dir != null ? dir.z() * k : 0.0f);
                consumer.addVertex(x, y, z, packedColor, 0.0f, 0.0f,
                        OverlayTexture.NO_OVERLAY, OutlineRenderer.FULL_BRIGHT, 0.0f, 1.0f, 0.0f);
            }
        }
        renderer.outlineBuffers.endBatch();
        return true;
    }

    /**
     * 按共享顶点(精确 float 位置)聚合相邻面法线并归一化(v1/v2 共用,与核心
     * prepareExpand 同构):每个 quad 取前 3 顶点叉积得单位面法线,加到该 quad
     * 全部 4 顶点的法线和;零和顶点(退化)法线置零 → 该顶点不外扩。
     *
     * @param verts 捕获顶点流(xyz 交错,长度为 12 的倍数)
     * @return 顶点位置 key → 归一化平均法线
     */
    private static Map<OutlineRenderer.Position, Vector3f> aggregateNormals(float[] verts) {
        int quads = verts.length / 12;
        Map<OutlineRenderer.Position, Vector3f> normals = new java.util.HashMap<>();
        for (int q = 0; q < quads; q++) {
            int b = q * 12;
            float ax = verts[b], ay = verts[b + 1], az = verts[b + 2];
            float bx = verts[b + 3], by = verts[b + 4], bz = verts[b + 5];
            float ucx = verts[b + 6], ucy = verts[b + 7], ucz = verts[b + 8];
            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float vx = ucx - ax, vy = ucy - ay, vz = ucz - az;
            float fnx = uy * vz - uz * vy;
            float fny = uz * vx - ux * vz;
            float fnz = ux * vy - uy * vx;
            float inv = 1.0f / (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (!Float.isFinite(inv)) {
                continue; // 退化面(三点共线)
            }
            fnx *= inv; fny *= inv; fnz *= inv;
            for (int v = 0; v < 4; v++) {
                int it = b + v * 3;
                normals.computeIfAbsent(new OutlineRenderer.Position(verts[it], verts[it + 1], verts[it + 2]),
                        k -> new Vector3f()).add(fnx, fny, fnz);
            }
        }
        // 归一化每个顶点的平均法线(零和跳过 → 该顶点不外扩)
        for (Vector3f n : normals.values()) {
            if (n.lengthSquared() > 1e-6f) {
                n.normalize();
            } else {
                n.set(0.0f, 0.0f, 0.0f);
            }
        }
        return normals;
    }
}
