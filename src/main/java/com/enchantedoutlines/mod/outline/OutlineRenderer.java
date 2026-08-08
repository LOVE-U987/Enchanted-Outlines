package com.enchantedoutlines.mod.outline;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.enchantedoutlines.mod.EnchantedOutlines;
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
 * <p>
 * ⚠️ <b>渲染架构铁律(违反即产生"正方形/立方体"描边 bug,详见仓库根目录 AGENTS.md):</b>
 * <ol>
 *   <li>独立纹理 PNG 读取一律用 {@code javax.imageio.ImageIO}(见
 *       {@link #shapeTextureForLocation})——palette(索引色)+ tRNS 透明贴图被
 *       {@code NativeImage.read} 读取会<b>丢失 alpha</b>,描边变实心立方体;</li>
 *   <li>亮度统计输出必须归一化到 0..1(÷255,见 {@link #averageLuma}),否则
 *       {@link #exposureScale} 算出 scale≈113 使 RGB 溢出 int,颜色完全错乱;</li>
 *   <li>形状算法是"统一轮廓"设计(扁平=形状纹理 / 3D=几何外扩 / 盔甲=逐 cube
 *       放大壳),任何优化不得改变其形状来源与几何语义。</li>
 * </ol>
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
     * 描边着色器的 alpha 强化 Uniform 引用(缓存,避免每帧每格重复字符串查找)。
     * 资源重载(F3+T)时随 {@link #setOutlineShader} 一起更新。
     */
    private volatile Uniform outlineAlphaBoostUniform;

    /** 描边着色器的硬切不透明 Uniform 引用(缓存,避免每帧每格重复字符串查找)。 */
    private volatile Uniform outlineCutoutUniform;

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

    /**
     * 源贴图 location → 平均感知亮度缓存(关闭混色时按物体颜色压暗曝光用)。
     * 同一贴图的不同描边色会各自生成形状纹理,亮度只依赖贴图内容 → 按 location
     * 缓存避免对同一张图反复遍历像素。资源重载(F3+T)时随 {@link #setOutlineShader}
     * 一起清空,贴图文件变更后重算。
     */
    private final Map<ResourceLocation, Float> lumaCache = new HashMap<>();

    /**
     * 模型几何缓存(WeakHashMap,键为 BakedModel 实例)。BakedModel 烘焙后不可变 →
     * quads / 法线外扩预处理 / 平均亮度一次性计算,帧间直接复用。
     * 资源重载(F3+T)后模型是全新实例,旧条目随弱引用自动回收,无需手动清理。
     */
    private final Map<BakedModel, ModelGeometry> modelGeometryCache = new WeakHashMap<>();

    /** 形状纹理缓存键:物品贴图 + 描边色(RGB) + 是否压暗曝光(开关切换时缓存失效)。 */
    private record ShapeKey(ResourceLocation spriteName, int color, boolean reduceExposure) {
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
     * <p>
     * ⚠️ 本方法是<b>渲染热路径</b>(每帧每格/每槽位调用),结果按 500ms 间隔缓存:
     * 不要改成每次实时反射检测(两次 invoke 开销大);切换光影包最多延迟半秒生效,
     * 可接受(AGENTS.md #5)。
     */
    private boolean needVanillaShaderFallback() {
        // 渲染热路径(每帧每格调用):反射检测有真实开销,按时间间隔缓存结果。
        // Iris 光影包切换不频繁,500ms 刷新一次即可;切换后最多延迟半秒生效。
        long now = System.currentTimeMillis();
        if (now - lastShaderStateCheck >= SHADER_STATE_REFRESH_MS) {
            cachedNeedVanillaShaderFallback = isShaderPackInUse() && !isUnknownShadersAllowed();
            lastShaderStateCheck = now;
        }
        return cachedNeedVanillaShaderFallback;
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

    /**
     * 光影状态检测结果缓存刷新间隔(毫秒)。
     * <p>
     * {@link #needVanillaShaderFallback} 在<b>每帧每格/每槽位</b>都会被调用(渲染热路径),
     * 每次都做两次反射 invoke。Iris 光影包切换是低频操作(玩家手动切换),缓存 500ms
     * 后重新检测即可:切换后最多延迟半秒生效,换来每帧省去数百次反射调用。
     */
    private static final long SHADER_STATE_REFRESH_MS = 500L;

    /** 上次光影状态检测时间(epoch ms);初始 0 保证首次调用立即检测。 */
    private static long lastShaderStateCheck = 0L;

    /** 缓存的上次 {@link #needVanillaShaderFallback} 检测结果。 */
    private static boolean cachedNeedVanillaShaderFallback = false;

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
        // 缓存 Uniform 引用:setUniform 是热路径(GUI 每格每帧),getUniform 是字符串
        // 查找;资源重载后 shader 是新实例,Uniform 引用随之刷新。
        this.outlineAlphaBoostUniform = shader != null ? shader.getUniform("OutlineAlphaBoost") : null;
        this.outlineCutoutUniform = shader != null ? shader.getUniform("OutlineCutout") : null;
        // 资源重载(F3+T 触发 RegisterShadersEvent)时,TextureManager 已销毁全部注册纹理,
        // "描边色形状纹理"缓存与"贴图亮度"缓存一并失效 → 清空以便按需重建。
        this.shapeTextures.clear();
        this.lumaCache.clear();
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
        Uniform uniform = this.outlineAlphaBoostUniform;
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
        Uniform uniform = this.outlineCutoutUniform;
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

        // 几何(quads / 法线外扩预处理)按 BakedModel 缓存,同物品多格共享,不再每帧重算
        ModelGeometry geo = geometryOf(model);
        List<BakedQuad> quads = geo.quads;
        if (quads.isEmpty()) {
            return;
        }

        if (model.isGui3d()) {
            // 3D 物品(方块/铁砧/盾牌等):顶点法线平均外扩(见 renderGui3dInflate)。
            // 用 COLOR_WRITE(不写深度)的 RenderType,本体随后覆盖中心露出外扩环。
            VertexConsumer shellConsumer = outlineBuffers.getBuffer(handOutlineRenderType());
            renderGui3dInflate(pose, geo, x, y, z, model, shellConsumer, packedColor, thickness);
        } else {
            // 扁平物品:8 方向屏幕偏移(各方向整体平移,屏幕像素语义)。
            // 基础矩阵链(scale + display transform + 居中)与方向无关 → 只应用一次,
            // 每个方向仅做一次平移,避免 8 次重复矩阵乘法。
            VertexConsumer consumer = outlineBuffers.getBuffer(outlineRenderType());
            pose.pushPose();
            try {
                pose.translate(x + 8, y + 8, z);
                pose.scale(16.0F, -16.0F, 16.0F);
                model.getTransforms().getTransform(ItemDisplayContext.GUI).apply(false, pose);
                pose.translate(-0.5F, -0.5F, -0.5F);
                // 屏幕偏移 thickness×0.5px 在 scale(16) 之后 → 模型空间偏移 = 像素/16
                float t = thickness * THICKNESS_SCALE / 16.0f;
                List<BakedQuad> geoQuads = geo.quads;
                List<Vec3i> geoNormals = geo.expandNormals;
                Vector3f tmp = new Vector3f(); // 复用,避免每顶点分配
                for (float[] off : OFFSETS) {
                    pose.pushPose();
                    try {
                        pose.translate(off[0] * t, off[1] * t, 0.0f);
                        Matrix4f poseMatrix = pose.last().pose();
                        for (int qi = 0; qi < geoQuads.size(); qi++) {
                            emitQuad(geoQuads.get(qi), geoNormals.get(qi), poseMatrix,
                                    consumer, packedColor, tmp);
                        }
                    } finally {
                        pose.popPose();
                    }
                }
            } finally {
                pose.popPose();
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
    private void renderGui3dInflate(PoseStack pose, ModelGeometry geo, int x, int y, int z,
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
            renderVertexNormalExpand(geo, pose, consumer, packedColor,
                    thickness * THICKNESS_SCALE / 16.0f);
        } finally {
            pose.popPose();
        }
    }

    /** 顶点位置 key(用原始 float 位做 equals/hashCode,精确去重共享顶点)。 */
    private record Position(float x, float y, float z) {
    }

    /**
     * 外扩预处理顶点:位置/UV + 平均法线方向(已归一化)。
     * 由 {@link #prepareExpand} 一次性算好缓存进 {@link ModelGeometry},
     * 帧内只做矩阵变换,不再每帧分配 HashMap 与顶点对象。
     */
    private record ExpandVertex(float x, float y, float z, float u, float v,
                                float nx, float ny, float nz) {
    }

    /**
     * 单个 BakedModel 的渲染预处理缓存(模型不可变 → 一次性计算,帧间复用)。
     * <p>
     * 原实现里 {@link #collectQuads(BakedModel)} 与顶点法线平均外扩在<b>每次渲染</b>
     * 都重新执行:GUI 中同一物品 64 格 → 每帧 64 次全模型遍历 + 64 次法线 HashMap;
     * 手持/盔甲每帧同样重复。这里按 BakedModel 实例缓存全部与帧无关的结果:
     * <ul>
     *   <li>{@link #quads}:模型全部 quad(collectQuads 结果);</li>
     *   <li>{@link #expandVertices} / {@link #expandNormals}:顶点法线平均外扩的预处理
     *       (顶点坐标 + 归一化平均法线方向 + 每 quad 面法线),帧内只做矩阵变换;</li>
     *   <li>{@link #bySprite} / {@link #spriteQuadIndices}:按 sprite 分组
     *       (扁平物品形状纹理路径),索引映射一次算好,帧内按索引取法线;</li>
     *   <li>{@link #luma}:主 sprite 平均感知亮度(关闭混色时按物体颜色压暗曝光)。</li>
     * </ul>
     * <p>
     * ⚠️ <b>缓存纪律(AGENTS.md #4)</b>:只缓存与帧无关的输入(顶点坐标/UV/法线),
     * 帧内仍做矩阵变换;任何优化不得改变渲染结果 —— 改完后必须在开启/关闭光影
     * 两种模式下验证形状与颜色不变。
     */
    private static final class ModelGeometry {
        final List<BakedQuad> quads;
        final List<ExpandVertex[]> expandVertices;
        final List<Vec3i> expandNormals;
        final Map<TextureAtlasSprite, List<BakedQuad>> bySprite;
        /** 每个 sprite 的 quad 在 {@link #quads} 中的索引(与 {@link #bySprite} 顺序一致,用于取法线)。 */
        final Map<TextureAtlasSprite, int[]> spriteQuadIndices;
        final float luma;
        /** 是否全部 quad 使用 stone 精灵(盾牌/三叉戟盒模型,光影 fallback 下恒纯白纯色)。 */
        final boolean allStone;

        ModelGeometry(BakedModel model) {
            this.quads = collectQuads(model);
            List<Vec3i> normals = new ArrayList<>(quads.size());
            for (BakedQuad q : quads) {
                normals.add(safeQuadNormal(q));
            }
            this.expandNormals = normals;
            this.expandVertices = prepareExpand(quads, normals);
            Map<TextureAtlasSprite, int[]> spriteIndices = new LinkedHashMap<>();
            this.bySprite = groupBySprite(quads, spriteIndices);
            this.spriteQuadIndices = spriteIndices;
            this.luma = mainSpriteLuma(quads);
            this.allStone = usesOnlyStoneSprite(quads);
        }
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
     * <p>
     * <b>性能</b>:法线平均计算与顶点数据(坐标/UV/方向)与帧无关,已由
     * {@link ModelGeometry} 一次性预处理缓存;本方法每帧只做矩阵变换与顶点写入,
     * 不再分配 HashMap / 顶点对象(共享顶点仍按 quad 重复输出,与 GPU 顶点级语义一致)。
     *
     * @param geo         模型几何缓存(含外扩预处理)
     * @param pose        已居中(translate(-0.5))的 PoseStack
     * @param consumer    顶点写入目标
     * @param packedColor 描边色(ABGR 打包)
     * @param offset      外扩距离(模型单位)
     */
    private void renderVertexNormalExpand(ModelGeometry geo, PoseStack pose, VertexConsumer consumer,
                                          int packedColor, float offset) {
        Matrix4f poseMatrix = pose.last().pose();
        Vector3f tmp = new Vector3f(); // 复用,避免每顶点分配
        List<ExpandVertex[]> quadVertices = geo.expandVertices;
        List<Vec3i> quadNormals = geo.expandNormals;
        for (int qi = 0; qi < quadVertices.size(); qi++) {
            Vec3i n = quadNormals.get(qi);
            for (ExpandVertex v : quadVertices.get(qi)) {
                Vector3f p = poseMatrix.transformPosition(
                        v.x() + v.nx() * offset, v.y() + v.ny() * offset, v.z() + v.nz() * offset, tmp);
                consumer.addVertex(p.x(), p.y(), p.z(), packedColor, v.u(), v.v(),
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
        // 几何(quads / 法线外扩预处理 / 平均亮度)按 BakedModel 缓存,帧间复用
        ModelGeometry geo = geometryOf(model);
        List<BakedQuad> quads = geo.quads;
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
        // ⚠️ 形状算法是"统一轮廓"设计(AGENTS.md #3):下方各分支只换"采样纹理"与
        // "几何算法"的既定组合,不得拆分/替换形状来源 —— 扁平=形状纹理、
        // 3D=顶点法线外扩、盔甲/鞘翅/投掷物=逐 cube 放大壳,保持 v0.1.2 语义。
        boolean fallback = needVanillaShaderFallback();
        if (fallback && !model.isGui3d() && !Config.ITEM_PIXEL_COLOR_GLINT.get()) {
            renderFlatPureColorShape(geo, pose, packedColor, thickness);
            outlineBuffers.endBatch();
            return;
        }
        RenderType renderType;
        if (fallback) {
            if (model.isGui3d()) {
                // 盾牌/三叉戟盒模型恒纯白;真实 3D 物品按混色开关选择 BLOCK_SHEET / 纯白。
                // 凡是纯白路径(pureWhite = true)都是"纯描边色",emissive 全亮下亮色
                // 描边(粉/金/白)过曝刺眼。混合模式因乘了暗色物品贴图而天然压暗
                // ("根据物体颜色降低曝光");纯色路径缺这层调制 → 按物体主 sprite
                // 平均亮度压暗描边色(色相不变,暗色物品描边更暗)。盾牌/三叉戟恒纯色
                // → 无论混色开关如何都压暗。
                boolean pureWhite = geo.allStone || !Config.ITEM_PIXEL_COLOR_GLINT.get();
                renderType = pureWhite
                        ? worldEmissiveOutlineRenderType(WHITE_TEXTURE)
                        : worldEmissiveOutlineRenderType();
                if (pureWhite) {
                    packedColor = darkenByLuma(packedColor, geo.luma);
                }
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
            renderVertexNormalExpand(geo, pose, consumer, packedColor, offset);
        } else {
            // 扁平物品(剑/工具等):整体平面内 8 方向平移,UV 不变 → 剑形轮廓
            // 均匀外扩、边缘贴合。移动顶点会拉伸 UV → 外扩量 = (scale-1)×到中心
            // 距离,剑身中部离中心近、外扩少,剑头离中心远、外扩多 → 轮廓裂开。
            // 整体平移则每个方向外扩量恒定,与 GUI 扁平物品的屏幕偏移同思路。
            float t = thickness * THICKNESS_SCALE / 16.0f;
            List<BakedQuad> geoQuads = geo.quads;
            List<Vec3i> geoNormals = geo.expandNormals;
            Vector3f tmp = new Vector3f(); // 复用,避免每顶点分配
            for (float[] off : OFFSETS) {
                pose.pushPose();
                try {
                    pose.translate(off[0] * t, off[1] * t, 0.0f);
                    Matrix4f m = pose.last().pose();
                    for (int qi = 0; qi < geoQuads.size(); qi++) {
                        emitQuad(geoQuads.get(qi), geoNormals.get(qi), m, consumer, packedColor, tmp);
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
     * <p>
     * ⚠️ 本方法只在 {@link ModelGeometry} 构造时调用一次(结果已缓存,AGENTS.md #4);
     * 不要在渲染热路径里直接调用它 —— 那会让几何在每帧每格重复计算。
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
     * 取(或构建)模型的几何缓存,帧间复用 quads / 法线外扩预处理 / 平均亮度。
     * 弱引用缓存:模型不再被引用(资源重载)时自动回收。
     */
    private ModelGeometry geometryOf(BakedModel model) {
        ModelGeometry geo = this.modelGeometryCache.get(model);
        if (geo == null) {
            geo = new ModelGeometry(model);
            this.modelGeometryCache.put(model, geo);
        }
        return geo;
    }

    /**
     * 顶点法线平均外扩的预处理:第一遍累加每个共享顶点的相邻面法线并归一化,
     * 第二遍把"顶点坐标 / UV / 平均法线方向"打包成 {@link ExpandVertex} 缓存。
     * 原实现每帧重复这两遍并分配 HashMap;现在只在模型首次渲染时算一次。
     * <p>
     * 兼容性:direction 为 null 的 quad 用 safeQuadNormal 叉积兜底;零法线(退化面)
     * 跳过累加——零向量若参与 accumulate 后 normalize 会得 NaN(0/0),污染整件物品描边。
     *
     * @param quads       模型 quads(与 quadNormals 顺序一致)
     * @param quadNormals 每 quad 的面法线(缓存复用)
     * @return 与 quads 一一对应的外扩顶点数组
     */
    private static List<ExpandVertex[]> prepareExpand(List<BakedQuad> quads, List<Vec3i> quadNormals) {
        Map<Position, Vector3f> normals = new HashMap<>();
        for (int qi = 0; qi < quads.size(); qi++) {
            Vec3i n = quadNormals.get(qi);
            if (n.getX() == 0 && n.getY() == 0 && n.getZ() == 0) {
                continue;
            }
            int[] v = quads.get(qi).getVertices();
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
        List<ExpandVertex[]> out = new ArrayList<>(quads.size());
        for (int qi = 0; qi < quads.size(); qi++) {
            int[] v = quads.get(qi).getVertices();
            ExpandVertex[] verts = new ExpandVertex[v.length / 8];
            int vi = 0;
            for (int i = 0; i + 8 <= v.length; i += 8, vi++) {
                float x = Float.intBitsToFloat(v[i]);
                float y = Float.intBitsToFloat(v[i + 1]);
                float z = Float.intBitsToFloat(v[i + 2]);
                float u = Float.intBitsToFloat(v[i + 4]);
                float vv = Float.intBitsToFloat(v[i + 5]);
                // 防御:顶点未参与第一遍(极端 float 位不一致)→ 零方向,不外扩
                Vector3f dir = normals.get(new Position(x, y, z));
                float dx = dir != null ? dir.x() : 0f;
                float dy = dir != null ? dir.y() : 0f;
                float dz = dir != null ? dir.z() : 0f;
                verts[vi] = new ExpandVertex(x, y, z, u, vv, dx, dy, dz);
            }
            out.add(verts);
        }
        return out;
    }

    /**
     * 按 sprite 分组(扁平物品形状纹理路径用);无 sprite 的 quad 忽略。
     * 同时把每个 quad 在 quads 列表中的索引写入 spriteIndices(用于帧内按索引取法线,
     * 避免每帧 indexOf 搜索)。
     */
    private static Map<TextureAtlasSprite, List<BakedQuad>> groupBySprite(
            List<BakedQuad> quads, Map<TextureAtlasSprite, int[]> spriteIndices) {
        Map<TextureAtlasSprite, List<BakedQuad>> bySprite = new LinkedHashMap<>();
        Map<TextureAtlasSprite, java.util.List<Integer>> indexAcc = new LinkedHashMap<>();
        for (int i = 0; i < quads.size(); i++) {
            TextureAtlasSprite sprite = quads.get(i).getSprite();
            if (sprite == null) {
                continue;
            }
            bySprite.computeIfAbsent(sprite, k -> new ArrayList<>()).add(quads.get(i));
            indexAcc.computeIfAbsent(sprite, k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<TextureAtlasSprite, java.util.List<Integer>> e : indexAcc.entrySet()) {
            int[] idx = new int[e.getValue().size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = e.getValue().get(i);
            }
            spriteIndices.put(e.getKey(), idx);
        }
        return bySprite;
    }

    /**
     * 模型主 sprite 的平均感知亮度(0..1;读取失败返回 -1)。
     * 关闭混色时按物体颜色压暗纯色描边曝光(见 {@link #exposureScale})。
     */
    private static float mainSpriteLuma(List<BakedQuad> quads) {
        for (BakedQuad q : quads) {
            TextureAtlasSprite s = q.getSprite();
            if (s == null) {
                continue;
            }
            NativeImage img = spriteOriginalImage(s);
            if (img != null) {
                float luma;
                try {
                    luma = averageLuma(img);
                } catch (Exception ignored) {
                    // 该贴图无法读取亮度(如格式不支持)→ 试下一个;绝不中断几何缓存构建
                    continue;
                }
                if (luma >= 0f) {
                    return luma;
                }
            }
        }
        return -1f;
    }

    /**
     * 平均感知亮度(Rec.601 加权,按 alpha 加权平均;全透明或无法读取返回 -1)。
     * 大图自动降采样(步长 = 像素数 / 4096),16×16 贴图全量遍历,只算一次并缓存。
     * <p>
     * ⚠️ <b>返回范围必须是 0..1(已 ÷255)</b>:曾因漏除 255 导致
     * {@link #exposureScale} 输出 scale≈113.9,描边色 RGB 乘巨大值后溢出 32 位
     * int → 颜色完全错乱(2026-08-08 事故)。任何人改动本方法,先确认返回值域。
     */
    private static float averageLuma(NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int step = Math.max(1, (w * h) / 4096);
        long sum = 0, count = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                // NativeImage RGBA 内存小端 → getPixelRGBA 返回 ABGR,alpha 在最高字节
                int abgr = img.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
                sum += (long) (299 * r + 587 * g + 114 * b) * a;
                count += a;
            }
        }
        if (count == 0) {
            return -1f;
        }
        // Rec.601 加权平均后 ÷255 归一化到 0..1
        return (float) (sum / (double) (count * 1000 * 255));
    }

    /**
     * 取(并缓存)源贴图的平均感知亮度;无法读取时返回 -1(不压暗)。
     * 缓存按 location,资源重载时清空。
     * <p>
     * ⚠️ 返回 -1(而非异常)是设计:任何亮度读取失败只"不压暗",
     * <b>绝不</b>中断或改变形状纹理生成(否则描边退化为矩形,见 AGENTS.md #2)。
     */
    private float lumaOf(ResourceLocation source, NativeImage img) {
        Float cached = this.lumaCache.get(source);
        if (cached != null) {
            return cached;
        }
        float luma;
        try {
            luma = averageLuma(img);
        } catch (Exception ignored) {
            // 亮度读取失败(如贴图格式不支持 getPixelRGBA):不压暗、不中断形状纹理生成
            luma = -1f;
        }
        this.lumaCache.put(source, luma);
        return luma;
    }

    /**
     * 纯色描边的曝光缩放:物体越暗,描边越暗(0.35..1.0)。
     * <p>
     * 模拟"混合算法根据物体颜色降低曝光亮度":混色时 {@code texel × vertexColor}
     * 被暗色物品贴图天然压暗;纯色路径没有这层调制,亮色描边在 emissive 全亮下
     * 会过曝刺眼 → 按物体平均亮度补上。luma=1(纯白)保持原亮度,luma=0(纯黑)
     * 压到 35%(下限保证描边仍可见)。
     *
     * @param luma 物体平均感知亮度(0..1);-1 = 无法读取,不压暗
     */
    // ⚠️ 返回值域硬约束 0.35..1.0:调用方(lumaOf/shapeTexture/darkenByLuma)依赖此范围,
    // 若 luma 不是 0..1(如 averageLuma 漏除 255)本函数会输出 55~113 的非法 scale。
    private static float exposureScale(float luma) {
        if (luma < 0f) {
            return 1.0f;
        }
        return 0.35f + 0.65f * luma;
    }

    /**
     * 按物体平均亮度压暗 ARGB 描边色(仅 RGB,色相不变;配置关闭时原样返回)。
     * <p>
     * ⚠️ 输入 luma 必须是 0..1(见 {@link #averageLuma} 的归一化铁律);scale 溢出
     * 会破坏颜色。改动前先确认调用链的归一化未被破坏(AGENTS.md #2)。
     */
    private static int darkenByLuma(int argb, float luma) {
        if (!Config.OUTLINE_EXPOSURE_REDUCE.get()) {
            return argb;
        }
        float scale = exposureScale(luma);
        int r = (int) (((argb >> 16) & 0xFF) * scale);
        int g = (int) (((argb >> 8) & 0xFF) * scale);
        int b = (int) ((argb & 0xFF) * scale);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
     *
     * @param quad        要输出的 quad
     * @param normal      该 quad 的法线(已由 {@link ModelGeometry} 缓存,避免每方向重复计算)
     * @param poseMatrix  顶点变换矩阵
     * @param consumer    顶点写入目标
     * @param packedColor 描边色(ABGR 打包)
     * @param tmp         复用的矩阵变换结果缓冲
     */
    private static void emitQuad(BakedQuad quad, Vec3i normal, Matrix4f poseMatrix,
                                 VertexConsumer consumer, int packedColor, Vector3f tmp) {
        int[] vertices = quad.getVertices();
        for (int i = 0; i + 8 <= vertices.length; i += 8) {
            float x = Float.intBitsToFloat(vertices[i]);
            float y = Float.intBitsToFloat(vertices[i + 1]);
            float z = Float.intBitsToFloat(vertices[i + 2]);
            float u = Float.intBitsToFloat(vertices[i + 4]);
            float v = Float.intBitsToFloat(vertices[i + 5]);
            Vector3f p = poseMatrix.transformPosition(x, y, z, tmp);
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
     * <p>
     * ⚠️ 本路径是扁平物品"统一轮廓"形状的来源(AGENTS.md #3):形状完全取决于
     * {@link #shapeTextureFor} 生成的形状纹理(alpha 遮罩)。若纹理回退纯白矩形
     * (读不到 alpha),描边会变成正方形 —— 修改前先检查 {@link #shapeTextureForLocation}
     * 的 ImageIO 铁律与 {@link #spriteOriginalImage} 的父类链查找未被破坏。
     *
     * @param quads     收集到的模型 quads
     * @param pose      已居中(translate(-0.5))的 PoseStack
     * @param color     ARGB 描边色
     * @param thickness 描边厚度(像素语义)
     */
    private void renderFlatPureColorShape(ModelGeometry geo, PoseStack pose, int color, float thickness) {
        float t = thickness * THICKNESS_SCALE / 16.0f;
        // 按 sprite 分组(已随模型缓存):同一贴图的 quad 共用一张形状纹理(同一 RenderType 一次绑定)。
        // 法线与 quad 索引也随模型缓存,帧内按索引取,不做 indexOf 搜索。
        Map<TextureAtlasSprite, List<BakedQuad>> bySprite = geo.bySprite;
        Map<TextureAtlasSprite, int[]> spriteQuadIndices = geo.spriteQuadIndices;
        List<Vec3i> geoNormals = geo.expandNormals;
        Vector3f tmp = new Vector3f(); // 复用,避免每顶点分配
        for (Map.Entry<TextureAtlasSprite, List<BakedQuad>> entry : bySprite.entrySet()) {
            TextureAtlasSprite sprite = entry.getKey();
            ResourceLocation tex = shapeTextureFor(sprite, color);
            VertexConsumer consumer = outlineBuffers.getBuffer(worldEmissiveOutlineRenderType(tex));
            List<BakedQuad> spriteQuads = entry.getValue();
            int[] quadIndices = spriteQuadIndices.get(sprite);
            for (float[] off : OFFSETS) {
                pose.pushPose();
                try {
                    pose.translate(off[0] * t, off[1] * t, 0.0f);
                    Matrix4f m = pose.last().pose();
                    for (int i = 0; i < spriteQuads.size(); i++) {
                        emitQuadShape(sprite, spriteQuads.get(i), geoNormals.get(quadIndices[i]),
                                m, consumer, tmp);
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
        boolean reduce = Config.OUTLINE_EXPOSURE_REDUCE.get();
        ShapeKey key = new ShapeKey(source, rgb, reduce);
        ResourceLocation loc = this.shapeTextures.get(key);
        if (loc == null) {
            int w = src.getWidth(), h = src.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            // 关闭混色时描边为纯色,emissive 全亮下亮色(粉/金/白)会过曝刺眼。
            // 按源贴图的平均感知亮度压暗 RGB(色相不变):暗色物品(铁剑等)的描边
            // 显著变暗、亮色物品基本不变 —— 模拟"混合算法根据物体颜色降低曝光"。
            // 同一贴图不同描边色会各生成一张形状纹理,亮度按 source 缓存避免重复遍历。
            float scale = reduce ? exposureScale(lumaOf(source, src)) : 1.0f;
            int r = (int) (((rgb >> 16) & 0xFF) * scale);
            int g = (int) (((rgb >> 8) & 0xFF) * scale);
            int b = (int) ((rgb & 0xFF) * scale);
            try {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // ⚠️ NativeImage RGBA 内存小端:getPixelRGBA 返回 ABGR 打包,
                        // alpha 在最高字节;setPixelRGBA 写入 (a<<24)|(b<<16)|(g<<8)|r。
                        // 通道顺序写错会导致形状/颜色错乱,改动前务必确认打包方向。
                        int a = (src.getPixelRGBA(x, y) >>> 24) & 0xFF;
                        img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
            } catch (Exception e) {
                // 源贴图格式不支持像素读取(如 RGB 无 alpha)→ 无形状信息,回退纯色矩形
                return WHITE_TEXTURE;
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
     * 独立纹理(盔甲/鞘翅/投掷物实体)版形状纹理:读取纹理 PNG,走统一入口 {@link #shapeTexture}。
     * <p>
     * <b>关键</b>:不能用 {@code NativeImage.read(InputStream)} 直接读 —— 它对
     * <b>palette(索引色)+ tRNS 透明</b> 的 PNG(原版盔甲/鞘翅/多数物品贴图都是)会
     * <b>丢失透明信息</b>,解码后全像素 alpha=255 → 形状纹理 100% 实心 → 描边变成
     * 实心立方体。改用 JDK {@code ImageIO}(正确展开 palette 的 tRNS 为 alpha)读取
     * ARGB,再逐像素拷贝到 NativeImage。
     * <p>
     * <b>性能</b>:本方法每帧每实体每槽位都会被调用(armorOutlineRenderType),先在
     * {@link #shapeTextures} 缓存里查键(source, rgb, reduce),命中直接返回,不再读文件
     * (首次才读)。
     *
     * @param texture 独立纹理 location(如 armor / elytra / trident 材质)
     * @param color   ARGB 描边色
     * @return 已注册的纹理 location;读取失败时回退纯白矩形
     */
    private ResourceLocation shapeTextureForLocation(ResourceLocation texture, int color) {
        int rgb = color & 0xFFFFFF;
        boolean reduce = Config.OUTLINE_EXPOSURE_REDUCE.get();
        ShapeKey key = new ShapeKey(texture, rgb, reduce);
        ResourceLocation cached = this.shapeTextures.get(key);
        if (cached != null) {
            return cached; // 已生成过:直接复用,不再读 PNG
        }
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return WHITE_TEXTURE;
            }
            try (InputStream in = resource.get().open()) {
                // ⚠️ 铁律:必须用 ImageIO 而非 NativeImage.read。原版盔甲/鞘翅贴图是
                // palette(索引色)+ tRNS 透明 PNG,NativeImage.read 会丢失透明信息,
                // 解码后全像素 alpha=255 → 形状纹理 100% 实心 → 描边变实心立方体
                // (2026-08-08 事故)。ImageIO 正确展开 palette 的 tRNS 为 alpha。
                // ImageIO 正确处理 palette+tRNS:透明像素 alpha=0(不透明 255)
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(in);
                if (bi == null) {
                    return WHITE_TEXTURE;
                }
                int w = bi.getWidth(), h = bi.getHeight();
                NativeImage src = new NativeImage(w, h, false);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // ⚠️ NativeImage RGBA 内存小端:getPixelRGBA 返回 ABGR 打包,
                        // alpha 在最高字节;setPixelRGBA 写入 (a<<24)|(b<<16)|(g<<8)|r。
                        // 通道顺序写错会导致形状/颜色错乱,改动前务必确认打包方向。
                        int argb = bi.getRGB(x, y); // ARGB
                        int a = (argb >>> 24) & 0xFF;
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        // NativeImage 内存小端 ABGR 打包:alpha 在最高字节
                        src.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
                ResourceLocation loc = shapeTexture(texture, src, color);
                src.close();
                return loc;
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
     * <p>
     * ⚠️ 按 sprite contents 的<b>类</b>缓存 Field(静态,避免每次 getDeclaredField);
     * 查找必须用 {@link #findOriginalImageField} <b>沿父类链递归</b> —— SpriteContents
     * 子类不直接声明该字段,直接 getDeclaredField 会 NoSuchFieldException → 若因此
     * 返回 null,扁平物品形状纹理回退纯白矩形(描边变成方块)。
     */
    private static final Map<Class<?>, java.lang.reflect.Field> ORIGINAL_IMAGE_FIELDS = new HashMap<>();

    private static NativeImage spriteOriginalImage(TextureAtlasSprite sprite) {
        try {
            Class<?> clazz = sprite.contents().getClass();
            java.lang.reflect.Field field = ORIGINAL_IMAGE_FIELDS.get(clazz);
            if (field == null) {
                field = findOriginalImageField(clazz);
                if (field == null) {
                    return null; // 找不到(异常类):本次失败,不缓存失败标记,下次重试
                }
                field.setAccessible(true);
                ORIGINAL_IMAGE_FIELDS.put(clazz, field);
            }
            return (NativeImage) field.get(sprite.contents());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 沿类链向上查找 originalImage 字段(父类声明的私有字段对子类实例同样可读)。 */
    private static java.lang.reflect.Field findOriginalImageField(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField("originalImage");
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * 单个 quad 以"形状纹理"模式写入:UV 从 atlas 绝对坐标重映射为该 sprite 的相对坐标
     * (0..1,独立纹理全图),顶点颜色传白色(描边色已烘焙进纹理)。
     *
     * @param sprite      形状纹理对应的 atlas 精灵(用于 UV 重映射)
     * @param quad        要输出的 quad
     * @param normal      该 quad 的法线(已由 {@link ModelGeometry} 缓存,避免每方向重复计算)
     * @param poseMatrix  顶点变换矩阵
     * @param consumer    顶点写入目标
     * @param tmp         复用的矩阵变换结果缓冲
     */
    private static void emitQuadShape(TextureAtlasSprite sprite, BakedQuad quad, Vec3i normal,
                                      Matrix4f poseMatrix, VertexConsumer consumer, Vector3f tmp) {
        int[] vertices = quad.getVertices();
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
            Vector3f p = poseMatrix.transformPosition(x, y, z, tmp);
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
     * <p>
     * <b>性能</b>:visit 回调是每帧每 cube 调用的热路径,复用局部 Matrix4f 临时对象
     * (而非每 cube 新建 3 个),JOML 的矩阵变换是纯计算,不保留引用 → 复用安全。
     */
    private static void renderPartInflated(PoseStack pose, ModelPart part, VertexConsumer consumer,
                                           int packedColor, float scale) {
        if (part == null || !part.visible) {
            return;
        }
        Matrix4f original = new Matrix4f(); // 复用:保存/还原 cube 局部 pose
        Matrix4f transform = new Matrix4f(); // 复用:T(c)·S·T(-c) 临时
        part.visit(pose, (p, path, index, cube) -> {
            // cube 包围盒中心(像素单位 → 模型单位:像素/16)
            float cx = (cube.minX + cube.maxX) / 2.0f / 16.0f;
            float cy = (cube.minY + cube.maxY) / 2.0f / 16.0f;
            float cz = (cube.minZ + cube.maxZ) / 2.0f / 16.0f;
            original.set(p.pose());
            // transform = T(c)·S·T(-c),复用同一对象(矩阵乘法是纯读源写目标)
            transform.identity()
                    .translate(cx, cy, cz)
                    .scale(scale)
                    .translate(-cx, -cy, -cz);
            p.pose().mul(transform);
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
     * <p>
     * ⚠️ <b>统一入口(AGENTS.md #3)</b>:本方法<b>只负责选择采样纹理</b>,外层几何
     * 算法(renderPartInflated 逐 cube 放大壳)恒同。不要在此为某模式单独改几何,
     * 否则各模式形状不一致(如盔甲变实心立方体)。
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
