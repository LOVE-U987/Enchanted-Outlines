package com.enchantedoutlines.mod.outline;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.enchantedoutlines.mod.config.Config;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 绘制时轮廓描边渲染器(GUI / 物品栏 / 快捷栏)。
 * <p>
 * 原理(参考资源包 Enchantment Outlines 的"描边图层"效果):
 * 在物品本体绘制前,用自定义核心着色器把物品模型的 <b>alpha 遮罩以纯描边色</b>
 * 沿 8 个屏幕方向各偏移 {@code thickness} 像素渲染一次;随后原版正常绘制物品本体
 * 覆盖中心区域,露出的外扩环即"沿物品形状的纯色描边"。
 * <p>
 * 与资源包"为每件物品预画描边贴图"不同,这里对任意物品模型(含模组物品)程序化生成,
 * 且每帧解析同一个模型 → 动画物品、NBT 变体、损伤模型天然一致。零烘焙、零像素读回、
 * 零缓存内存;每帧开销 ≈ 8 × quad 数 + 1 次着色器绘制。
 * <p>
 * 渲染路径(GUI 与实体多路并行,共用同一描边着色器):
 * <ul>
 *   <li>GUI 扁平物品:8 方向屏幕偏移;</li>
 *   <li>GUI 3D 物品与手持 3D 物品:顶点法线平均外扩(贴合表面、厚度均匀);</li>
 *   <li>手持扁平物品:整体平面内 8 方向平移;</li>
 *   <li>盔甲穿戴/投掷物实体:逐 cube 绕自身包围盒中心放大壳。</li>
 * </ul>
 * <b>已知限制:</b>BEWLR 自定义渲染物品(盾牌、三叉戟、望远镜已用近似模型支持;
 * 钓鱼竿、地图等占位模型无形状)暂不描边。
 */
public final class OutlineRenderer {

    public static final OutlineRenderer INSTANCE = new OutlineRenderer();

    /** 全亮光照(GUI 物品标准光照)。 */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * 厚度缩放系数。配置里的 thickness 只是"名义像素",实际偏移 = thickness × 系数。
     * <p>
     * 为什么小于 1:描边画的是完整 alpha 遮罩,物品贴图自带的边缘渐变/抗锯齿像素
     * 也会被算进描边,视觉宽度 ≈ 偏移量 + 约 1px 贴图边缘;再加上 8 方向中的对角
     * 偏移距离是 √2 倍且拐角处叠加,1 像素名义偏移的视觉环宽会接近 2px。
     * 0.5 系数让 thickness=1 变成半像素细线,thickness=8 仍有 4px 的粗描边余地。
     */
    private static final float THICKNESS_SCALE = 0.5f;

    /**
     * 手持 3D 物品的顶点法线外扩距离增量(像素每单位)。世界渲染中 3D 物品
     * (三叉戟/盾牌/方块)的描边距离 = thickness × THICKNESS_SCALE × 本系数,
     * 作为 {@link #renderVertexNormalExpand} 的 offset。
     */
    private static final float HAND_INFLATE_PER_THICKNESS = 0.08f;

    /**
     * 盔甲描边放大增量。盔甲模型约 1.8 单位高,远大于手持物品;同样 scale 下外扩的
     * 绝对量按"中心距"放大,轮廓显得过大。取手持系数约一半,视觉描边宽度才接近。
     */
    private static final float ARMOR_INFLATE_PER_THICKNESS = 0.04f;

    /**
     * 投掷物实体描边放大增量。三叉戟实体模型 pole 仅 25px(=1.56 单位)高、宽 1px,
     * 是又细又长的物体:沿用 ARMOR 系数时外扩量按半对角线(≈0.78)计算,
     * thickness=2 下 scale-1 仅 0.04,视觉几乎不可见。取更大的专用系数,
     * 让投掷物在远处也能看到清晰的描边。
     */
    private static final float PROJECTILE_INFLATE_PER_THICKNESS = 0.12f;

    /**
     * 鞘翅描边放大增量。鞘翅翼片是薄板(约 10×20×2 模型单位),形状扁平开阔;
     * 系数介于投掷物与盔甲之间,取 0.06 让穿戴鞘翅时轮廓清晰可见。
     */
    private static final float ELYTRA_INFLATE_PER_THICKNESS = 0.06f;

    /** 8 个屏幕偏移方向。 */
    private static final float[][] OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** 由 RegisterShadersEvent 的 onLoaded 回调写入(F3+T 重载时自动更新)。 */
    private volatile ShaderInstance outlineShader;

    /**
     * 描边专用缓冲源。独立于 GuiGraphics.bufferSource(),渲染完立即 endBatch →
     * 描边一定在物品本体之前绘制,不依赖共享 BufferSource 的遍历顺序。
     */
    private final MultiBufferSource.BufferSource outlineBuffers;

    private RenderType outlineRenderType;
    private RenderType handOutlineRenderType;
    /** 光影兼容世界渲染 RenderType 缓存(内置 emissive shader,按纹理区分:BLOCK_SHEET 与白色/独立纹理)。 */
    private final Map<ResourceLocation, RenderType> worldOutlineRenderTypes = new HashMap<>();
    private BakedModel shieldModel;
    /** 生成 shieldModel 时使用的 transforms(blocking 与非 blocking 不同,需跟踪重建)。 */
    private ItemTransforms shieldTransforms;
    private BakedModel tridentModel;
    /** 生成 tridentModel 时使用的 transforms(in_hand 与 throwing 不同,需跟踪重建)。 */
    private ItemTransforms tridentTransforms;
    private final Map<ResourceLocation, RenderType> armorRenderTypes = new HashMap<>();

    /**
     * "描边色形状纹理"缓存:扁平物品在光影兼容下关闭混色时,为每个 (物品贴图, 描边色)
     * 生成一张 RGB=描边色、A=贴图 alpha 的动态纹理,让描边既保留物品形状又是纯描边色。
     * 纹理注册到 TextureManager(资源重载时自动销毁),本缓存随之清空重建。
     */
    private final Map<ShapeKey, ResourceLocation> shapeTextures = new HashMap<>();

    /** 形状纹理缓存键:物品贴图 + 描边色(RGB)。 */
    private record ShapeKey(ResourceLocation spriteName, int color) {
    }

    /** 三叉戟实体纹理(投掷物 ThrownTrident 的模型材质)。 */
    private static final ResourceLocation TRIDENT_ENTITY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/trident.png");

    /** 鞘翅实体纹理(ElytraLayer 的默认鞘翅材质)。 */
    private static final ResourceLocation ELYTRA_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/elytra.png");

    /**
     * 纯白纹理(1×1,不透明)。光影兼容世界渲染专用。
     * <p>
     * 用于<b>采样物品贴图会导致异常</b>的路径:
     * <ul>
     *   <li>盾牌/三叉戟近似盒模型:盒顶点 UV 是方块图集 stone 精灵的 UV,若采样
     *       BLOCK_SHEET 会被光影误判为方块材质(盾/三叉戟"反射四周方块纹理");
     *       纯白独立纹理 → 无方块材质反查,且 1×1 纹理任意 UV 采样结果相同;</li>
     *   <li>扁平物品(剑/弓/工具)在关闭"附魔光效混合物品颜色"
     *       (Config.ITEM_PIXEL_COLOR_GLINT=false)时:纯白 → 纯描边色,但失去
     *       贴图形状(矩形轮廓)。</li>
     * </ul>
     */
    private static final ResourceLocation WHITE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("enchanted_outlines", "textures/white.png");

    /**
     * 世界渲染是否需走<b>内置 shader 兼容路径</b>的判断逻辑。
     * <p>
     * 光影(Iris/Oculus)接管世界渲染后,自定义 core shader 的行为由 Iris 的
     * {@code iris$shouldSkipThis} 决定:默认配置 {@code allowUnknownShaders=false} 时,
     * 光影激活状态下未知(非 vanilla)shader 的<b>绘制会被 Iris 完全跳过</b>
     * → 描边被渲染为透明。因此:
     * <ul>
     *   <li>光影<b>未激活</b>(未装 Iris、装了但没开光影包)→ 自定义 shader 原样工作,
     *       效果最佳(alpha boost / cutout / 发光全保留);</li>
     *   <li>光影激活且 {@code allowUnknownShaders=true}(用户已在 iris.properties 开启
     *       "允许未知着色器")→ 自定义 shader 可渲染到主 framebuffer,同样完美;</li>
     *   <li>光影激活且 {@code allowUnknownShaders=false}(默认)→ 自定义 shader 被跳过,
     *       世界路径改用内置 {@code entity_translucent_emissive} shader 构造的 RenderType
     *       (见 {@link #worldEmissiveOutlineRenderType()})。</li>
     * </ul>
     * GUI 渲染不经光影,始终用自定义 shader。
     */
    private boolean needVanillaShaderFallback() {
        return isShaderPackInUse() && !isUnknownShadersAllowed();
    }

    /** 反射缓存的 IrisApi 单例(无 Iris 时为 null)。 */
    private static final Object IRIS_API_INSTANCE = resolveIrisApiInstance();

    /** 反射缓存的 IrisApi.isShaderPackInUse(无 Iris 时为 null)。 */
    private static final java.lang.reflect.Method IRIS_IS_SHADER_PACK_IN_USE =
            resolveIrisApiMethod("isShaderPackInUse");

    /** 反射缓存的 IrisConfig 实例(经 Iris.getIrisConfig(),无 Iris 时为 null)。 */
    private static final Object IRIS_CONFIG_INSTANCE = resolveIrisConfigInstance();

    /** 反射缓存的 IrisConfig.shouldAllowUnknownShaders(无 Iris 时为 null)。 */
    private static final java.lang.reflect.Method IRIS_SHOULD_ALLOW_UNKNOWN_SHADERS =
            resolveIrisUnknownShadersMethod();

    /** 反射获取 IrisApi 单例(getInstance 静态方法)。失败(未装 Iris)返回 null。 */
    private static Object resolveIrisApiInstance() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            return api.getMethod("getInstance").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射获取 IrisApi 实例方法。失败返回 null。 */
    private static java.lang.reflect.Method resolveIrisApiMethod(String name) {
        try {
            if (IRIS_API_INSTANCE == null) {
                return null;
            }
            return IRIS_API_INSTANCE.getClass().getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射获取 IrisConfig 单例(经 Iris.getIrisConfig() 静态方法)。失败返回 null。 */
    private static Object resolveIrisConfigInstance() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            return iris.getMethod("getIrisConfig").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射获取 IrisConfig.shouldAllowUnknownShaders 实例方法。失败返回 null。 */
    private static java.lang.reflect.Method resolveIrisUnknownShadersMethod() {
        try {
            if (IRIS_CONFIG_INSTANCE == null) {
                return null;
            }
            return IRIS_CONFIG_INSTANCE.getClass().getMethod("shouldAllowUnknownShaders");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 光影是否激活(Iris 有光影包编译成功并在使用)。
     *
     * @return true = 光影正在接管世界渲染
     */
    private static boolean isShaderPackInUse() {
        try {
            return (Boolean) IRIS_IS_SHADER_PACK_IN_USE.invoke(IRIS_API_INSTANCE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Iris 是否允许未知 shader 渲染(iris.properties 的 allowUnknownShaders)。
     * <p>
     * 默认 false:自定义 core shader 在光影激活时被 Iris 跳过绘制(透明)。
     * 玩家手动开启后,自定义描边 shader 可渲染到主 framebuffer,效果与无光影一致。
     *
     * @return true = 允许,自定义 shader 可用
     */
    private static boolean isUnknownShadersAllowed() {
        try {
            return (Boolean) IRIS_SHOULD_ALLOW_UNKNOWN_SHADERS.invoke(IRIS_CONFIG_INSTANCE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private OutlineRenderer() {
        this.outlineBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(262144));
    }

    /** 着色器加载回调入口(供 EnchantedOutlinesClient 调用)。 */
    public void setOutlineShader(ShaderInstance shader) {
        this.outlineShader = shader;
        // 资源重载(F3+T 触发 RegisterShadersEvent)时,TextureManager 已销毁全部注册纹理,
        // "描边色形状纹理"缓存一并失效 → 清空以便按需重建。
        this.shapeTextures.clear();
    }

    /**
     * 设置描边 alpha 强化倍率(OutlineAlphaBoost uniform)。
     * <p>
     * 描边画的是物品贴图的 alpha 遮罩,贴图边缘自带抗锯齿渐变(alpha&lt;1);
     * 手持描边在远处场景背景下会显得半透明,抬高倍率让描边更实心。
     * GUI 传 1.0(保持原样),手持传 2.0。
     */
    private void setOutlineAlphaBoost(float boost) {
        ShaderInstance shader = this.outlineShader;
        if (shader == null) {
            return;
        }
        // 各渲染路径默认半透明 → 重置 cutout;需要不透明的路径(物品展示框)在 boost
        // 之后再用 setOutlineCutout(true) 覆盖(见 renderHandOutline 6 参重载)。
        setOutlineCutout(false);
        Uniform uniform = shader.getUniform("OutlineAlphaBoost");
        if (uniform != null) {
            uniform.set(boost);
        }
    }

    /**
     * 设置描边是否硬切不透明(OutlineCutout uniform)。
     * <p>
     * 半透明混合下,贴图边缘渐变像素(alpha&lt;1)即使 boost 抬升后仍透出背景 →
     * 物品展示框等<b>背景物体</b>在明亮场景中轮廓几乎不可见。开启后 fsh 把任何
     * 非透明像素切成 alpha=1.0 的纯色,轮廓完全不透明。
     *
     * @param opaque true = 不透明硬切(展示框),false = 半透明(手持/GUI/默认)
     */
    private void setOutlineCutout(boolean opaque) {
        ShaderInstance shader = this.outlineShader;
        if (shader == null) {
            return;
        }
        Uniform uniform = shader.getUniform("OutlineCutout");
        if (uniform != null) {
            uniform.set(opaque ? 1.0f : 0.0f);
        }
    }

    /**
     * 渲染描边。调用方需已完成前置判断(总开关、附魔、entity、custom renderer)。
     *
     * @param pose      GuiGraphics.pose()(原始 GUI 帧 PoseStack)
     * @param model     GUI 上下文解析出的物品 BakedModel
     * @param x         物品格子 x(像素)
     * @param y         物品格子 y(像素)
     * @param quadSize  GuiGraphics 私有 renderItem 的 quadSize 参数(3D 物品的 z 偏移)
     * @param color     ARGB 描边颜色
     * @param thickness 描边厚度(像素,可浮点)
     */
    @SuppressWarnings("deprecation") // getTransforms 在 1.21.1 已过时但 vanilla ItemRenderer 同款使用,无更优替代
    public void renderOutline(PoseStack pose, BakedModel model,
                              int x, int y, int quadSize, int color, float thickness) {
        if (thickness <= 0f || this.outlineShader == null) {
            return;
        }
        setOutlineAlphaBoost(1.0f);

        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        int z = 150 + (model.isGui3d() ? quadSize : 0);

        List<BakedQuad> quads = collectQuads(model);
        if (quads.isEmpty()) {
            return;
        }

        if (model.isGui3d()) {
            // 3D 物品(方块/铁砧/盾牌等):顶点法线平均外扩(见 renderGui3dInflate)。
            // 用 COLOR_WRITE(不写深度)的 RenderType,本体随后覆盖中心露出外扩环。
            VertexConsumer shellConsumer = outlineBuffers.getBuffer(handOutlineRenderType());
            renderGui3dInflate(pose, quads, x, y, z, model, shellConsumer, packedColor, thickness);
        } else {
            // 扁平物品:8 方向屏幕偏移(各方向整体平移,屏幕像素语义)
            VertexConsumer consumer = outlineBuffers.getBuffer(outlineRenderType());
            for (float[] off : OFFSETS) {
                pose.pushPose();
                try {
                    pose.translate(x + 8 + off[0] * thickness * THICKNESS_SCALE,
                            y + 8 + off[1] * thickness * THICKNESS_SCALE, z);
                    pose.scale(16.0F, -16.0F, 16.0F);
                    model.getTransforms().getTransform(ItemDisplayContext.GUI).apply(false, pose);
                    pose.translate(-0.5F, -0.5F, -0.5F);
                    Matrix4f poseMatrix = pose.last().pose();
                    for (BakedQuad quad : quads) {
                        emitQuad(quad, poseMatrix, consumer, packedColor);
                    }
                } finally {
                    pose.popPose();
                }
            }
        }
        // 立即绘制描边:保证在物品本体(由 GuiGraphics 后续 flush)之前完成。
        outlineBuffers.endBatch();
    }

    /**
     * GUI 3D 物品描边:与手持 3D 同构的<b>顶点法线平均外扩</b>(见 {@link #renderVertexNormalExpand})。
     * <p>
     * 整体缩放壳(绕包围盒中心放大)的外扩量 = (scale-1)×点到中心距离 → 离中心远的
     * 部分(立体物品的上下两端)外扩更多,视觉上轮廓向模型上方偏移、且 thickness
     * 越大越明显。顶点法线固定距离外扩则描边宽度处处相等,消除偏移。
     */
    private static void renderGui3dInflate(PoseStack pose, List<BakedQuad> quads, int x, int y, int z,
                                           BakedModel model, VertexConsumer consumer,
                                           int packedColor, float thickness) {
        pose.pushPose();
        try {
            pose.translate(x + 8, y + 8, z);
            pose.scale(16.0F, -16.0F, 16.0F);
            model.getTransforms().getTransform(ItemDisplayContext.GUI).apply(false, pose);
            pose.translate(-0.5F, -0.5F, -0.5F);
            // GUI 1 模型单位 = 16px,扁平物品屏幕偏移为 thickness×0.5px →
            // 模型空间偏移 = thickness×0.5/16,保持两种路径描边宽度一致。
            // 顶点法线外扩贴合表面、无中心偏移,与手持 3D 一致。
            renderVertexNormalExpand(quads, pose, consumer, packedColor,
                    thickness * THICKNESS_SCALE / 16.0f);
        } finally {
            pose.popPose();
        }
    }

    /** 顶点位置 key(用原始 float 位做 equals/hashCode,精确去重共享顶点)。 */
    private record Position(float x, float y, float z) {
    }

    /**
     * 取 quad 的面法线,兼容任意模组模型。
     * <p>
     * {@code BakedQuad.getDirection()} 可返回 null:vanilla 语义里 null 表示"无方向"的
     * 未着色面,部分模组手写 BakedQuad 时构造器传 null(构造器不校验)。原版
     * {@code VertexConsumer.putBulkData} 对此会 NPE,但我们的描边在物品本体<b>之前</b>
     * 执行,必须先兜底。做法:direction 为 null 时,用前 3 个顶点的叉积推导法线
     * (退化面返回零向量,该面不参与外扩,但不崩溃)。
     */
    private static Vec3i safeQuadNormal(BakedQuad quad) {
        Direction dir = quad.getDirection();
        if (dir != null) {
            return dir.getNormal();
        }
        int[] v = quad.getVertices();
        if (v.length < 24) { // 不足 3 个完整顶点,无法叉积
            return Vec3i.ZERO;
        }
        float ax = Float.intBitsToFloat(v[0]);
        float ay = Float.intBitsToFloat(v[1]);
        float az = Float.intBitsToFloat(v[2]);
        float bx = Float.intBitsToFloat(v[8]);
        float by = Float.intBitsToFloat(v[9]);
        float bz = Float.intBitsToFloat(v[10]);
        float cx = Float.intBitsToFloat(v[16]);
        float cy = Float.intBitsToFloat(v[17]);
        float cz = Float.intBitsToFloat(v[18]);
        // (b-a) × (c-a),再归一化到整数坐标 Vec3i
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float acx = cx - ax, acy = cy - ay, acz = cz - az;
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq <= 1e-6f) {
            return Vec3i.ZERO; // 退化面
        }
        float inv = 1.0f / (float) Math.sqrt(lenSq);
        return new Vec3i(Math.round(nx * inv), Math.round(ny * inv), Math.round(nz * inv));
    }

    /**
     * 顶点法线平均外扩(立体物品):每个顶点沿<b>相邻面法线平均值</b>方向移动固定距离。
     * <p>
     * 优点:描边贴合表面、厚度均匀,<b>不依赖任何几何中心</b> → 视觉重心≠几何中心、
     * 或细长物品(三叉戟 pole 两端、盾牌薄板)不会"向一端偏移"。这也是图形学中
     * 生成外扩轮廓(膨胀壳)的标准做法。
     * <p>
     * 注意:扁平物品(单层平面)顶点法线只有 ±z,外扩沿 z 不可见 → 扁平物品不走本方法,
     * 改用整体 8 方向平移(UV 不变,剑形轮廓均匀外扩)。
     */
    private static void renderVertexNormalExpand(List<BakedQuad> quads, PoseStack pose, VertexConsumer consumer,
                                                 int packedColor, float offset) {
        // 第一遍:累加每个共享顶点的相邻面法线。
        // 兼容性:direction 为 null 的 quad 用 safeQuadNormal 叉积兜底;零法线(退化面)
        // 跳过累加——零向量若参与 accumulate 后 normalize 会得 NaN(0/0),污染整件物品描边。
        Map<Position, Vector3f> normals = new HashMap<>();
        for (BakedQuad q : quads) {
            Vec3i n = safeQuadNormal(q);
            if (n.getX() == 0 && n.getY() == 0 && n.getZ() == 0) {
                continue;
            }
            int[] v = q.getVertices();
            for (int i = 0; i + 8 <= v.length; i += 8) {
                Position key = new Position(Float.intBitsToFloat(v[i]),
                        Float.intBitsToFloat(v[i + 1]), Float.intBitsToFloat(v[i + 2]));
                normals.computeIfAbsent(key, k -> new Vector3f()).add(n.getX(), n.getY(), n.getZ());
            }
        }
        for (Vector3f n : normals.values()) {
            if (n.lengthSquared() > 1e-6f) {
                n.normalize();
            }
        }

        // 第二遍:顶点沿平均法线外扩 offset 后输出
        Matrix4f poseMatrix = pose.last().pose();
        for (BakedQuad q : quads) {
            Vec3i n = safeQuadNormal(q);
            int[] v = q.getVertices();
            for (int i = 0; i + 8 <= v.length; i += 8) {
                float x = Float.intBitsToFloat(v[i]);
                float y = Float.intBitsToFloat(v[i + 1]);
                float z = Float.intBitsToFloat(v[i + 2]);
                float u = Float.intBitsToFloat(v[i + 4]);
                float vv = Float.intBitsToFloat(v[i + 5]);
                Vector3f dir = normals.get(new Position(x, y, z));
                if (dir == null) {
                    dir = new Vector3f(); // 防御:顶点未参与第一遍(极端 float 位不一致),不外扩
                }
                float ex = dir.x() * offset;
                float ey = dir.y() * offset;
                float ez = dir.z() * offset;
                Vector3f p = poseMatrix.transformPosition(x + ex, y + ey, z + ez, new Vector3f());
                consumer.addVertex(p.x(), p.y(), p.z(), packedColor, u, vv,
                        OverlayTexture.NO_OVERLAY, FULL_BRIGHT, n.getX(), n.getY(), n.getZ());
            }
        }
    }

    /**
     * 手持描边(第一/第三人称世界渲染)。
     * <p>
     * 3D 世界物品不能像 GUI 那样做屏幕像素偏移,改用壳以描边色渲染、
     * <b>不写深度</b>(COLOR_WRITE),物品本体随后覆盖中心,露出外扩环:
     * <ul>
     *   <li>3D 物品(三叉戟/盾牌/方块等):顶点法线平均外扩 —— 每个顶点沿相邻面
     *       法线平均值方向移动固定距离,描边贴合表面、厚度均匀。不依赖几何中心,
     *       细长物品(三叉戟 pole)两端对称延伸,不会"向一端偏移"。</li>
     *   <li>扁平物品(剑/工具等):整体平面内 8 方向平移,UV 不变 → 剑形轮廓
     *       均匀外扩、边缘贴合。移动顶点会拉伸 UV → 外扩量 = (scale-1)×到中心
     *       距离,剑身中部离中心近、外扩少,剑头离中心远、外扩多 → 轮廓裂开;
     *       整体平移则每个方向外扩量恒定,与 GUI 扁平物品的屏幕偏移同思路。</li>
     * </ul>
     * 调用方需已完成 display transform 与 translate(-0.5) 居中。
     *
     * @param model     物品 BakedModel(手持变体已由调用方解析)
     * @param pose      已居中(translate(-0.5,-0.5,-0.5))的 PoseStack
     * @param color     ARGB 描边颜色
     * @param thickness 描边厚度(像素语义,可浮点)
     */
    public void renderHandOutline(BakedModel model, PoseStack pose, int color, float thickness) {
        // 手持描边发光半透明:boost=1.0(物品内部≈1、边缘渐变更淡),配合全亮光照呈
        // 半透明发光效果;不再抬升到 2.0(那会让第一人称近距物品的描边完全实心不透明)。
        renderHandOutline(model, pose, color, thickness, 1.0f, false);
    }

    /**
     * 手持描边,可指定 {@link #setOutlineAlphaBoost(float) OutlineAlphaBoost}(半透明)。
     *
     * @param boost OutlineAlphaBoost(手持 1.0 半透明;部分背景路径 2.0)
     */
    public void renderHandOutline(BakedModel model, PoseStack pose, int color, float thickness, float boost) {
        renderHandOutline(model, pose, color, thickness, boost, false);
    }

    /**
     * 手持描边,可指定 alpha 强化与<b>是否硬切不透明</b>。
     * <p>
     * 偏移系数不变,仅着色器 alpha 处理不同:
     * <ul>
     *   <li>半透明(opaque=false):boost 抬升贴图边缘渐变 alpha,仍带混合 —— 第一人称
     *       近距手持保持发光效果;</li>
     *   <li>不透明(opaque=true):fsh 把非透明像素硬切为 alpha=1.0 纯色,轮廓完全不透明。
     *       物品展示框(FIXED)等<b>背景物体</b>贴墙/距玩家远,半透明描边在明亮场景中
     *       几乎不可见,必须不透明才能看清。</li>
     * </ul>
     *
     * @param boost  OutlineAlphaBoost(半透明 1.0;不透明 2.0 确保内部像素切满)
     * @param opaque true = 硬切不透明(物品展示框),false = 半透明(手持/掉落物)
     */
    public void renderHandOutline(BakedModel model, PoseStack pose, int color, float thickness,
                                  float boost, boolean opaque) {
        if (thickness <= 0f || this.outlineShader == null) {
            return;
        }
        setOutlineAlphaBoost(boost);
        setOutlineCutout(opaque);
        List<BakedQuad> quads = collectQuads(model);
        if (quads.isEmpty()) {
            return;
        }
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);

        // 光影兼容:自定义 shader 在"光影激活 + 不允许未知 shader"时被 Iris 跳过绘制
        // (透明),必须改用内置 emissive shader 的 RenderType。光影未激活或玩家已开启
        // allowUnknownShaders 时,继续用自定义描边 shader(alpha boost / cutout 全保留)。
        //   - 扁平物品(剑/弓/工具)开混色:BLOCK_SHEET 保留物品贴图 alpha 遮罩(形状),
        //     颜色 = 描边色 × 物品贴图像素色(混合,见 Config.ITEM_PIXEL_COLOR_GLINT);
        //   - 扁平物品关混色:走 renderFlatPureColorShape —— CPU 读取贴图 alpha 生成
        //     "描边色形状纹理",纯描边色 + 物品形状(不再矩形);
        //   - 3D 物品(方块等)开混色:BLOCK_SHEET(方块贴图颜色 × 描边色,形状由几何外扩);
        //   - 3D 物品关混色:纯白纹理(纯描边色,形状仍由几何外扩决定,不退化);
        //   - 盾牌/三叉戟近似盒模型:全部 quads 用 stone 精灵,采样 BLOCK_SHEET 会被
        //     Iris 误判为方块材质(反射方块纹理)且 stone 灰把描边色染暗 → 恒纯白纯色。
        // 展示框(FIXED)在光影 fallback 下也统一半透明(内置 emissive),不再硬切不透明。
        if (needVanillaShaderFallback() && !model.isGui3d() && !Config.ITEM_PIXEL_COLOR_GLINT.get()) {
            renderFlatPureColorShape(quads, pose, packedColor, thickness);
            outlineBuffers.endBatch();
            return;
        }
        RenderType renderType;
        if (needVanillaShaderFallback()) {
            if (model.isGui3d()) {
                // 盾牌/三叉戟盒模型恒纯白;真实 3D 物品按混色开关选择 BLOCK_SHEET / 纯白。
                renderType = usesOnlyStoneSprite(quads)
                        ? worldEmissiveOutlineRenderType(WHITE_TEXTURE)
                        : (Config.ITEM_PIXEL_COLOR_GLINT.get()
                                ? worldEmissiveOutlineRenderType()
                                : worldEmissiveOutlineRenderType(WHITE_TEXTURE));
            } else {
                // 扁平物品:开混色 → BLOCK_SHEET(关混色已在上方分支处理)
                renderType = worldEmissiveOutlineRenderType();
            }
        } else {
            renderType = handOutlineRenderType();
        }
        VertexConsumer consumer = outlineBuffers.getBuffer(renderType);
        float offset = thickness * THICKNESS_SCALE * HAND_INFLATE_PER_THICKNESS;
        if (model.isGui3d()) {
            // 立体物品(三叉戟/盾牌/方块等):顶点法线平均外扩 —— 每个顶点沿相邻面
            // 法线平均值方向移动固定距离,描边贴合表面、厚度均匀。不依赖几何中心,
            // 细长物品(三叉戟 pole)两端对称延伸,不再"向一端偏移"。
            renderVertexNormalExpand(quads, pose, consumer, packedColor, offset);
        } else {
            // 扁平物品(剑/工具等):整体平面内 8 方向平移,UV 不变 → 剑形轮廓
            // 均匀外扩、边缘贴合。移动顶点会拉伸 UV → 外扩量 = (scale-1)×到中心
            // 距离,剑身中部离中心近、外扩少,剑头离中心远、外扩多 → 轮廓裂开。
            // 整体平移则每个方向外扩量恒定,与 GUI 扁平物品的屏幕偏移同思路。
            float t = thickness * THICKNESS_SCALE / 16.0f;
            for (float[] off : OFFSETS) {
                pose.pushPose();
                try {
                    pose.translate(off[0] * t, off[1] * t, 0.0f);
                    Matrix4f m = pose.last().pose();
                    for (BakedQuad quad : quads) {
                        emitQuad(quad, m, consumer, packedColor);
                    }
                } finally {
                    pose.popPose();
                }
            }
        }
        outlineBuffers.endBatch();
    }

    /**
     * 收集模型全部 quads(与 ItemRenderer.renderModelLists 完全相同的遍历方式)。
     * <p>
     * 兼容性:部分模组手写 BakedModel 的 {@code getQuads} 在个别方向可能返回 null
     * (vanilla 契约是 List 非空,但第三方实现不可靠),这里跳过 null 防止 NPE。
     */
    private static List<BakedQuad> collectQuads(BakedModel model) {
        List<BakedQuad> quads = new ArrayList<>();
        RandomSource random = RandomSource.create();
        for (Direction direction : Direction.values()) {
            random.setSeed(42L);
            List<BakedQuad> list = model.getQuads(null, direction, random);
            if (list != null) {
                quads.addAll(list);
            }
        }
        random.setSeed(42L);
        List<BakedQuad> list = model.getQuads(null, null, random);
        if (list != null) {
            quads.addAll(list);
        }
        return quads;
    }

    /**
     * 判断模型是否全部 quad 都使用 {@code minecraft:block/stone} 精灵 ——
     * 即 {@link #shieldModel} / {@link #tridentModel} 自建的近似盒模型。
     * <p>
     * 盒模型 UV 采样 stone(全不透明,只为 alpha 遮罩);若在光影 fallback 下让其
     * 采样 BLOCK_SHEET,会被 Iris 当作方块材质反查(盾/三叉戟"反射四周方块纹理")
     * 且 stone 灰会把描边色染暗 → 必须恒用纯白纹理。
     *
     * @param quads 收集到的模型 quads
     * @return true = 全部 stone,视为盒模型
     */
    private static boolean usesOnlyStoneSprite(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return false;
        }
        for (BakedQuad q : quads) {
            TextureAtlasSprite s = q.getSprite();
            if (s == null || !s.contents().name().equals(ResourceLocation.withDefaultNamespace("block/stone"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单个 quad:顶点经 pose 矩阵变换到 GUI 像素空间,以纯描边色写入。
     * 顶点数据布局(i 步进 8):x, y, z / 打包颜色 / u, v / 未用。
     * 法线取 quad 方向(GUI 下着色器不使用,仅补全格式)。
     */
    private static void emitQuad(BakedQuad quad, Matrix4f poseMatrix, VertexConsumer consumer, int packedColor) {
        int[] vertices = quad.getVertices();
        Vec3i normal = safeQuadNormal(quad);
        for (int i = 0; i + 8 <= vertices.length; i += 8) {
            float x = Float.intBitsToFloat(vertices[i]);
            float y = Float.intBitsToFloat(vertices[i + 1]);
            float z = Float.intBitsToFloat(vertices[i + 2]);
            float u = Float.intBitsToFloat(vertices[i + 4]);
            float v = Float.intBitsToFloat(vertices[i + 5]);
            Vector3f p = poseMatrix.transformPosition(x, y, z, new Vector3f());
            consumer.addVertex(p.x(), p.y(), p.z(), packedColor, u, v,
                    OverlayTexture.NO_OVERLAY, FULL_BRIGHT,
                    normal.getX(), normal.getY(), normal.getZ());
        }
    }

    /**
     * 扁平物品"纯描边色 + 保留物品形状"渲染(光影兼容下关闭混色)。
     * <p>
     * 内置 emissive fsh 是 {@code texel × vertexColor},单纹理采样无法分离"形状(alpha)"
     * 与"颜色(RGB)"。解法:CPU 读取物品贴图原图(NativeImage,内存像素非 GPU 回读),
     * 生成一张 RGB=描边色、A=原 alpha 的"描边色形状纹理"(见
     * {@link #shapeTextureFor(TextureAtlasSprite, int)}),再按 8 方向平移渲染 →
     * 输出 = 纯描边色 × 贴图 alpha = 纯色物品轮廓,形状与开启混色时一致。
     * <p>
     * 顶点 UV 需从 atlas 绝对坐标重映射为 sprite 相对坐标(独立纹理 0..1)。
     * 顶点颜色传白色(描边色已烘焙进纹理)。
     *
     * @param quads     收集到的模型 quads
     * @param pose      已居中(translate(-0.5))的 PoseStack
     * @param color     ARGB 描边色
     * @param thickness 描边厚度(像素语义)
     */
    private void renderFlatPureColorShape(List<BakedQuad> quads, PoseStack pose, int color, float thickness) {
        float t = thickness * THICKNESS_SCALE / 16.0f;
        // 按 sprite 分组:同一贴图的 quad 共用一张形状纹理(同一 RenderType 一次绑定)
        Map<TextureAtlasSprite, List<BakedQuad>> bySprite = new LinkedHashMap<>();
        for (BakedQuad q : quads) {
            TextureAtlasSprite sprite = q.getSprite();
            if (sprite == null) {
                continue;
            }
            bySprite.computeIfAbsent(sprite, k -> new ArrayList<>()).add(q);
        }
        for (Map.Entry<TextureAtlasSprite, List<BakedQuad>> entry : bySprite.entrySet()) {
            TextureAtlasSprite sprite = entry.getKey();
            ResourceLocation tex = shapeTextureFor(sprite, color);
            VertexConsumer consumer = outlineBuffers.getBuffer(worldEmissiveOutlineRenderType(tex));
            for (float[] off : OFFSETS) {
                pose.pushPose();
                try {
                    pose.translate(off[0] * t, off[1] * t, 0.0f);
                    Matrix4f m = pose.last().pose();
                    for (BakedQuad quad : entry.getValue()) {
                        emitQuadShape(sprite, quad, m, consumer);
                    }
                } finally {
                    pose.popPose();
                }
            }
        }
    }

    /**
     * 生成(或取缓存)"描边色形状纹理":与源贴图同尺寸,RGBA 中 RGB=描边色、
     * A=源贴图 alpha。注册到 TextureManager,之后 RenderType 可直接按 location 采样。
     * <p>
     * <b>统一入口</b>:扁平物品(atlas sprite)与盔甲/鞘翅/投掷物(独立纹理)共用
     * 同一套生成逻辑 —— 无论混色开关如何,描边形状始终贴合源贴图的 alpha 遮罩,
     * 只是"颜色来源"不同(混色开 = 纹理 RGB × 描边色;混色关 = 纯描边色)。
     * <p>
     * 缓存键 = (源贴图 location, 描边色);资源重载时 TextureManager 销毁纹理,缓存由
     * {@link #setOutlineShader} 清空,按需重建。
     *
     * @param source 源贴图 location(仅作缓存键与纹理命名)
     * @param src    源贴图像素(读取方负责获取与关闭)
     * @param color  ARGB 描边色
     * @return 已注册的纹理 location
     */
    private ResourceLocation shapeTexture(ResourceLocation source, NativeImage src, int color) {
        int rgb = color & 0xFFFFFF;
        ShapeKey key = new ShapeKey(source, rgb);
        ResourceLocation loc = this.shapeTextures.get(key);
        if (loc == null) {
            int w = src.getWidth(), h = src.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // NativeImage RGBA 内存小端 → getPixelRGBA 返回 ABGR 打包,alpha 在最高字节
                    int a = (src.getPixelRGBA(x, y) >>> 24) & 0xFF;
                    img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            loc = ResourceLocation.fromNamespaceAndPath("enchanted_outlines",
                    "shape/" + source.getNamespace() + "/"
                            + source.getPath().replace('/', '_') + "_"
                            + Integer.toHexString(rgb));
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            this.shapeTextures.put(key, loc);
        }
        return loc;
    }

    /**
     * 扁平物品版形状纹理:从 atlas 精灵取原图像素后走统一入口 {@link #shapeTexture}。
     *
     * @param sprite 物品贴图(atlas 精灵)
     * @param color  ARGB 描边色
     * @return 已注册的纹理 location;拿不到像素时回退纯白矩形
     */
    private ResourceLocation shapeTextureFor(TextureAtlasSprite sprite, int color) {
        NativeImage src = spriteOriginalImage(sprite);
        if (src == null) {
            // 拿不到贴图像素(反射失败等):回退纯白矩形,保证描边仍可见
            return WHITE_TEXTURE;
        }
        return shapeTexture(sprite.contents().name(), src, color);
    }

    /**
     * 独立纹理(盔甲/鞘翅/投掷物实体)版形状纹理:从 ResourceManager 读取纹理 PNG,
     * 走统一入口 {@link #shapeTexture}。
     *
     * @param texture 独立纹理 location(如 armor / elytra / trident 材质)
     * @param color   ARGB 描边色
     * @return 已注册的纹理 location;读取失败时回退纯白矩形
     */
    private ResourceLocation shapeTextureForLocation(ResourceLocation texture, int color) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return WHITE_TEXTURE;
            }
            try (InputStream in = resource.get().open();
                 NativeImage src = NativeImage.read(in)) {
                return shapeTexture(texture, src, color);
            }
        } catch (Exception ignored) {
            return WHITE_TEXTURE;
        }
    }

    /**
     * 读取贴图精灵的原始图像(NativeImage,CPU 内存像素,非 GPU 回读)。
     * <p>
     * 1.21.1 的 {@code SpriteContents.originalImage} 是私有字段且无公开 getter,
     * 用反射读取(mojmap 字段名运行期稳定;失败返回 null 由调用方回退)。
     */
    private static NativeImage spriteOriginalImage(TextureAtlasSprite sprite) {
        try {
            java.lang.reflect.Field field = sprite.contents().getClass().getDeclaredField("originalImage");
            field.setAccessible(true);
            return (NativeImage) field.get(sprite.contents());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 单个 quad 以"形状纹理"模式写入:UV 从 atlas 绝对坐标重映射为该 sprite 的相对坐标
     * (0..1,独立纹理全图),顶点颜色传白色(描边色已烘焙进纹理)。
     */
    private static void emitQuadShape(TextureAtlasSprite sprite, BakedQuad quad, Matrix4f poseMatrix,
                                      VertexConsumer consumer) {
        int[] vertices = quad.getVertices();
        Vec3i normal = safeQuadNormal(quad);
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        float uw = u1 - u0, vh = v1 - v0;
        for (int i = 0; i + 8 <= vertices.length; i += 8) {
            float x = Float.intBitsToFloat(vertices[i]);
            float y = Float.intBitsToFloat(vertices[i + 1]);
            float z = Float.intBitsToFloat(vertices[i + 2]);
            float u = Float.intBitsToFloat(vertices[i + 4]);
            float v = Float.intBitsToFloat(vertices[i + 5]);
            float ru = uw > 1e-6f ? (u - u0) / uw : 0.0f;
            float rv = vh > 1e-6f ? (v - v0) / vh : 0.0f;
            Vector3f p = poseMatrix.transformPosition(x, y, z, new Vector3f());
            consumer.addVertex(p.x(), p.y(), p.z(), 0xFFFFFFFF, ru, rv,
                    OverlayTexture.NO_OVERLAY, FULL_BRIGHT,
                    normal.getX(), normal.getY(), normal.getZ());
        }
    }

    /**
     * 自定义 RenderType:alpha 遮罩纯色描边着色器 + 物品图集 + 半透明 + 与 GUI 一致的深度。
     * 着色器状态用 {@link java.util.function.Supplier} 引用 → 资源重载后自动使用新实例,
     * 无需重建 RenderType。
     * <p>
     * <b>只写颜色不写深度</b>(COLOR_WRITE):描边画的是整张 16×16 平面 quad,而 GL 深度写入
     * 与 alpha 无关——即使描边着色器输出 alpha=0 的透明像素(物品贴图透明区域),深度仍会
     * 写入。原版附魔光效(glint)用 EQUAL_DEPTH_TEST 只在<b>本体写入深度</b>的像素上显示;
     * 描边把物品周围的格子背景区域深度也写掉后,glint 会在整个槽位通过深度测试 →
     * 出现"格子大小的原版附魔光效闪动"。本体随后绘制(LEQUAL,相等通过)覆盖中心并重写
     * 本体区域深度,描边无需也不应写深度。与 GUI 3D 路径(handOutlineRenderType)一致。
     */
    private RenderType outlineRenderType() {
        RenderType type = this.outlineRenderType;
        if (type == null) {
            type = RenderType.create("enchanted_outlines_outline",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> this.outlineShader))
                            .setTextureState(RenderStateShard.BLOCK_SHEET)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                            .createCompositeState(false));
            this.outlineRenderType = type;
        }
        return type;
    }

    /**
     * 手持描边专用 RenderType:与 GUI 版同着色器/图集/半透明,但<b>只写颜色不写深度</b>
     * (壳先画,本体随后覆盖中心;若壳写深度会挡住本体)。
     */
    private RenderType handOutlineRenderType() {
        RenderType type = this.handOutlineRenderType;
        if (type == null) {
            type = RenderType.create("enchanted_outlines_hand_outline",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> this.outlineShader))
                            .setTextureState(RenderStateShard.BLOCK_SHEET)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                            .createCompositeState(false));
            this.handOutlineRenderType = type;
        }
        return type;
    }

    /**
     * 光影兼容:世界渲染半透明发光描边 RenderType(BLOCK_SHEET,扁平物品专用)。
     * <p>
     * <b>为什么需要内置 shader:</b>Iris 默认 {@code allowUnknownShaders=false} 且光影激活时,
     * 自定义 core shader(如 {@code enchanted_outlines:outline})的绘制被 Iris 直接跳过
     * (见 {@link #needVanillaShaderFallback()})→ 描边透明。改用内置
     * {@code entity_translucent_emissive} shader 后,Iris 有完善 gbuffer 映射
     * (ENTITY_TRANSLUCENT_EMISSIVE),光影可正确渲染描边。
     * <p>
     * <b>为什么 emissive:</b>内置 {@code entity_translucent} 会乘 lightmap,Iris 的
     * gbuffer 阶段会重写实体光照 → 描边在暗处变暗、失去"发光"感;emissive 变体
     * 不乘 lightmap、RGB 全亮 → 描边始终是鲜艳的纯色(发光效果)。
     * <p>
     * <b>扁平物品为何仍用 BLOCK_SHEET:</b>剑/工具等扁平物品是整张 16×16 平面 quad,
     * 形状全靠物品贴图的 alpha 遮罩(8 方向平移裁出轮廓)。改用纯白纹理会变成实心
     * 方块。代价是内置 fsh 的 {@code texel × vertexColor} 会用物品贴图 RGB 染一遍
     * 描边色(如铁剑灰 × 金色 ≈ 暗金)——这是"单纹理采样无法分离 alpha 与 RGB"的
     * 硬限制;emissive 全亮下偏色比普通版轻。若开启 Iris 的 allowUnknownShaders,
     * 自动回退自定义 shader,颜色完全准确。
     */
    private RenderType worldEmissiveOutlineRenderType() {
        RenderType type = this.worldOutlineRenderTypes.get(TextureAtlas.LOCATION_BLOCKS);
        if (type == null) {
            type = RenderType.create("enchanted_outlines_world_outline",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                            .setTextureState(RenderStateShard.BLOCK_SHEET)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setOverlayState(RenderStateShard.OVERLAY)
                            .createCompositeState(false));
            this.worldOutlineRenderTypes.put(TextureAtlas.LOCATION_BLOCKS, type);
        }
        return type;
    }

    /**
     * 光影兼容:世界渲染半透明发光描边 RenderType(独立纹理)。
     * <p>
     * 供<b>非方块图集的独立纹理路径</b>使用(盔甲/鞘翅/三叉戟投掷物实体的原纹理,
     * 或 {@link #WHITE_TEXTURE} 纯白):独立纹理不触发 Iris 的方块材质反查,
     * 消除"盾/三叉戟反射四周方块纹理"的 gbuffer 污染;emissive 不乘 lightmap →
     * 描边全亮发光。
     * <p>
     * 用原纹理(盔甲等):形状由纹理 alpha 遮罩贴合(与无光影算法相同),颜色 =
     * 纹理 RGB × 描边色(内置 fsh 的 {@code texel × vertexColor},混合不可避免,
     * 但盔甲纹理多为中性色,混合后偏色轻)。用纯白纹理:输出纯描边色(形状由几何决定)。
     *
     * @param texture 采样纹理(盔甲基础材质 / elytra / trident 实体纹理 / 纯白)
     */
    private RenderType worldEmissiveOutlineRenderType(ResourceLocation texture) {
        RenderType type = this.worldOutlineRenderTypes.get(texture);
        if (type == null) {
            type = RenderType.create("enchanted_outlines_world_entity_outline",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setOverlayState(RenderStateShard.OVERLAY)
                            .createCompositeState(false));
            this.worldOutlineRenderTypes.put(texture, type);
        }
        return type;
    }

    /**
     * 盾牌近似描边模型。
     * <p>
     * 盾牌是 BEWLR 物品:占位模型(builtin/entity)无几何、本体由 BEWLR 程序化渲染。
     * <b>关键坐标系推导</b>(GUI/手持同构):
     * <pre>本体 = T(display) · T(-0.5) · S(1,-1,-1) · plate局部      (BEWLR 内 scale(1,-1,-1))
     * 描边 = T(display) · T(-0.5) · Q                              (描边路径应用 display transform + translate(-0.5))
     * 要重合 → Q = S(1,-1,-1) · plate</pre>
     * 所以盒顶点直接用 plate 局部坐标经 Y/Z 翻转:<b>不能 +0.5</b>——+0.5 在 T(-0.5) 的另一侧,
     * 会留下 T(display)·0 = display 平移量(如 GUI translation [2,3,0] ×16 → 32px/48px)的固定偏移。
     * <p>
     * plate:addBox(-6,-11,-2,12,22,1) → x -0.375..0.375、y -0.6875..0.6875、z -0.125..-0.0625;
     * S(1,-1,-1) 翻转后 x -0.375..0.375、y -0.6875..0.6875、z 0.0625..0.125。
     * <b>transforms 必须取当前解析出的盾牌模型的 display</b>(不能固定用普通盾牌):
     * 盾牌有 blocking override → blocking 时 resolve 到 shield_blocking,display 不同
     * (如 firstperson trans 普通 [-10,2,-10] vs blocking [-15,5,-11]),否则举盾错位。
     * 调用方把 {@code ItemRenderer.render} / GUI 已解析的 model.getTransforms() 传进来。
     * UV 取 block atlas 的 stone(全不透明,只为 alpha 遮罩)。
     */
    public BakedModel shieldModel(ItemTransforms transforms) {
        BakedModel model = this.shieldModel;
        if (model == null || this.shieldTransforms != transforms) {
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.withDefaultNamespace("block/stone"));
            List<BakedQuad> quads = new ArrayList<>();
            // S(1,-1,-1)·plate 局部坐标,不加 0.5(见方法注释推导)
            addBox(quads, sprite, -0.375f, -0.6875f, 0.0625f, 0.375f, 0.6875f, 0.125f);
            // 所有方向给空列表:SimpleBakedModel 对非 null direction 返回 culledFaces.get(dir),
            // 用 Map.of() 会返回 null → collectQuads 的 addAll(null) 崩溃
            Map<Direction, List<BakedQuad>> emptyByDir = Map.of(
                    Direction.NORTH, List.of(), Direction.SOUTH, List.of(),
                    Direction.EAST, List.of(), Direction.WEST, List.of(),
                    Direction.UP, List.of(), Direction.DOWN, List.of());
            // isGui3d=true:GUI 下盾牌本体是 3D 旋转薄板,走 renderGui3dInflate 的
            // "绕包围盒中心均匀放大壳"(与手持同一算法),对旋转薄板得到完整轮廓。
            model = new SimpleBakedModel(quads, emptyByDir,
                    false, false, true, sprite, transforms, ItemOverrides.EMPTY);
            this.shieldModel = model;
            this.shieldTransforms = transforms;
        }
        return model;
    }

    /**
     * 三叉戟手持/投掷描边模型。
     * <p>
     * 三叉戟在手持时是 BEWLR 物品(trident_in_hand/trident_throwing 都是 builtin/entity
     * 占位,无几何),本体由 BEWLR 渲染 TridentModel(LayerDefinition 实体模型):
     * <pre>本体 = T(display) · T(-0.5) · S(1,-1,-1) · tridentLocal</pre>
     * 描边盒几何 = S(1,-1,-1) · tridentLocal,顶点坐标经 Y/Z 翻转。
     * <p>
     * TridentModel.createLayer 几何(像素单位,root 无偏移):
     * pole(-0.5,2,-0.5, 1,25,1)、base(-1.5,0,-0.5, 3,2,1)、
     * left_spike(-2.5,-3,-0.5, 1,4,1)、middle_spike(-0.5,-4,-0.5, 1,4,1)、
     * right_spike(1.5,-3,-0.5, 1,4,1)。S(1,-1,-1) 翻转后 y→-y、z→-z(z 对称不变)。
     * <b>transforms 必须取当前解析的占位模型</b>(throwing 与普通 display 不同),
     * 否则投掷时描边与本体错位。
     */
    public BakedModel tridentModel(ItemTransforms transforms) {
        BakedModel model = this.tridentModel;
        if (model == null || this.tridentTransforms != transforms) {
            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.withDefaultNamespace("block/stone"));
            List<BakedQuad> quads = new ArrayList<>();
            float f = 1.0f / 16.0f;
            // pole: y 2..27 → -27..-2
            addBox(quads, sprite, -0.5f * f, -27f * f, -0.5f * f, 0.5f * f, -2f * f, 0.5f * f);
            // base: y 0..2 → -2..0
            addBox(quads, sprite, -1.5f * f, -2f * f, -0.5f * f, 1.5f * f, 0f, 0.5f * f);
            // left_spike: y -3..1 → -1..3
            addBox(quads, sprite, -2.5f * f, -1f * f, -0.5f * f, -1.5f * f, 3f * f, 0.5f * f);
            // middle_spike: y -4..0 → 0..4
            addBox(quads, sprite, -0.5f * f, 0f, -0.5f * f, 0.5f * f, 4f * f, 0.5f * f);
            // right_spike: y -3..1 → -1..3
            addBox(quads, sprite, 1.5f * f, -1f * f, -0.5f * f, 2.5f * f, 3f * f, 0.5f * f);
            Map<Direction, List<BakedQuad>> emptyByDir = Map.of(
                    Direction.NORTH, List.of(), Direction.SOUTH, List.of(),
                    Direction.EAST, List.of(), Direction.WEST, List.of(),
                    Direction.UP, List.of(), Direction.DOWN, List.of());
            model = new SimpleBakedModel(quads, emptyByDir,
                    false, false, true, sprite, transforms, ItemOverrides.EMPTY);
            this.tridentModel = model;
            this.tridentTransforms = transforms;
        }
        return model;
    }

    /** 构造一个轴对齐盒的 6 个面 quad(全部进 unculled 列表)。 */
    private static void addBox(List<BakedQuad> out, TextureAtlasSprite sprite,
                               float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        addFace(out, sprite, Direction.SOUTH, u0, v0, u1, v1, minX, minY, maxZ, maxX, maxY, maxZ);
        addFace(out, sprite, Direction.NORTH, u0, v0, u1, v1, minX, minY, minZ, maxX, maxY, minZ);
        addFace(out, sprite, Direction.UP, u0, v0, u1, v1, minX, maxY, minZ, maxX, maxY, maxZ);
        addFace(out, sprite, Direction.DOWN, u0, v0, u1, v1, minX, minY, minZ, maxX, minY, maxZ);
        addFace(out, sprite, Direction.EAST, u0, v0, u1, v1, maxX, minY, minZ, maxX, maxY, maxZ);
        addFace(out, sprite, Direction.WEST, u0, v0, u1, v1, minX, minY, minZ, minX, maxY, maxZ);
    }

    /** 构造单个面 quad(逆时针、面向外部),顶点布局 [x,y,z,bgra,u,v,0,0]。 */
    private static void addFace(List<BakedQuad> out, TextureAtlasSprite sprite, Direction dir,
                                float u0, float v0, float u1, float v1,
                                float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float[][] p = switch (dir) {
            case SOUTH -> new float[][]{{minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}};
            case NORTH -> new float[][]{{minX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, minY, minZ}};
            case UP -> new float[][]{{minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}};
            case DOWN -> new float[][]{{minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}, {minX, minY, minZ}};
            case EAST -> new float[][]{{maxX, minY, minZ}, {maxX, minY, maxZ}, {maxX, maxY, maxZ}, {maxX, maxY, minZ}};
            default -> new float[][]{{minX, minY, maxZ}, {minX, minY, minZ}, {minX, maxY, minZ}, {minX, maxY, maxZ}}; // WEST
        };
        float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        int[] verts = new int[32];
        for (int i = 0; i < 4; i++) {
            int o = i * 8;
            verts[o] = Float.floatToRawIntBits(p[i][0]);
            verts[o + 1] = Float.floatToRawIntBits(p[i][1]);
            verts[o + 2] = Float.floatToRawIntBits(p[i][2]);
            verts[o + 3] = 0xFFFFFFFF;
            verts[o + 4] = Float.floatToRawIntBits(uv[i][0]);
            verts[o + 5] = Float.floatToRawIntBits(uv[i][1]);
        }
        out.add(new BakedQuad(verts, 0, dir, sprite, false));
    }

    /**
     * 盔甲穿戴描边(穿在玩家身上的盔甲部件)。
     * <p>
     * 由 {@code HumanoidArmorLayer} 的 renderArmorPiece 注入调用。用<b>逐部件绕各自
     * 包围盒中心放大壳</b>渲染描边色,本体随后覆盖,露出外扩环:每个 3D 盒子
     * (头/身/臂/腿)独立膨胀,位移 = (scale-1)×盒半对角线 → 各部件描边均匀;
     * 整体绕人形中心放大会让头/手/脚等离中心远的部件描边异常粗、躯干处薄。
     * 材质用该部件的基础 armor 材质(独立纹理,非 atlas)→ 描边着色器采样其 alpha。
     *
     * @param pose      实体渲染的 PoseStack(已含实体变换)
     * @param model     该槽位的 HumanoidModel(inner 或 outer)
     * @param texture   盔甲基础材质(layer.texture)
     * @param color     ARGB 描边颜色
     * @param thickness 描边厚度(像素语义,可浮点)
     * @param slot      盔甲槽位(决定渲染哪些部件)
     */
    public void renderArmorOutline(PoseStack pose, HumanoidModel<?> model, ResourceLocation texture,
                                   int color, float thickness, EquipmentSlot slot) {
        if (thickness <= 0f || this.outlineShader == null) {
            return;
        }
        // 盔甲在场景背景下,描边需实心
        setOutlineAlphaBoost(2.0f);
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);

        // 复刻 HumanoidArmorLayer.setPartVisibility:只渲染该槽位部件
        model.setAllVisible(false);
        switch (slot) {
            case HEAD:
                model.head.visible = true;
                model.hat.visible = true;
                break;
            case CHEST:
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
                break;
            case LEGS:
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
                break;
            case FEET:
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
                break;
        }

        float scale = 1.0f + thickness * THICKNESS_SCALE * ARMOR_INFLATE_PER_THICKNESS;
        VertexConsumer consumer = outlineBuffers.getBuffer(armorOutlineRenderType(texture, color));
        // 逐部件绕各自包围盒中心放大:每个 3D 盒子(头/身/臂/腿)独立膨胀,
        // 位移 = (scale-1)×盒半对角线 → 各部件描边均匀。整体绕人形中心放大会让
        // 头/手/脚等离中心远的部件描边异常粗、躯干处薄,且整块实心壳臃肿。
        // 用 part.visit 遍历:自动处理部件间的父子变换(如 head→hat)。
        pose.pushPose();
        try {
            renderPartInflated(pose, model.head, consumer, packedColor, scale);
            renderPartInflated(pose, model.hat, consumer, packedColor, scale);
            renderPartInflated(pose, model.body, consumer, packedColor, scale);
            renderPartInflated(pose, model.rightArm, consumer, packedColor, scale);
            renderPartInflated(pose, model.leftArm, consumer, packedColor, scale);
            renderPartInflated(pose, model.rightLeg, consumer, packedColor, scale);
            renderPartInflated(pose, model.leftLeg, consumer, packedColor, scale);
        } finally {
            pose.popPose();
        }
        outlineBuffers.endBatch();
    }

    /**
     * 对单个盔甲部件渲染描边壳:遍历该部件的全部 cube,每个 cube 绕<b>自身包围盒中心</b>
     * 均匀放大。这样描边贴合每个 3D 盒子的形状,单层纹理盔甲各部件轮廓清晰。
     * <p>
     * {@code part.visit} 会应用部件自身的 translateAndRotate 并递归子部件(如 head→hat),
     * 回调里拿到的是已含完整变换的 Pose。对每个 cube:把 pose 矩阵右乘
     * T(c)·S·T(-c)(c 为该 cube 包围盒中心,局部单位),再编译,随后还原矩阵。
     */
    private static void renderPartInflated(PoseStack pose, ModelPart part, VertexConsumer consumer,
                                           int packedColor, float scale) {
        if (part == null || !part.visible) {
            return;
        }
        part.visit(pose, (p, path, index, cube) -> {
            // cube 包围盒中心(像素单位 → 模型单位:像素/16)
            float cx = (cube.minX + cube.maxX) / 2.0f / 16.0f;
            float cy = (cube.minY + cube.maxY) / 2.0f / 16.0f;
            float cz = (cube.minZ + cube.maxZ) / 2.0f / 16.0f;
            Matrix4f original = new Matrix4f(p.pose());
            p.pose().mul(new Matrix4f()
                    .translation(cx, cy, cz)
                    .scale(scale)
                    .translate(-cx, -cy, -cz));
            try {
                cube.compile(p, consumer, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, packedColor);
            } finally {
                p.pose().set(original);
            }
        });
    }

    /**
     * 三叉戟投掷物实体描边(ThrownTrident,由 {@code ThrownTridentRenderer} 直接渲染
     * TridentModel 实体模型,不经 ItemRenderer)。
     * <p>
     * 与盔甲同一套"逐 cube 绕自身包围盒中心放大壳"算法:调用方已复刻 render 的
     * Y/Z 旋转,pose 与世界对齐。材质用三叉戟实体纹理(独立纹理非 atlas)。
     */
    public void renderEntityModelOutline(PoseStack pose, ModelPart model, int color, float thickness) {
        renderEntityModelOutline(pose, model, TRIDENT_ENTITY_TEXTURE, PROJECTILE_INFLATE_PER_THICKNESS, color, thickness);
    }

    /**
     * 实体模型描边通用版(鞘翅/投掷物共用):逐 cube 绕自身包围盒中心放大壳。
     *
     * @param pose              实体渲染 PoseStack(已含实体变换与姿态)
     * @param model             实体模型根(烘焙的 ModelPart)
     * @param texture           独立纹理(非 atlas),描边着色器采样其 alpha 遮罩
     * @param inflatePerThick   每厚度放大增量
     * @param color             描边 ARGB
     * @param thickness         描边厚度(像素语义)
     */
    public void renderEntityModelOutline(PoseStack pose, ModelPart model, ResourceLocation texture,
                                         float inflatePerThick, int color, float thickness) {
        renderEntityModelOutline(pose, new ModelPart[]{model}, texture, inflatePerThick, color, thickness);
    }

    /**
     * 鞘翅穿戴描边:逐翼片绕自身包围盒中心放大壳。
     *
     * @param pose      实体渲染 PoseStack(已含实体变换与姿态)
     * @param wings     左右翼片(已 setupAnim 的 ModelPart)
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     */
    public void renderElytraOutline(PoseStack pose, ModelPart[] wings, int color, float thickness) {
        renderEntityModelOutline(pose, wings, ELYTRA_TEXTURE, ELYTRA_INFLATE_PER_THICKNESS, color, thickness);
    }

    /**
     * 实体模型描边通用版:逐 cube 绕自身包围盒中心放大壳。
     *
     * @param pose              实体渲染 PoseStack(已含实体变换与姿态)
     * @param models            实体模型部件(可多个,如鞘翅左右翼)
     * @param texture           独立纹理(非 atlas),描边着色器采样其 alpha 遮罩
     * @param inflatePerThick   每厚度放大增量
     * @param color             描边 ARGB
     * @param thickness         描边厚度(像素语义)
     */
    public void renderEntityModelOutline(PoseStack pose, ModelPart[] models, ResourceLocation texture,
                                         float inflatePerThick, int color, float thickness) {
        if (thickness <= 0f || this.outlineShader == null || models == null || models.length == 0) {
            return;
        }
        setOutlineAlphaBoost(2.0f);
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        float scale = 1.0f + thickness * THICKNESS_SCALE * inflatePerThick;
        VertexConsumer consumer = outlineBuffers.getBuffer(armorOutlineRenderType(texture, color));
        pose.pushPose();
        try {
            for (ModelPart part : models) {
                renderPartInflated(pose, part, consumer, packedColor, scale);
            }
        } finally {
            pose.popPose();
        }
        outlineBuffers.endBatch();
    }

    /**
     * 盔甲/鞘翅/投掷物描边 RenderType <b>统一入口</b>。
     * <p>
     * 无论混色开关与光影状态如何,<b>外层几何算法(renderPartInflated 逐 cube 放大壳)
     * 都是同一个</b>,本方法只负责选择"采样纹理",让不同模式下的形状保持一致:
     * <ul>
     *   <li>无光影 / 已开启 allowUnknownShaders(自定义 shader)→ 原纹理 + 自定义
     *       描边 shader(alpha boost 抬升,形状贴合纹理镂空);</li>
     *   <li>光影 fallback + 混色开(armorPixelColorGlint=true)→ 原纹理 + emissive
     *       (形状贴合纹理镂空,颜色与纹理混合);</li>
     *   <li>光影 fallback + 混色关(armorPixelColorGlint=false)→ <b>描边色形状纹理</b>
     *       (读取原纹理 alpha 遮罩,RGB=描边色)+ emissive —— 形状与混色开时
     *       <b>完全一致</b>,只是颜色为纯描边色。</li>
     * </ul>
     * 即:三种模式统一调用同一个外层算法,仅颜色来源(纹理 RGB / 纯描边色)不同。
     *
     * @param texture 盔甲/鞘翅/投掷物基础纹理(原纹理)
     * @param color   描边 ARGB(混色关时用于烘焙形状纹理)
     */
    private RenderType armorOutlineRenderType(ResourceLocation texture, int color) {
        if (needVanillaShaderFallback()) {
            if (Config.ARMOR_PIXEL_COLOR_GLINT.get()) {
                // 混色开:采样原纹理(alpha 遮罩 + 颜色混合)
                return worldEmissiveOutlineRenderType(texture);
            }
            // 混色关:采样"描边色形状纹理"(保留原纹理 alpha 遮罩形状,纯描边色)
            ResourceLocation shapeTex = shapeTextureForLocation(texture, color);
            return worldEmissiveOutlineRenderType(shapeTex);
        }
        // 自定义 shader:原纹理 + 描边 shader(alpha boost)
        RenderType type = this.armorRenderTypes.get(texture);
        if (type == null) {
            type = RenderType.create("enchanted_outlines_armor",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> this.outlineShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                            .createCompositeState(false));
            this.armorRenderTypes.put(texture, type);
        }
        return type;
    }
}
