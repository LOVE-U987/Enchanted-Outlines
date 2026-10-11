package com.enchantedoutlines.mod.outline;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

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
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
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
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
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
 * <p>
 * <b>子模块结构(2026-09-25 行为保持拆分)</b>:BEWLR 家族已拆为同包独立类,本类保留
 * 渲染管线核心(shader/uniform/RenderType/描边缓冲/缓存统一失效)与 BEWLR 统一派发
 * 入口,各子模块经构造时注入的本类实例访问共享设施:
 * <ul>
 *   <li>{@link BewlrCapture} —— 通用渲染捕获(任意模组零适配兜底;双守卫+节流诊断日志,
 *       "通用算法 v2"规范化捕获的落地战场);</li>
 *   <li>{@link LionfishOutline} —— LionfishAPI 骨骼(灾变)逐 cube 顶点法线外扩 / 整体壳
 *       回退描边;</li>
 *   <li>{@link TaczOutline} —— TACZ/Hazen 命名空间判定(TACZ 描边已统一走 v2 捕获,
 *       含全局缓冲旁路;专项复刻路径已于 v2.1 删除);</li>
 *   <li>{@link GeoOutline} —— GeckoLib/AzureLib Geo 物品与盔甲描边(含盔甲类型检测,
 *       本类保留 mixin 所需公有门面);</li>
 *   <li>{@link BewlrModelResolver} —— 静态字段模型解析(永恒星光/灾变精确映射表+预变换
 *       类别,纯静态)。</li>
 * </ul>
 * <b>已知限制:</b>BEWLR 自定义渲染物品(盾牌、三叉戟、望远镜已用近似模型支持;
 * 其余在 GUI/掉落物/展示框下若有 {@code <itemId>_inventory} 平面模型变体则用其描边
 * (如永恒星光月弧长枪);手持 BEWLR 物品(月弧长枪/灾变武器等)本体是模组自定义
 * 实体模型,通过 {@link #renderBewlrEntityOutline} 反射其模型做逐 cube/整体放大壳描边。
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

    /**
     * 当前是否处于 {@code ItemInHandRenderer.renderItem} 渲染上下文的标记。
     * <p>
     * 玩家手持物品描边由 {@code ItemInHandRendererMixin} 在
     * {@code ItemInHandRenderer.renderItem} HEAD 渲染(该 pose 已含 Better Combat/
     * PlayerAnimator 攻击动画变换,描边与本体同步);{@code ItemRendererMixin} 的
     * 8 参 render HEAD 玩家手持分支据此跳过,避免"静态描边 + 动画描边"重复。
     * <p>
     * 放在本普通类而非 mixin 类:Mixin 会把非私有方法尝试合并进目标类,public
     * static 工具方法会抛 InvalidMixinException(2026-08-13 崩溃:
     * "contains non-private static method")。
     */
    private static final ThreadLocal<Boolean> IN_ITEM_IN_HAND_RENDERER =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 设置/清除 {@code ItemInHandRenderer.renderItem} 上下文标记(HEAD 设 true、
     * RETURN 设 false;渲染线程单线程,ThreadLocal 仅为严谨)。
     *
     * @param in true = 进入 ItemInHandRenderer 渲染(描边已由该层处理)
     */
    public static void markItemInHandRender(boolean in) {
        IN_ITEM_IN_HAND_RENDERER.set(in);
    }

    /**
     * 查询当前是否处于 {@code ItemInHandRenderer.renderItem} 渲染上下文。
     *
     * @return true = 玩家手持物品描边已由 {@code ItemInHandRendererMixin} 处理,
     *         调用方应跳过
     */
    public static boolean isInItemInHandRenderer() {
        return IN_ITEM_IN_HAND_RENDERER.get();
    }

    /**
     * 当前是否处于 {@code GuiGraphics.renderItem}(背包/容器/快捷栏等 GUI 物品)渲染
     * 上下文的标记。
     * <p>
     * ⚠️ <b>GUI 屏幕空间渲染不经 Iris 光影</b>(见 {@link #needVanillaShaderFallback}):
     * 光影激活状态只对世界渲染(LevelRenderer)生效,GUI 物品图标始终用原版渲染管线
     * 绘制自定义 shader。若 GUI 期间仍按光影状态 fallback 到 emissive RenderType
     * (带 LIGHTMAP/OVERLAY 状态、为世界渲染设计),背包/容器 UI 里<b>所有非方块物品</b>
     * 的轮廓会渲染成错误的淡灰色 —— 方块(GUI 3D)走 {@code handOutlineRenderType}
     * 恒自定义 shader 不受影响,所以"物品灰、方块正常"(2026-08-31 事故,波及含
     * TACZ/Spore 在内的全部模组扁平物品与 Geo/BEWLR GUI 图标)。
     * <p>
     * {@code GuiGraphicsMixin} 在 renderItem HEAD/RETURN 设置/清除本标记;期间
     * {@link #needVanillaShaderFallback()} 恒返回 false(且不更新全局缓存,避免污染
     * 世界渲染的光影判断)。
     * <p>
     * 放在本普通类而非 mixin 类:Mixin 会把非私有方法尝试合并进目标类,public
     * static 工具方法会抛 InvalidMixinException(2026-08-13 崩溃)。
     */
    private static final ThreadLocal<Boolean> IN_GUI_RENDER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 当前正在描边的物品 ID(ThreadLocal,由各 mixin 入口设置 / finally 恢复)。
     * <p>
     * 供深层混色决策({@code renderFlatOutlineShape} 的 mix、
     * {@code armorOutlineRenderType} 的光影纹理选择、光影 fallback 的纯白判定)
     * 查询<b>逐物品覆盖</b>(配置 {@code solidColorItems} / 程序化
     * {@code registerItemSolid})—— 这些决策点拿不到 stack,经本标记传递当前物品。
     * 与 IN_GUI_RENDER / IN_ITEM_IN_HAND_RENDERER 同款模式(入口设 / finally 清)。
     */
    private static final ThreadLocal<ResourceLocation> CURRENT_OUTLINED_ITEM = new ThreadLocal<>();

    /**
     * 设置当前正在描边的物品(各 mixin 入口调用)。
     *
     * @param stack 正在描边的物品栈(空栈按 AIR 处理,不报错)
     */
    public static void markOutlinedItem(ItemStack stack) {
        ResourceLocation id = stack.getItemHolder().unwrapKey().map(key -> key.location()).orElse(null);
        CURRENT_OUTLINED_ITEM.set(id);
    }

    /** 清空当前描边物品标记(各 mixin 入口的 finally 调用)。 */
    public static void clearOutlinedItem() {
        CURRENT_OUTLINED_ITEM.remove();
    }

    /** 当前正在描边的物品 ID;无上下文返回 null。 */
    static ResourceLocation currentOutlinedItem() {
        return CURRENT_OUTLINED_ITEM.get();
    }

    /**
     * 设置/清除 {@code GuiGraphics.renderItem} GUI 渲染标记。
     *
     * @param in true = 进入 GUI 物品渲染(描边恒用自定义 shader,不按光影 fallback)
     */
    public static void markGuiRender(boolean in) {
        IN_GUI_RENDER.set(in);
    }

    /**
     * 查询当前是否处于 {@code GuiGraphics.renderItem} GUI 渲染上下文。
     *
     * @return true = GUI 屏幕空间渲染(不经 Iris,恒自定义 shader)
     */
    public static boolean isInGuiRender() {
        return IN_GUI_RENDER.get();
    }

    /** 全亮光照(GUI 物品标准光照)。 */
    static final int FULL_BRIGHT = 0xF000F0;

    /**
     * 厚度缩放系数。配置里的 thickness 只是"名义像素",实际偏移 = thickness × 系数。
     * <p>
     * 为什么小于 1:描边画的是完整 alpha 遮罩,物品贴图自带的边缘渐变/抗锯齿像素
     * 也会被算进描边,视觉宽度 ≈ 偏移量 + 约 1px 贴图边缘;再加上 8 方向中的对角
     * 偏移距离是 √2 倍且拐角处叠加,1 像素名义偏移的视觉环宽会接近 2px。
     * 0.5 系数让 thickness=1 变成半像素细线,thickness=8 仍有 4px 的粗描边余地。
     */
    static final float THICKNESS_SCALE = 0.5f;

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
    static final float ARMOR_INFLATE_PER_THICKNESS = 0.04f;

    /**
     * 投掷物实体描边放大增量。三叉戟实体模型 pole 仅 25px(=1.56 单位)高、宽 1px,
     * 是又细又长的物体:沿用 ARMOR 系数时外扩量按半对角线(≈0.78)计算,
     * thickness=2 下 scale-1 仅 0.04,视觉几乎不可见。取更大的专用系数,
     * 让投掷物在远处也能看到清晰的描边。
     */
    private static final float PROJECTILE_INFLATE_PER_THICKNESS = 0.12f;

    /** 盔甲参考半对角线(模型单位):原版胸甲 body cube(8×12×4 像素)→ √(8²+12²+4²)/2/16 ≈ 0.468。 */
    private static final float ARMOR_REF_DIAG = 0.468f;


    /**
     * ⚠️ 鞘翅描边<b>与盔甲共用同一系数与参考对角线</b>(v0.1.7 修正)。
     * <p>
     * 此前鞘翅用独立系数 0.09 + refDiag 0.701,外扩像素 = thickness×0.5×0.09×
     * 0.701×16 ≈ thickness×0.504 → armorThickness=8 时约 4px,是盔甲(约 1.2px)
     * 的 3 倍多,同一配置下鞘翅明显更宽(实测反馈)。修正:鞘翅直接复用
     * {@link #ARMOR_INFLATE_PER_THICKNESS} 与 {@link #ARMOR_REF_DIAG},均匀外扩后
     * 两者外扩像素完全一致 —— 同一个 armorThickness 统一控制盔甲与鞘翅视觉厚度。
     */

    /**
     * 4 个对角偏移方向(扁平描边专用,2026-10-02 统合自 Enchantment Glint Outline
     * 的 thickenQuad 思路,替代旧 8 方向;扁平物品与 v2.1 平坦捕获几何共用)。
     * <p>
     * <b>为什么 4 对角就够:</b>对角拷贝(位移 (±t,±t))在任一轴上的范围
     * [min+t, max+t] 与基向拷贝(位移 (0,±t))的 [min, max]+t <b>完全一致</b>
     * → 环的外边界在边中点方向不变;且 t ≤ 面片半径时对角拷贝覆盖基向拷贝的
     * 全部覆盖区 —— 基向 4 拷贝<b>纯冗余</b>。删掉后:绘制量减半(GUI 每物品每帧
     * 8→4 拷贝),且边中点区域从"对角+基向双重绘制(alpha 叠加变浓)"变为单次
     * 覆盖 → alpha 更均匀。
     * <p>
     * ⚠️ 已知限制:极端情况 t > 面片半径(超大厚度 × 极小物品)会在 4 个正方向
     * 出现细缝 —— 接受(视觉为环轻微变细,非错位;标准扁平路径 t ≤ 0.25 模型单位
     * 而面片半径 ≥ 0.5,恒安全)。
     */
    static final float[][] DIAG_OFFSETS = {
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * 外扩面的法线 ε 推挤量(模型单位,2026-10-02 统合自 Enchantment Glint Outline
     * 的 growVert):外扩顶点在正常外扩之外额外沿法线推 1e-4,保证描边面与本体表面
     * <b>永不共面</b>。本架构描边恒先画、本体后画覆盖(COLOR_WRITE 不写深度),
     * 正常情况无 z-fighting;ε 推挤保护边界情况(光影 fallback 渲染顺序差异、
     * 捕获拷贝与本体共面等),零视觉影响(1e-4 模型单位 ≈ 0.0016px)。
     */
    static final float NORMAL_EPSILON = 1e-4f;

    /** 由 RegisterShadersEvent 的 onLoaded 回调写入(F3+T 重载时自动更新)。 */
    volatile ShaderInstance outlineShader;

    /**
     * 描边着色器的 alpha 强化 Uniform 引用(缓存,避免每帧每格重复字符串查找)。
     * 资源重载(F3+T)时随 {@link #setOutlineShader} 一起更新。
     */
    private volatile Uniform outlineAlphaBoostUniform;

    /** 描边着色器的硬切不透明 Uniform 引用(缓存,避免每帧每格重复字符串查找)。 */
    private volatile Uniform outlineCutoutUniform;

    /**
     * 描边着色器的"混色来源" Uniform 引用(缓存,避免每帧每格重复字符串查找)。
     * 扁平"形状纹理"路径与盔甲混色路径需在顶点色之外混入贴图 RGB,
     * 而 3D 几何壳路径颜色全在顶点色里 —— 用该 uniform 区分(见 fsh)。
     */
    private volatile Uniform outlineBlendUniform;

    /**
     * 描边专用缓冲源。独立于 GuiGraphics.bufferSource(),渲染完立即 endBatch →
     * 描边一定在物品本体之前绘制,不依赖共享 BufferSource 的遍历顺序。
     */
    final MultiBufferSource.BufferSource outlineBuffers;

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

    /** GUI/手持扁平描边的"形状纹理"RenderType 缓存(按纹理 location)。 */
    private final Map<ResourceLocation, RenderType> guiShapeRenderTypes = new HashMap<>();

    /**
     * "描边色形状纹理"缓存:扁平物品在光影兼容下关闭混色时,为每个 (物品贴图, 描边色)
     * 生成一张 RGB=描边色、A=贴图 alpha 的动态纹理,让描边既保留物品形状又是纯描边色。
     * 纹理注册到 TextureManager(资源重载时自动销毁),本缓存随之清空重建。
     */
    private final Map<ShapeKey, ResourceLocation> shapeTextures = new HashMap<>();

    /**
     * 序列图(mcata 动画贴图)描边采样缓存(BUG1 修复,2026-09-25):
     * 原纹理 → 首帧烘焙动态纹理 location;非序列图缓存自映射。GPU 采样模式
     * (emissive/自定义 shader)经 {@link #animatedSampleTexture} 统一替换,
     * 防止把"整张 sheet 的全帧并集"画成描边巨块。随 invalidateCaches 清理。
     */
    private final Map<ResourceLocation, ResourceLocation> animFrameTextures = new HashMap<>();

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

    /** 形状纹理缓存键:物品贴图 + 描边色(RGB) + 是否压暗曝光 + 是否混物体贴图色(开关切换时缓存失效)。 */
    private record ShapeKey(ResourceLocation spriteName, int color, boolean reduceExposure, boolean mix) {
    }

    /**
     * "纹理是否全透明"(无任何 alpha&gt;0 像素)检测缓存(按纹理 location)。
     * <p>
     * 用途:盔甲/鞘翅/投掷物描边采样纹理 alpha 做遮罩,若纹理<b>完全透明</b>(如真菌感染
     * Spore 的 SporeBaseArmor.getArmorTexture 恒返回 spore:textures/entity/empty.png),
     * 描边壳整体透明不可见 → {@link #armorOutlineRenderType} 把采样源回退为
     * {@link #WHITE_TEXTURE}(纯几何壳)。检测结果与帧无关,按 location 缓存避免每帧读 PNG;
     * 资源重载(F3+T)时随 {@link #invalidateCaches} 清空重建。
     */
    private final Map<ResourceLocation, Boolean> transparentTextureCache = new HashMap<>();

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
    static final ResourceLocation WHITE_TEXTURE =
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
        // ⚠️ GUI 渲染不经光影,始终用自定义 shader:背包/容器/快捷栏等 GuiGraphics
        // 物品渲染期间,即使光影激活也绝不 fallback —— fallback 的 emissive RenderType
        // 带 LIGHTMAP/OVERLAY 状态(为世界渲染设计),在 GUI 屏幕空间会把轮廓渲染成
        // 淡灰色(2026-08-31 事故:方块走 handOutlineRenderType 恒自定义,扁平物品
        // renderFlatOutlineShape 内部查本方法 → 物品灰、方块正常)。GUI 期间直接返回
        // false 且<b>不更新</b> 500ms 缓存(避免污染随后世界渲染的光影判断)。
        if (isInGuiRender()) {
            return false;
        }
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
        this.outlineBlendUniform = shader != null ? shader.getUniform("OutlineBlend") : null;
        // 资源重载(F3+T 触发 RegisterShadersEvent)时,TextureManager 已销毁全部注册纹理,
        // 全部缓存(形状纹理/亮度/RenderType/近似模型)一并失效 → 清空以便按需重建
        // (AGENTS.md #7:缓存不随资源重载清理会导致"退出服务器重进渲染错误"等脏状态)。
        invalidateCaches();
    }

    /**
     * 清空全部渲染缓存,供<b>资源重载</b>与<b>加入/离开世界</b>时调用。
     * <p>
     * 多人游戏"退出后重进渲染严重错误"(issue #1)根因:进入服务器下载/应用资源包会触发
     * 资源重载,TextureManager 销毁全部动态纹理,而本渲染器的 RenderType 缓存
     * ({@link #worldOutlineRenderTypes}/{@link #armorRenderTypes} 等)与近似模型缓存
     * ({@link #shieldModel}/{@link #tridentModel})从不清理 → 旧缓存引用已失效的资源,
     * 描边渲染成 missing 纹理/错误状态;进单人世界恰好触发 RegisterShadersEvent 清理
     * 一部分缓存才"恢复"。修复:统一入口,资源重载与进出世界都清空。
     * <ul>
     *   <li>形状纹理/亮度缓存:纹理文件变更后重算;</li>
     *   <li>RenderType 缓存:纹理位置可能指向已销毁的动态纹理,重建引用新纹理;</li>
     *   <li>盾牌/三叉戟近似模型:持有旧 transforms/旧 atlas sprite 引用,重建。</li>
     * </ul>
     */
    public void invalidateCaches() {
        this.shapeTextures.clear();
        this.animFrameTextures.clear(); // BUG1:序列图首帧烘焙纹理(动态纹理随重载失效)
        this.lumaCache.clear();
        this.worldOutlineRenderTypes.clear();
        this.armorRenderTypes.clear();
        this.guiShapeRenderTypes.clear();
        this.handOutlineRenderType = null;
        this.shieldModel = null;
        this.shieldTransforms = null;
        this.tridentModel = null;
        this.tridentTransforms = null;
        this.modelGeometryCache.clear();
        this.lionfishOutline.invalidate(); // Lionfish 静态几何缓存(WeakHashMap,模型换实例自动回收)
        this.geoOutline.invalidate();      // Geo 静态几何/模型解析/盔甲检测缓存
        this.transparentTextureCache.clear();
    }

    /**
     * 描边批次安全兜底 flush(供各 mixin 顶层 finally 调用,批次闭合铁律)。
     * <p>
     * <b>事故背景(2026-09-25,"头顶巨大模型"根因之一)</b>:描边各路径
     * {@code getBuffer} 写入顶点后,若中途异常被顶层 {@code catch (Throwable ignored)}
     * 吞掉且无人 endBatch,半写脏批会滞留在共享 builder 里;下一次任意描边路径
     * {@code getBuffer} 到<b>不同</b> RenderType 时,该批被当场以<b>当时的 GL 状态/
     * 坐标系</b>画出(或同 RenderType 时被续写合并)—— 手持局部空间的顶点在世界
     * 渲染阶段上屏,表现为"巨大模型出现在错误位置"。所有 mixin 顶层入口在 finally
     * 里调用本方法,保证无论内部哪条路径异常/提前退出,描边缓冲都不跨渲染阶段滞留。
     * <p>
     * 正常路径已 endBatch 后再调用是空批 no-op(空批 flush 不产生绘制),零视觉影响。
     */
    public void flushOutlineBatch() {
        try {
            this.outlineBuffers.endBatch();
        } catch (Throwable ignored) {
            // flush 自身失败也绝不影响本体渲染
        }
    }

    /**
     * 设置描边 alpha 强化倍率(OutlineAlphaBoost uniform)。
     * <p>
     * 描边画的是物品贴图的 alpha 遮罩,贴图边缘自带抗锯齿渐变(alpha&lt;1);
     * 手持描边在远处场景背景下会显得半透明,抬高倍率让描边更实心。
     * GUI 传 1.0(保持原样),手持传 2.0。
     */
    void setOutlineAlphaBoost(float boost) {
        ShaderInstance shader = this.outlineShader;
        if (shader == null) {
            return;
        }
        // 各渲染路径默认半透明 → 重置 cutout;需要不透明的路径(物品展示框)在 boost
        // 之后再用 setOutlineCutout(true) 覆盖(见 renderHandOutline 6 参重载)。
        setOutlineCutout(false);
        // 默认不混入贴图 RGB:3D 几何壳颜色全在顶点色里;扁平形状纹理/盔甲混色路径
        // 在各自绘制前用 setOutlineBlend(1) 覆盖。
        setOutlineBlend(0.0f);
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
     * 设置描边颜色是否混入贴图 RGB(OutlineBlend uniform)。
     * <p>
     * fsh 的颜色公式:{@code vertexColor.rgb × mix(1, texel.rgb, OutlineBlend)}:
     * <ul>
     *   <li>{@code blend=0}(默认):纯描边色 —— 3D 几何壳路径的颜色全在顶点色里
     *       {@code emitVertexNormalExpand} / {@code renderGui3dInflate} 等;</li>
     *   <li>{@code blend=1}:混入贴图 RGB —— 扁平"形状纹理"把描边色烘焙进贴图
     *       (顶点色为白 → 结果 = 描边色,修复 GUI/手持扁平描边被渲染成<b>白色</b>
     *       的事故);以及盔甲混色开({@link Config#ARMOR_PIXEL_COLOR_GLINT})时
     *       描边色 × 盔甲纹理色混合。</li>
     * </ul>
     * ⚠️ uniform 是 shader 程序级全局状态,必须在<b>该批顶点 flush 之前</b>设置、
     * 且各绘制路径在 setOutlineAlphaBoost 里已默认重置为 0,路径内再按需覆盖;
     * 每个绘制都在 endBatch 收尾,不存在 3D/扁平跨批污染。
     *
     * @param blend 0 = 纯描边色(默认);1 = 混入贴图 RGB
     */
    private void setOutlineBlend(float blend) {
        Uniform uniform = this.outlineBlendUniform;
        if (uniform != null) {
            uniform.set(blend);
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
            // 基础矩阵链(scale + display transform + 居中)与方向无关 → 只应用一次。
            // ⚠️ 用形状纹理路径(renderFlatOutlineShape),不依赖模型 UV 采样 BLOCK_SHEET
            // —— 模组扁平模型 UV 常为 [0,1] 全图,旧 emitQuad 采样越界 → 平面正方形
            // (2026-08-16 事故,见 renderFlatOutlineShape 注释)。
            // ⚠️ GUI 是屏幕空间渲染,不经 Iris 光影(见 needVanillaShaderFallback 注释),
            // 由 GuiGraphicsMixin 设置的 IN_GUI_RENDER 标记保证本路径恒用自定义 shader——
            // 若按光影状态切 emissive,开光影时背包扁平物品轮廓会变淡灰(2026-08-31
            // 事故:方块走 handOutlineRenderType 恒自定义不受影响,扁平物品在此被错误
            // fallback)。
            pose.pushPose();
            try {
                pose.translate(x + 8, y + 8, z);
                pose.scale(16.0F, -16.0F, 16.0F);
                model.getTransforms().getTransform(ItemDisplayContext.GUI).apply(false, pose);
                pose.translate(-0.5F, -0.5F, -0.5F);
                renderFlatOutlineShape(geo, pose, color, thickness);
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

    /** 顶点位置 key(用原始 float 位做 equals/hashCode,精确去重共享顶点;捕获路径共用)。 */
    record Position(float x, float y, float z) {
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
        // ε 推挤(见 NORMAL_EPSILON):正常外扩之外额外沿法线推 1e-4,描边面与本体
        // 表面永不共面(防 z-fighting 边界情况)
        float off = offset + NORMAL_EPSILON;
        List<ExpandVertex[]> quadVertices = geo.expandVertices;
        List<Vec3i> quadNormals = geo.expandNormals;
        for (int qi = 0; qi < quadVertices.size(); qi++) {
            Vec3i n = quadNormals.get(qi);
            for (ExpandVertex v : quadVertices.get(qi)) {
                Vector3f p = poseMatrix.transformPosition(
                        v.x() + v.nx() * off, v.y() + v.ny() * off, v.z() + v.nz() * off, tmp);
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
            renderFlatOutlineShape(geo, pose, color, thickness);
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
                boolean pureWhite = geo.allStone || !Config.ITEM_PIXEL_COLOR_GLINT.get()
                        || ColorResolver.isSolidOutline(currentOutlinedItem());
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
            // 扁平物品(剑/工具等):8 方向平移 + 形状纹理(不依赖模型 UV)。
            // ⚠️ 旧实现 emitQuad + 模型 UV 采样 BLOCK_SHEET,模组扁平模型 UV 常为 [0,1]
            // 全图 → 采样越界 → 平面正方形(2026-08-16 事故,见 renderFlatOutlineShape)。
            renderFlatOutlineShape(geo, pose, color, thickness);
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
     * 扁平物品(GUI/手持)描边统一入口:8 方向平移 + <b>形状纹理</b>(CPU 读贴图 alpha),
     * <b>不依赖模型 UV 采样 BLOCK_SHEET</b>。
     * <p>
     * ⚠️ <b>为什么必须用形状纹理而不是模型 UV(2026-08-16 事故):</b>扁平描边旧实现用
     * {@link #emitQuad} 直接把<b>模型 UV</b> 交给着色器采样 BLOCK_SHEET。原版扁平物品
     * (剑/工具)烘焙后的 quad UV 是精灵在 atlas 的<b>绝对 UV</b>(模型生成器写入),
     * 采样正确 → 剑形轮廓;但<b>模组扁平模型</b>的 UV 常是 [0,1] 全图(或自定义值),
     * 采样 BLOCK_SHEET 越出精灵区域 → 整块 quad 全不透明 → <b>描边变成平面正方形</b>。
     * 修复:一律改走 {@link #shapeTextureFor} 形状纹理(alpha=贴图 alpha)+
     * {@link #emitQuadShape}(精灵相对 UV 0..1)渲染,形状完全由贴图 alpha 决定,
     * 与模型 UV 无关 → 任意模组扁平物品都遵循轮廓。
     * <ul>
     *   <li>无光影:自定义描边 shader + 形状纹理({@link #guiOutlineRenderType});</li>
     *   <li>光影 fallback(仅世界渲染):内置 emissive + 形状纹理({@link #worldEmissiveOutlineRenderType})。</li>
     * </ul>
     * 颜色恒为<b>纯描边色</b>(形状纹理 RGB 已烘焙) —— 这是摆脱模型 UV 依赖的必然代价
     * (不采样 BLOCK_SHEET,任意模组扁平物品轮廓完整)。
     * <p>
     * <b>不开光影强制纯色(2026-10-02 用户决策,覆盖全部算法)</b>:
     * <ul>
     *   <li>不开光影(自定义 shader):mix 恒 false —— 形状纹理烘<b>纯描边色</b>,
     *       无视混色开关(GUI 与世界一致,色相统一);</li>
     *   <li>开光影(兼容模式):GUI 外混色依据 {@link Config#ITEM_PIXEL_COLOR_GLINT}
     *       (开启时烘焙 <b>描边色 × 贴图像素色</b>,贴合物品本体颜色调制;关闭时纯色),
     *       GUI 内仍恒纯色(2026-09-06 约定保留)。</li>
     * </ul>
     *
     * @param geo      已收集的模型几何(bySprite / 法线 / quad 索引缓存)
     * @param pose     已居中(translate(-0.5))且已应用 GUI/display 变换的 PoseStack
     * @param color    ARGB 描边色
     * @param thickness 描边厚度(像素语义)
     */
    private void renderFlatOutlineShape(ModelGeometry geo, PoseStack pose, int color, float thickness) {
        float t = thickness * THICKNESS_SCALE / 16.0f;
        Map<TextureAtlasSprite, List<BakedQuad>> bySprite = geo.bySprite;
        Map<TextureAtlasSprite, int[]> spriteQuadIndices = geo.spriteQuadIndices;
        List<Vec3i> geoNormals = geo.expandNormals;
        Vector3f tmp = new Vector3f();
        // 形状纹理烘焙颜色模式(GUI 内恒纯色,与方块一致;GUI 外按混色配置烘焙
        // "描边色×贴图像素色",贴合物品本体颜色调制)。GUI 由 GuiGraphicsMixin 的
        // IN_GUI_RENDER 标记保证。
        // 不开光影强制纯色(2026-10-02 用户决策,覆盖全部算法):mix 含
        // needVanillaShaderFallback 门禁 —— 自定义 shader 路径恒烘纯描边色,
        // 无视混色开关;开光影时 GUI 外混色依据配置(GUI 内恒纯色,2026-09-06 约定)。
        // 两种模式各自烘焙(ShapeKey 已含 mix 维度),切换光影自动换纹理。
        boolean mix = needVanillaShaderFallback() && !isInGuiRender() && Config.ITEM_PIXEL_COLOR_GLINT.get()
                && !ColorResolver.isSolidOutline(currentOutlinedItem());
        // 形状纹理烘焙了描边色(顶点色为白,见 emitQuadShape),自定义 shader 必须
        // 混入 texel.rgb 才能得到描边色,否则输出白色(2026-08-31 事故)。
        // 光影 fallback 用内置 emissive(texel×vertex) 自动取到烘焙色,此设置仅对
        // 自定义 shader 生效,统一在此设置。
        setOutlineBlend(1.0f);
        for (Map.Entry<TextureAtlasSprite, List<BakedQuad>> entry : bySprite.entrySet()) {
            TextureAtlasSprite sprite = entry.getKey();
            ResourceLocation tex = shapeTextureFor(sprite, color, false, mix);
            RenderType rt = needVanillaShaderFallback()
                    ? worldEmissiveOutlineRenderType(tex)
                    : guiOutlineRenderType(tex);
            VertexConsumer consumer = outlineBuffers.getBuffer(rt);
            List<BakedQuad> spriteQuads = entry.getValue();
            int[] quadIndices = spriteQuadIndices.get(sprite);
            for (float[] off : DIAG_OFFSETS) {
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
     * GUI/手持扁平描边的"形状纹理" RenderType:自定义描边 shader + 指定形状纹理。
     * 无光影时用(有光影时走内置 emissive 的 {@link #worldEmissiveOutlineRenderType})。
     */
    private RenderType guiOutlineRenderType(ResourceLocation tex) {
        RenderType type = this.guiShapeRenderTypes.get(tex);
        if (type == null) {
            type = RenderType.create("enchanted_outlines_gui_shape",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> this.outlineShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                            .createCompositeState(false));
            this.guiShapeRenderTypes.put(tex, type);
        }
        return type;
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
     * @param reduce true = 按源贴图亮度压暗纯色描边(仅 {@link Config.OUTLINE_EXPOSURE_REDUCE});
     *               false = 恒为纯描边色(与方块/3D 几何路径一致,不再压暗)
     * @param mix    true = 烘焙 <b>描边色 × 贴图像素色</b>(GUI 外混物体贴图色,见
     *               {@link #renderFlatOutlineShape});false = 烘焙纯描边色(GUI 内)
     * @return 已注册的纹理 location
     */
    private ResourceLocation shapeTexture(ResourceLocation source, NativeImage src, int color, boolean reduce, boolean mix) {
        int rgb = color & 0xFFFFFF;
        ShapeKey key = new ShapeKey(source, rgb, reduce, mix);
        ResourceLocation loc = this.shapeTextures.get(key);
        if (loc == null) {
            int w = src.getWidth(), h = src.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            // 关闭混色时描边为纯色,emissive 全亮下亮色(粉/金/白)会过曝刺眼。
            // 按源贴图的平均感知亮度压暗 RGB(色相不变):暗色物品(铁剑等)的描边
            // 显著变暗、亮色物品基本不变 —— 模拟"混合算法根据物体颜色降低曝光"。
            // 同一贴图不同描边色会各生成一张形状纹理,亮度按 source 缓存避免重复遍历。
            float scale = reduce ? exposureScale(lumaOf(source, src)) : 1.0f;
            int baseR = (rgb >> 16) & 0xFF, baseG = (rgb >> 8) & 0xFF, baseB = rgb & 0xFF;
            int r = (int) (baseR * scale), g = (int) (baseG * scale), b = (int) (baseB * scale);
            try {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // ⚠️ NativeImage RGBA 内存小端:getPixelRGBA 返回 ABGR 打包,
                        // alpha 在最高字节,R/G/B 依次在低 3 字节;setPixelRGBA 写入
                        // (alpha<<24)|(b<<16)|(g<<8)|r。通道顺序写错会弄乱形状/颜色。
                        int abgr = src.getPixelRGBA(x, y);
                        int a = (abgr >>> 24) & 0xFF;
                        if (mix) {
                            // GUI 外混物体贴图色:描边 = 描边色 × 物品本体像素色。
                            // 既保留物品形状,描边色又随物品明暗,视觉上贴合本体。
                            int pr = abgr & 0xFF, pg = (abgr >> 8) & 0xFF, pb = (abgr >> 16) & 0xFF;
                            int mr = (baseR * pr) / 255, mg = (baseG * pg) / 255, mb = (baseB * pb) / 255;
                            img.setPixelRGBA(x, y, (a << 24) | (mb << 16) | (mg << 8) | mr);
                        } else {
                            img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                        }
                    }
                }
            } catch (Exception e) {
                // 源贴图格式不支持像素读取(如 RGB 无 alpha)→ 无形状信息,回退纯色矩形。
                return WHITE_TEXTURE;
            }
            loc = ResourceLocation.fromNamespaceAndPath("enchanted_outlines",
                    "shape/" + source.getNamespace() + "/"
                            + source.getPath().replace('/', '_') + "_"
                            + Integer.toHexString(rgb) + (mix ? "_mix" : ""));
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            this.shapeTextures.put(key, loc);
        }
        return loc;
    }

    /**
     * 扁平物品版形状纹理:从 atlas 精灵取原图像素后走统一入口 {@link #shapeTexture}。
     *
     * @param sprite  物品贴图(atlas 精灵)
     * @param color   ARGB 描边色
     * @param reduce  true = 按源贴图亮度压暗纯色描边(见 {@link #shapeTexture})
     * @param mix     true = 烘焙"描边色 × 贴图像素色"(GUI 外混物体贴图色)
     * @return 已注册的纹理 location;拿不到像素时回退纯白矩形
     */
    private ResourceLocation shapeTextureFor(TextureAtlasSprite sprite, int color, boolean reduce, boolean mix) {
        NativeImage src = spriteOriginalImage(sprite);
        if (src == null) {
            // spriteOriginalImage 反射 originalImage 失败 → 回退 WHITE_TEXTURE(1×1 不透明)
            // → 8 方向平移画出整块矩形。本体直接渲染贴图(不依赖 originalImage)所以正常。
            return WHITE_TEXTURE;
        }
        return shapeTexture(sprite.contents().name(),
                cropToFrame0(src, sprite.contents().width(), sprite.contents().height()),
                color, reduce, mix);
    }

    /**
     * mcmeta 感知的独立纹理读取(BUG1 修复,2026-09-25)。
     * <p>
     * <b>事故</b>:带 {@code .png.mcmeta} 动画声明的"序列图"贴图(一张图纵向平铺全部帧,
     * 如 Hazen 火山拳 64×320 = 5 帧,geo 模型的 UV 空间只有单帧 64×64)—— 本体渲染时
     * GPU 采样的是逐帧轮换的活纹理/模组自管帧,而 CPU 端 {@code ImageIO} 读文件读到的是
     * <b>整张 sheet</b>,直接做形状会把全部帧的 alpha 并集烘焙进去 → "描边渲染了整张
     * 序列图"。本方法统一入口:ImageIO 读取(铁律 1,palette+tRNS 展开)+ 序列图裁首帧。
     *
     * @param texture 独立纹理 location
     * @return 首帧图像;资源缺失/读取失败返回 null(调用方自行降级,禁止退 WHITE)。
     *         失败原因经 {@link #logTextureReadFailure} 节流留痕(2026-10-10 Hazen
     *         平面描边根因排查引入:此前静默吞掉,无法定位根因)
     */
    private static java.awt.image.BufferedImage readTextureFrame(ResourceLocation texture) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                logTextureReadFailure(texture, "resource-missing");
                return null;
            }
            java.awt.image.BufferedImage bi;
            try (InputStream in = resource.get().open()) {
                // ⚠️ 铁律 1:必须用 ImageIO 而非 NativeImage.read —— palette(索引色)+
                // tRNS 透明 PNG 被 NativeImage.read 读取会丢 alpha → 描边变实心(2026-08-08 事故)。
                bi = javax.imageio.ImageIO.read(in);
            }
            if (bi == null) {
                logTextureReadFailure(texture, "imageio-null(格式不被 JDK ImageIO 识别)");
                return null;
            }
            return cropAnimationFrame0(bi, texture);
        } catch (Exception e) {
            logTextureReadFailure(texture, "exception:" + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? "(" + e.getMessage() + ")" : ""));
            return null;
        }
    }

    /** 纹理读取失败节流日志时间戳(常驻诊断,非渲染缓存,不参与 invalidateCaches)。 */
    private static final Map<String, Long> TEX_READ_FAIL_LOG_THROTTLE = new HashMap<>();

    /**
     * 纹理读取失败的节流诊断日志(常驻诊断,同 {@code BewlrCapture.logCaptureReject}
     * 模式:10 秒/原因#纹理 一次,info 级,低频不刷屏 —— 铁律 6 的合法例外)。
     * <p>
     * 用户报障直接附 latest.log 即可定位是哪个纹理、哪种失败原因
     * (资源缺失/ImageIO 不识别/异常),再做精准根修。
     *
     * @param texture 读取失败的纹理 location
     * @param reason  失败原因描述
     */
    private static void logTextureReadFailure(ResourceLocation texture, String reason) {
        String key = reason + "#" + texture;
        long now = System.currentTimeMillis();
        Long last = TEX_READ_FAIL_LOG_THROTTLE.put(key, now);
        if (last != null && now - last < 10_000L) {
            return; // 10 秒窗口内同原因同纹理只打印一次
        }
        EnchantedOutlines.LOGGER.info(
                "[Enchanted Outlines] 纹理读取失败(描边降级为 GPU 采样原纹理): 原因={} 纹理={}",
                reason, texture);
    }

    /**
     * 若纹理带 mcmeta {@code animation} 声明,裁出首帧区域;否则原样返回。
     * <p>
     * 帧尺寸:mcmeta 可显式声明 {@code width}/{@code height},缺省为"方帧"(帧高=图宽,
     * vanilla 规则);帧序取 {@code frames[0]}(整数或 {index,time} 对象),缺省顺序首帧行 0。
     * 解析失败一律原样返回(行为与修复前一致,保守)。
     *
     * @param bi      ImageIO 读出的原始图像(可能为整张序列图)
     * @param texture 纹理 location(用于查同名 .png.mcmeta)
     * @return 首帧子图;非动画/解析失败返回原图
     */
    private static java.awt.image.BufferedImage cropAnimationFrame0(java.awt.image.BufferedImage bi, ResourceLocation texture) {
        try {
            var meta = Minecraft.getInstance().getResourceManager().getResource(
                    ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), texture.getPath() + ".mcmeta"));
            if (meta.isEmpty()) {
                return bi; // 无动画声明:普通贴图,原样
            }
            String json;
            try (InputStream in = meta.get().open()) {
                json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("animation")) {
                return bi;
            }
            com.google.gson.JsonObject anim = root.getAsJsonObject("animation");
            int fw = anim.has("width") ? anim.get("width").getAsInt() : bi.getWidth();
            int fh = anim.has("height") ? anim.get("height").getAsInt() : bi.getWidth();
            if (fw <= 0 || fh <= 0 || fw > bi.getWidth() || fh > bi.getHeight()) {
                return bi; // 声明异常:保守原样
            }
            int frameIndex = 0; // frames[0](缺省顺序帧 0)
            if (anim.has("frames") && anim.get("frames").isJsonArray()
                    && !anim.get("frames").getAsJsonArray().isEmpty()) {
                com.google.gson.JsonElement f0 = anim.get("frames").getAsJsonArray().get(0);
                if (f0.isJsonObject() && f0.getAsJsonObject().has("index")) {
                    frameIndex = f0.getAsJsonObject().get("index").getAsInt();
                } else if (f0.isJsonPrimitive()) {
                    frameIndex = f0.getAsInt();
                }
            }
            int y0 = frameIndex * fh;
            if (y0 + fh > bi.getHeight()) {
                y0 = 0; // 越界回退首行
            }
            return bi.getSubimage(0, y0, fw, fh);
        } catch (Exception ignored) {
            return bi;
        }
    }

    /**
     * 序列图描边采样统一入口(BUG1 修复,2026-09-25):mcmeta 动画纹理的 GPU 端注册对象
     * 是<b>整张 sheet</b>(模组自管逐帧动画,如 AzureLib AzItemRenderer),描边直接采样
     * 会画成全帧并集巨块。这里把序列图换成<b>本模组烘焙的首帧动态纹理</b>(原始 RGB 保留,
     * 供 emissive/自定义 shader 两种 GPU 模式采样);非序列图原样返回(零开销)。
     * <p>
     * ⚠️ 首帧是静态近似:描边不随本体帧动画(烘焙纹理不轮帧)。帧间轮廓相近的武器
     * (动画多为发光/特效变化)视觉可接受;比整张 sheet 巨块正确得多。
     * <p>
     * 缓存经 {@link #invalidateCaches} 清理(TextureManager 重载后动态纹理失效)。
     *
     * @param texture 原纹理 location
     * @return 非序列图 = 原样;序列图 = 已注册的首帧烘焙纹理 location
     */
    /**
     * 序列图描边采样统一入口(BUG1 修复,2026-09-26 修订为"按活性分流")。
     * <p>
     * mcmeta 动画纹理(一张图平铺全部帧)的模型 UV 空间只有单帧。两种注册形态:
     * <ul>
     *   <li><b>AzureLib AnimatableTexture(活动画)</b>:渲染管线每帧
     *       {@code setAndUpdate} 推进 GPU 帧内容 → <b>绑原 location</b>,描边采样
     *       当前帧,形状/颜色与本体动画<b>同步</b>(2026-09-26 用户实测:一律烘帧 0
     *       会让描边静止、与本体动画脱节);本判定调用 getTexture 还会触发
     *       AzureLib TextureManagerMixin 建立动画纹理,顺带治愈本体注册;</li>
     *   <li><b>普通注册(无 mixin 推帧的 SimpleTexture = 整张 sheet)</b> → 绑本模组
     *       烘焙的<b>首帧纹理</b>(静态近似,形状正确)—— 不绑原 location,否则
     *       采样整张 sheet 画成全帧并集巨块(2026-09-25 BUG1 事故)。</li>
     * </ul>
     * 非序列图原样返回(零开销)。缓存经 {@link #invalidateCaches} 清理。
     *
     * @param texture 原纹理 location
     * @return 序列图:活动画 = 原样(采样实时帧) / 死注册 = 首帧烘焙纹理;非序列图 = 原样
     */
    private ResourceLocation animatedSampleTexture(ResourceLocation texture) {
        if (WHITE_TEXTURE.equals(texture)) {
            return texture;
        }
        ResourceLocation cached = this.animFrameTextures.get(texture);
        if (cached != null) {
            return cached;
        }
        try {
            var meta = Minecraft.getInstance().getResourceManager().getResource(
                    ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), texture.getPath() + ".mcmeta"));
            if (meta.isEmpty()) {
                this.animFrameTextures.put(texture, texture); // 非序列图:缓存自映射
                return texture;
            }
            // 序列图:按注册纹理活性分流(见方法 javadoc)—— 活动画绑原 location(采样实时帧,
            // 与本体动画同步);死注册(SimpleTexture = 整张 sheet)才走下方首帧烘焙。
            var registered = Minecraft.getInstance().getTextureManager().getTexture(texture);
            if (registered != null
                    && registered.getClass().getName().endsWith("AnimatableTexture")) {
                this.animFrameTextures.put(texture, texture); // 活动画:缓存自映射(绑原 location)
                return texture;
            }
            java.awt.image.BufferedImage frame = readTextureFrame(texture); // ImageIO + 裁首帧
            if (frame == null) {
                return texture; // 读失败:不缓存(资源未就绪等临时失败),下次重试
            }
            int w = frame.getWidth(), h = frame.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = frame.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // NativeImage 内存小端 ABGR 打包:alpha 在最高字节
                    img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("enchanted_outlines",
                    "animframe/" + texture.getNamespace() + "/" + texture.getPath().replace('/', '_'));
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            this.animFrameTextures.put(texture, loc);
            return loc;
        } catch (Exception ignored) {
            return texture; // 异常:保持原行为
        }
    }

    /**
     * 把序列图 NativeImage 裁成左上角 (fw×fh) 首帧(atlas 精灵版,BUG1 同源修复):
     * 动画精灵的 contents 宽高 = 帧尺寸,originalImage = 整张 sheet。
     * 尺寸吻合(非动画)原样返回同一实例(调用方据此跳过 close)。
     *
     * @param src 原始图像(可能是整张 sheet)
     * @param fw  帧宽(精灵 contents 宽)
     * @param fh  帧高(精灵 contents 高)
     * @return 首帧图像;非动画返回原实例
     */
    private static NativeImage cropToFrame0(NativeImage src, int fw, int fh) {
        int w = src.getWidth(), h = src.getHeight();
        if (fw <= 0 || fh <= 0 || fw > w || fh > h || (w == fw && h == fh)) {
            return src;
        }
        NativeImage frame = new NativeImage(fw, fh, false);
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                frame.setPixelRGBA(x, y, src.getPixelRGBA(x, y));
            }
        }
        return frame;
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
     * @return 已注册的纹理 location;<b>读取失败返回 null</b>(2026-10-10 Hazen 平面
     *         描边事故:白矩形兜底会让放大壳整体不透明 = 实心平面"方形背景",与
     *         铁律 6.9"贴图提取失败禁止退 WHITE"同源 —— 调用方必须处理 null 并
     *         自行降级,禁止回退 WHITE_TEXTURE)
     */
    private ResourceLocation shapeTextureForLocation(ResourceLocation texture, int color) {
        int rgb = color & 0xFFFFFF;
        boolean reduce = Config.OUTLINE_EXPOSURE_REDUCE.get();
        ShapeKey key = new ShapeKey(texture, rgb, reduce, false);
        ResourceLocation cached = this.shapeTextures.get(key);
        if (cached != null) {
            return cached; // 已生成过:直接复用,不再读 PNG
        }
        try {
            // BUG1(2026-09-25):改经 mcmeta 感知的 readTextureFrame —— ImageIO 读取
            // (铁律 1:palette+tRNS 展开)+ 序列图裁首帧,防"全帧并集"烘焙进形状纹理。
            java.awt.image.BufferedImage bi = readTextureFrame(texture);
            if (bi == null) {
                // 读取失败:返回 null 交调用方降级(禁止退 WHITE —— alpha 全 1 = 实心平面,
                // 2026-10-10 Hazen 平面描边根因);失败原因已由 readTextureFrame 节流留痕
                return null;
            }
            {
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
                ResourceLocation loc = shapeTexture(texture, src, color, reduce, false);
                src.close();
                return loc;
            }
        } catch (Exception ignored) {
            // 读取异常:同上返回 null 交调用方降级(失败原因已节流留痕,见 readTextureFrame)
            return null;
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
            // 所有方向给空列表(可变容器,见 newEmptyCulledFaces 的说明):
            // SimpleBakedModel 对非 null direction 返回 culledFaces.get(dir),
            // 缺方向会返回 null → collectQuads 的 addAll(null) 崩溃
            Map<Direction, List<BakedQuad>> emptyByDir = newEmptyCulledFaces();
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
            // 所有方向给空列表(可变容器,见 newEmptyCulledFaces 的说明)
            Map<Direction, List<BakedQuad>> emptyByDir = newEmptyCulledFaces();
            model = new SimpleBakedModel(quads, emptyByDir,
                    false, false, true, sprite, transforms, ItemOverrides.EMPTY);
            this.tridentModel = model;
            this.tridentTransforms = transforms;
        }
        return model;
    }

    /**
     * 构造 SimpleBakedModel 用的 culledFaces:6 个方向全部映射到空列表,容器可变。
     * <p>
     * 不能用 {@code Map.of()}/{@code List.of()} 的不可变容器:FerriteCore 的
     * {@code minimizeCulled} 在模型构造时会对传入的 culledFaces 调用 {@code put()}
     * (把空列表替换为紧凑共享实例),不可变 Map 会抛 {@link UnsupportedOperationException}
     * (崩溃栈:SimpleBakedModel.&lt;init&gt; → ferritecore$minimizeFaceLists →
     * ModelSidesImpl.minimizeCulled → ImmutableCollections$AbstractImmutableMap.put)。
     *
     * @return 6 个方向均为可变空 ArrayList 的可变 HashMap
     */
    private static Map<Direction, List<BakedQuad>> newEmptyCulledFaces() {
        Map<Direction, List<BakedQuad>> map = new HashMap<>();
        for (Direction dir : Direction.values()) {
            map.put(dir, new ArrayList<>());
        }
        return map;
    }

    /**
     * BEWLR 物品(模型 parent 为 builtin/entity,{@code isCustomRenderer()==true})的
     * <b>GUI/GROUND/FIXED</b> 描边模型解析。
     * <p>
     * 这类物品的占位模型没有几何,本体在这些上下文由模组或原版在
     * {@code ItemRenderer.render} 内替换成<b>平面模型</b>渲染 —— 描边必须用同一个模型
     * 才能与本体对齐。实测(2026-08-09,永恒星光 0.8.1)本体实际使用的 MRL 变体:
     * <ol>
     *   <li>{@code <ns>:item/<id>#standalone}(永恒星光 style:getSpecialModel 对
     *       MRL.id().withPrefix("item/") + variant standalone,如
     *       eternal_starlight:item/crescent_spear_inventory#standalone 是平面模型);</li>
     *   <li>{@code <ns>:item/<id>#inventory};</li>
     *   <li>{@code <ns>:<id>#standalone};</li>
     *   <li>{@code <ns>:<id>_inventory#inventory}(部分模组的约定);</li>
     *   <li>{@code <ns>:<id>#inventory}(原版风格,钓鱼竿 fishing_rod#inventory 就是
     *       handheld 平面)。</li>
     * </ol>
     * 只接受<b>存在、非 missing、非 custom renderer</b> 的模型 —— builtin/entity 占位
     * 模型会被拒绝,无可用平面变体时返回 null 由调用方跳过描边。
     *
     * @param stack 物品;仅当原模型 {@code isCustomRenderer()} 时调用
     * @return 可描边的平面模型;无可用平面变体时返回 null
     */
    public static BakedModel inventoryModelFor(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String ns = id.getNamespace();
        String path = id.getPath();
        ModelManager manager = Minecraft.getInstance().getItemRenderer().getItemModelShaper().getModelManager();
        List<ModelResourceLocation> candidates = List.of(
                // 永恒星光 style:item/<id>_inventory#standalone(本体 GUI 实际渲染用的模型,
                // getSpecialModel 把 <id>_inventory MRL 的 id withPrefix("item/") + standalone)
                new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(ns, "item/" + path + "_inventory"), "standalone"),
                new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(ns, "item/" + path + "_inventory"), "inventory"),
                new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(ns, path + "_inventory"), "standalone"),
                // 通用约定:<id>_inventory#inventory / <id>#standalone / <id>#inventory
                ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(ns, path + "_inventory")),
                new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(ns, path), "standalone"),
                ModelResourceLocation.inventory(id));
        for (ModelResourceLocation mrl : candidates) {
            BakedModel model = manager.getModel(mrl);
            if (isUsableInventoryModel(manager, model)) {
                return model;
            }
        }
        return null;
    }

    /**
     * inventory 模型是否可描边:存在、非 missing 占位、且本身不是 builtin/entity。
     * missing 模型(紫黑方块)没有物品形状,直接用会把描边画成整块 16×16 实心方块。
     */
    private static boolean isUsableInventoryModel(ModelManager manager, BakedModel model) {
        return model != null
                && model != manager.getMissingModel()
                && !model.isCustomRenderer();
    }

    // ==================== BEWLR 手持物品 3D 描边 ====================

    /**
     * BEWLR 手持物品 3D 描边放大系数(每厚度增量)。
     * <p>
     * 3D 描边是几何放大壳(scale = 1 + thickness×0.5×系数),外扩量 =
     * (scale-1)×点到中心距离 → 细长武器多数面离中心近、外扩小,需要比扁平物品
     * (像素偏移)更大的基础系数。旧硬编码 0.12 太弱,现读配置 {@link Config#BEWLR_3D_SCALE}
     * (默认 0.3),玩家可调。
     */
    /** BEWLR 家族子模块实例(拆分自本类,构造时互相持有引用):Lionfish / 通用捕获 / Geo;TACZ 判定为纯静态(TaczOutline)。 */
    private final LionfishOutline lionfishOutline = new LionfishOutline(this);
    private final BewlrCapture bewlrCapture = new BewlrCapture(this);
    private final GeoOutline geoOutline = new GeoOutline(this);

    static float bewlrInflatePerThickness() {
        return Config.BEWLR_3D_SCALE.get().floatValue();
    }

    /**
     * BEWLR 手持物品 3D 描边(月弧长枪/灾变武器等):
     * 本体手持由模组 BEWLR 渲染自定义实体模型,这里反射其持有的模型对象做放大壳。
     * <ul>
     *   <li>标准 Mojang {@link Model} 子类(root 是 {@link ModelPart},如永恒星光
     *       CrescentSpearModel)→ 复用 {@link #renderEntityModelOutline} 逐 cube 放大壳;</li>
     *   <li>LionfishAPI 骨骼(root 是 AdvancedModelBox,如灾变)→ 整体包围盒放大壳
     *       ({@link LionfishOutline#renderInflated})。</li>
     * </ul>
     * 无可用模型时返回 false,调用方跳过描边(与修复前行为一致)。
     *
     * @param stack     物品(BEWLR)
     * @param pose      已复刻 display transform 的 PoseStack(与本体同变换)
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     * @return true = 已绘制描边
     */
    public boolean renderBewlrEntityOutline(ItemStack stack, PoseStack pose, int color, float thickness) {
        return renderBewlrEntityOutline(stack, pose, ItemDisplayContext.NONE, color, thickness);
    }

    /**
     * BEWLR 手持物品 3D 描边(月弧长枪/灾变武器等):带显示上下文版本。
     * <p>
     * 与 {@link #renderBewlrEntityOutline(ItemStack, PoseStack, int, float)} 相同,
     * 额外接收 {@link ItemDisplayContext}。捕获({@link BewlrCapture#renderCaptured})为
     * <b>首选</b>(2026-10-02,描边随动画):它先调用模组
     * {@code IClientItemExtensions.getCustomRenderer().renderByItem},让动画状态机
     * (GeckoLib/AzureLib tickAnimation)先应用当帧姿态,再读几何画描边 → 轮廓与本体动画
     * 同步;捕获为空/守卫拒绝时回退反射/Geo 路径(旧行为)。
     *
     * @param stack     物品(BEWLR)
     * @param pose      已复刻 display transform 的 PoseStack(与本体同变换)
     * @param context   当前显示上下文(GUI/手持/GROUND/FIXED;捕获需与本体同 context)
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     * @return true = 已绘制描边
     */
    public boolean renderBewlrEntityOutline(ItemStack stack, PoseStack pose, ItemDisplayContext context,
                                            int color, float thickness) {
        if (thickness <= 0f || this.outlineShader == null) {
            return false;
        }
        // ⚠️ TACZ 专项兼容与 Hazen 全武器兼容(2026-09-06 重启用):命中的命名空间按各自
        // enable*Compat 总开关(粗粒度)控制;若关闭则全局跳过,不再经下方注册表判断。
        // 新模组的 namespace 级控制走 bewlrNamespaceAdapters 注册表(细粒度)。
        boolean hazen = TaczOutline.isHazenNamespace(stack);
        boolean tacz = TaczOutline.isTaczNamespace(stack);
        if ((tacz && !Config.ENABLE_TACZ_COMPAT.get())
                || (hazen && !Config.ENABLE_HAZEN_COMPAT.get())) {
            return false;
        }
        // ⚠️ TACZ 第一人称(2026-09-26):渲染器读相机/内部动画姿态,本注入点(HEAD)必错位
        if (tacz && context.firstPerson()) {
            return false;
        }

        // ⚠️ namespace → adapter 注册表(2026-10-02,② 通用化收官):按配置指定适配器优先级,
        // 新模组出问题改配置即可换路径/关闭,不再改代码。未配置的命名空间(TACZ/Hazen 已有
        // 粗粒度开关的亦在内)走默认 capture 优先链。与 enableTaczCompat/enableHazenCompat
        // 叠加生效 —— 此处之前已检查总开关,注册表只能进一步限制/重排,不复活已关闭的。
        String preferred = null;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null) {
            preferred = Config.namespaceAdapter(itemId.getNamespace());
        }
        if ("off".equals(preferred)) {
            return false;
        }
        if ("legacy".equals(preferred)) {
            // 反射/Geo 直取优先(旧行为,2026-10-02 之前模式);失败仍回退捕获保证有描边
            if (renderResolverFallback(stack, pose, color, thickness)) {
                return true;
            }
            return bewlrCapture.renderCaptured(stack, pose, context, color, thickness);
        }
        // 默认(未配置 / "capture"):捕获优先(2026-10-02 描边随动画)→ 反射/Geo 回退
        if (bewlrCapture.renderCaptured(stack, pose, context, color, thickness)) {
            return true;
        }
        return renderResolverFallback(stack, pose, color, thickness);
    }

    /**
     * 反射/Geo 回退单元(从派发链提取的行为保持分支,2026-10-02 namespace 注册表共用)。
     * <p>
     * 静态字段解析(永恒星光/灾变映射表)→ 命中走标准 Mojang Model 逐 cube 放大壳或
     * LionfishAPI 逐 cube 顶点法线外扩;未命中走 Geo 贴图取样(GeckoLib/AzureLib 物品)。
     * 供派发链的默认回退步骤与 namespace 注册表的 {@code legacy} 优先步骤共用。
     *
     * @param stack     物品(BEWLR)
     * @param pose      已复刻 display transform 的 PoseStack(与本体同变换)
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     * @return true = 已绘制描边;false = 无法解析(捕获已在调用方先尝试,不重复)
     */
    private boolean renderResolverFallback(ItemStack stack, PoseStack pose,
                                           int color, float thickness) {
        BewlrModelResolver.BewlrModel found = BewlrModelResolver.find(stack);
        if (found == null || found.model() == null) {
            // 无静态字段适配(GeckoLib 武器、Hazen 星怒等):Geo 贴图取样
            return geoOutline.renderItem(stack, pose, color, thickness);
        }
        Object model = found.model();
        ResourceLocation tex = found.texture() != null ? found.texture() : WHITE_TEXTURE;
        // 复刻 BEWLR 渲染器内部的模组级预变换(本体在 display 后 pushPose→translate→scale)
        pose.pushPose();
        try {
            BewlrModelResolver.applyBewlrPreTransform(pose, found.preTransform());
            if (model instanceof Model mojang) {
                ModelPart root = BewlrModelResolver.modelRootOf(mojang);
                if (root != null) {
                    renderEntityModelOutline(pose, new ModelPart[]{root}, tex,
                            bewlrInflatePerThickness(), color, thickness);
                    return true;
                }
            }
            if (Config.BEWLR_3D_PER_CUBE.get()) {
                if (lionfishOutline.renderPerCube(pose, model, tex, color, thickness)) {
                    return true;
                }
            }
            if (lionfishOutline.renderInflated(pose, model, tex, color, thickness)) {
                return true;
            }
        } finally {
            pose.popPose();
        }
        return false;
    }

    /** 沿类链向上查找字段(父类声明的私有字段对子类实例同样可读)。 */
    static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** 查找参数匹配的方法(参数为 null 的位不参与匹配)。 */
    static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                boolean match = paramTypes.length == pts.length;
                if (match) {
                    for (int i = 0; i < pts.length; i++) {
                        if (paramTypes[i] != null && !paramTypes[i].isAssignableFrom(pts[i])) {
                            match = false;
                            break;
                        }
                    }
                }
                if (match) {
                    return m;
                }
            }
        }
        return null;
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
            // uniform = 盔甲描边启用固定厚度(Config.ARMOR_UNIFORM_EXPAND);
            // 鞘翅/投掷物等走本方法时传 false,保持原视觉(向后兼容)
            boolean uniform = Config.ARMOR_UNIFORM_EXPAND.get();
            renderPartInflated(pose, model.head, consumer, packedColor, scale, uniform, ARMOR_REF_DIAG);
            renderPartInflated(pose, model.hat, consumer, packedColor, scale, uniform, ARMOR_REF_DIAG);
            renderPartInflated(pose, model.body, consumer, packedColor, scale, uniform, ARMOR_REF_DIAG);
            renderPartInflated(pose, model.rightArm, consumer, packedColor, scale, uniform, ARMOR_REF_DIAG);
            renderPartInflated(pose, model.leftArm, consumer, packedColor, scale, uniform, ARMOR_REF_DIAG);
            renderPartInflated(pose, model.rightLeg, consumer, packedColor, scale, uniform, ARMOR_REF_DIAG);
            renderPartInflated(pose, model.leftLeg, consumer, packedColor, scale, uniform, ARMOR_REF_DIAG);
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
     * <p>
     * <b>固定厚度 uniform=true</b>(盔甲/鞘翅启用,{@link Config#ARMOR_UNIFORM_EXPAND}):
     * 放大壳外扩量 = (scale-1)×cube 半对角线,大 cube 描边厚、模组细分模型
     * (如永恒星光热泉石盔甲/鞘翅翼尖)小 cube 描边明显偏薄。开启后每个 cube 的
     * scale 改为<b>按自身尺寸自适应</b>:perCubeScale = 1+(scale-1)×refDiag/自身
     * 半对角线,使所有部件的表面外扩量一致(参考半对角线 refDiag 由调用方指定,
     * 取该模型主 cube 的半对角线 → 视觉与主 cube 一致,细分部分补足到相同厚度)。
     *
     * @param pose        渲染 PoseStack(已含部件变换)
     * @param part        盔甲部件 ModelPart
     * @param consumer    顶点写入目标
     * @param packedColor 描边色(ABGR 打包)
     * @param scale       基础放大系数(1 + thickness×THICKNESS_SCALE×inflate)
     * @param uniform     true = 按 cube 尺寸自适应(等厚);false = 固定 scale(旧行为)
     * @param refDiag     均匀外扩的参考半对角线(模型单位,仅 uniform=true 时用)
     */
    private static void renderPartInflated(PoseStack pose, ModelPart part, VertexConsumer consumer,
                                           int packedColor, float scale, boolean uniform, float refDiag) {
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
            // 自适应放大系数:小 cube 补足外扩量,使表面外扩厚度与参考一致
            float perCubeScale = scale;
            if (uniform) {
                float dx = (cube.maxX - cube.minX) / 16.0f;
                float dy = (cube.maxY - cube.minY) / 16.0f;
                float dz = (cube.maxZ - cube.minZ) / 16.0f;
                float diag = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (diag > 1e-4f) {
                    perCubeScale = 1.0f + (scale - 1.0f) * refDiag / diag;
                }
            }
            original.set(p.pose());
            // transform = T(c)·S·T(-c),复用同一对象(矩阵乘法是纯读源写目标)
            transform.identity()
                    .translate(cx, cy, cz)
                    .scale(perCubeScale)
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
     * <p>
     * <b>与盔甲视觉一致(v0.1.7 修正)</b>:鞘翅复用盔甲的放大系数
     * ({@link #ARMOR_INFLATE_PER_THICKNESS})与参考半对角线
     * ({@link #ARMOR_REF_DIAG}),均匀外扩后两者外扩像素完全相同 —— 同一个
     * armorThickness 配置统一控制盔甲与鞘翅的描边厚度(此前鞘翅独立系数导致
     * 同一配置下鞘翅明显更宽)。
     *
     * @param pose      实体渲染 PoseStack(已含实体变换与姿态)
     * @param wings     左右翼片(已 setupAnim 的 ModelPart)
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义,来自 armorThickness 配置)
     */
    public void renderElytraOutline(PoseStack pose, ModelPart[] wings, int color, float thickness) {
        renderEntityModelOutline(pose, wings, ELYTRA_TEXTURE, ARMOR_INFLATE_PER_THICKNESS, color, thickness,
                true, ARMOR_REF_DIAG);
    }

    /**
     * 实体模型描边通用版:逐 cube 绕自身包围盒中心放大壳(鞘翅/投掷物/BEWLR 长枪共用)。
     * 使用<b>固定放大壳</b>(不按 cube 自适应,向后兼容)。
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
        renderEntityModelOutline(pose, models, texture, inflatePerThick, color, thickness,
                false, ARMOR_REF_DIAG);
    }

    /**
     * 实体模型描边通用版(内部带均匀外扩开关)。
     *
     * @param pose              实体渲染 PoseStack(已含实体变换与姿态)
     * @param models            实体模型部件(可多个,如鞘翅左右翼)
     * @param texture           独立纹理(非 atlas),描边着色器采样其 alpha 遮罩
     * @param inflatePerThick   每厚度放大增量
     * @param color             描边 ARGB
     * @param thickness         描边厚度(像素语义)
     * @param uniform           true = 每 cube 等厚外扩(参考 refDiag);false = 固定放大壳
     * @param refDiag           均匀外扩参考半对角线(模型单位,仅 uniform=true 用)
     */
    private void renderEntityModelOutline(PoseStack pose, ModelPart[] models, ResourceLocation texture,
                                          float inflatePerThick, int color, float thickness,
                                          boolean uniform, float refDiag) {
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
                renderPartInflated(pose, part, consumer, packedColor, scale, uniform, refDiag);
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
    RenderType armorOutlineRenderType(ResourceLocation texture, int color) {
        // ⚠️ 全透明纹理回退(2026-08-31 Spore 盔甲"无描边"根因):真菌感染(Spore)的
        // SporeBaseArmor.getArmorTexture 恒返回 spore:textures/entity/empty.png(全透明
        // 32×32),原版 HumanoidArmorLayer 用它渲染(隐形),Spore 玩家盔甲本体实际由
        // 自定义 CustomArmorLayer(部件渲染)绘制。描边若继续采样 empty 的 alpha → 壳整体
        // 透明不可见。几何(renderPartInflated 逐 cube 放大壳)与纹理无关,只需把采样源
        // 换成不透明的 WHITE_TEXTURE(alpha 全 1)→ 描边变为"纯几何实心壳"绕本体可见。
        // 检测结果按纹理缓存,不重复读 PNG。
        if (isFullyTransparent(texture)) {
            texture = WHITE_TEXTURE;
        }
        if (needVanillaShaderFallback()) {
            // 逐物品覆盖:强制纯色的物品即使全局开混色也走纯色形状纹理
            if (Config.ARMOR_PIXEL_COLOR_GLINT.get() && !ColorResolver.isSolidOutline(currentOutlinedItem())) {
                // 混色开:采样原纹理(alpha 遮罩 + 颜色混合)。
                // BUG1(2026-09-25):GPU 采样前统一做序列图首帧替换 —— mcmeta 动画贴图
                // 注册到 TextureManager 的是整张 sheet,直接采样会画成全帧并集巨块。
                return worldEmissiveOutlineRenderType(animatedSampleTexture(texture));
            }
            // 混色关:优先采样"描边色形状纹理"(保留原纹理 alpha 遮罩形状,纯描边色)
            ResourceLocation shapeTex = shapeTextureForLocation(texture, color);
            if (shapeTex != null) {
                return worldEmissiveOutlineRenderType(shapeTex);
            }
            // ⚠️ 读取失败降级(2026-10-10 Hazen 平面描边根因):禁止退 WHITE_TEXTURE ——
            // alpha 全 1 会让放大壳整体不透明 = 实心平面("方形背景"事故,铁律 6.9 捕获
            // 路径已明令禁止,本路径同源加固)→ 降级为 GPU 采样原纹理
            // (animatedSampleTexture 分流:活动画绑原 location / 死注册首帧烘焙):
            // 形状仍由真实贴图 alpha 决定;颜色退化为混色(内置 emissive shader 无法
            // 屏蔽 texel.rgb,纯色语义仅在 CPU 形状纹理可用时保证)。失败原因已经
            // readTextureFrame 节流留痕,根因修复后自动回到纯色形状纹理路径。
            return worldEmissiveOutlineRenderType(animatedSampleTexture(texture));
        }
        // 自定义 shader:原纹理 + 描边 shader(alpha boost)。
        // ⚠️ 不开光影强制纯色(2026-10-02 用户决策,覆盖全部算法):blend 恒 0,
        // 描边 = 纯描边色(贴图只提供 alpha 形状),无视混色开关;混色仅在<b>光影
        // 兼容模式</b>依据 ARMOR_PIXEL_COLOR_GLINT 配置(见上方 fallback 分支)。
        setOutlineBlend(0.0f);
        // BUG1(2026-09-25):自定义 shader 的 GPU 采样同样统一换首帧烘焙纹理(同上)
        texture = animatedSampleTexture(texture);
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

    /**
     * 检测纹理是否<b>完全透明</b>(PNG 无任何 alpha&gt;0 像素),结果按纹理缓存。
     * <p>
     * ⚠️ 与 {@link #shapeTextureForLocation} 一样用 JDK ImageIO 读取:palette+tRNS 等
     * 格式交给 JDK 展开,不做像素级 alpha 猜测。读失败按"非透明"处理(宁可描边照旧,
     * 也不因检测失败吞掉整件描边)。white.png(1×1 不透明)直接短路。
     *
     * @param texture 独立纹理(盔甲/鞘翅/投掷物/Geo 的采样源)
     * @return true = 全透明,采样其 alpha 的描边壳不可见,应回退纯几何
     */
    private boolean isFullyTransparent(ResourceLocation texture) {
        if (WHITE_TEXTURE.equals(texture)) {
            return false;
        }
        Boolean cached = this.transparentTextureCache.get(texture);
        if (cached != null) {
            return cached;
        }
        boolean result;
        try {
            // BUG1(2026-09-25):mcmeta 感知读取(ImageIO + 序列图只扫首帧)—— 动画贴图
            // 的模型 UV 空间只有单帧,透明性按首帧判断;首帧全透明而后帧有内容的动画
            // 会被判为全透明(回退纯几何壳,比描边不可见好)。
            java.awt.image.BufferedImage bi = readTextureFrame(texture);
            if (bi == null) {
                result = false; // 读失败按"非透明"处理:宁可描边照旧,不吞整件描边
            } else {
                result = true; // 先假设全透明,发现任一不透明像素即翻转
                outer:
                for (int y = 0; y < bi.getHeight(); y++) {
                    for (int x = 0; x < bi.getWidth(); x++) {
                        if (((bi.getRGB(x, y) >>> 24) & 0xFF) > 0) {
                            result = false;
                            break outer;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            result = false;
        }
        this.transparentTextureCache.put(texture, result);
        return result;
    }

    /**
     * Geo 骨骼模型(GeckoLib / AzureLib 物品与盔甲)的<b>纯几何描边</b> RenderType。
     * <p>
     * ⚠️ 3D 骨骼模型<b>不能</b>用 2D 贴图 alpha 遮罩做描边形状:模组 3D 模型贴图常带
     * 透明/镂空区域、内部面、特殊 UV(如 2D 精灵图展开在 3D 方块上),采样其 alpha 会让
     * 描边缺块/半透明。因此 Geo 骨骼描边<b>恒为纯描边色、形状纯几何</b>(逐 cube 顶点
     * 法线外扩),采样 WHITE_TEXTURE(alpha 全 1),无视 ARMOR_PIXEL_COLOR_GLINT 混色开关。
     * <ul>
     *   <li>无光影(自定义 shader):WHITE_TEXTURE + 描边 shader,vertexColor=纯描边色;</li>
     *   <li>光影 fallback:烘焙 WHITE_TEXTURE 的"描边色形状纹理"(全 alpha 纯色)+ emissive。</li>
     * </ul>
     */
    RenderType geoOutlineRenderType(int color) {
        if (needVanillaShaderFallback()) {
            ResourceLocation shapeTex = shapeTextureForLocation(WHITE_TEXTURE, color);
            // 防御(2026-10-10 契约变更:读失败返回 null):WHITE 是自有 1×1 资源,
            // 读取理论不可达;真失败时原样 WHITE 恰是纯几何壳的正确语义(alpha 全 1)
            return worldEmissiveOutlineRenderType(shapeTex != null ? shapeTex : WHITE_TEXTURE);
        }
        RenderType type = this.armorRenderTypes.get(WHITE_TEXTURE);
        if (type == null) {
            type = RenderType.create("enchanted_outlines_geo",
                    DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(() -> this.outlineShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(WHITE_TEXTURE, false, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                            .createCompositeState(false));
            this.armorRenderTypes.put(WHITE_TEXTURE, type);
        }
        return type;
    }


    // ==================== Geo 盔甲门面(GeoOutline 委托,mixin 接口不变) ====================

    /**
     * 检测是否 AzureLib 盔甲(门面:委托 {@link GeoOutline},HumanoidArmorLayerMixin 调用点不变)。
     *
     * @param stack 盔甲物品
     * @return true = AzureLib 盔甲(走 Geo 骨骼描边,不走原版盔甲路径)
     */
    public boolean isAzureLibArmor(ItemStack stack) {
        return this.geoOutline.isAzureLibArmor(stack);
    }

    /**
     * AzureLib 盔甲描边(门面:委托 {@link GeoOutline#renderAzureLibArmor},实现与拆分前一致)。
     *
     * @param pose      盔甲层 PoseStack
     * @param stack     盔甲物品
     * @param entity    穿戴实体
     * @param slot      盔甲槽位
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     * @param baseModel 父级人形模型(骨骼姿势对齐源)
     * @return true = 已绘制描边
     */
    public boolean renderAzureLibArmorOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                              EquipmentSlot slot, int color, float thickness,
                                              HumanoidModel<?> baseModel) {
        return this.geoOutline.renderAzureLibArmor(pose, stack, entity, slot, color, thickness, baseModel);
    }

    /**
     * 检测是否 GeckoLib 盔甲(门面:委托 {@link GeoOutline},HumanoidArmorLayerMixin 调用点不变)。
     *
     * @param stack 盔甲物品
     * @return true = GeckoLib 盔甲(GeoRenderProvider.getGeoArmorRenderer 非空)
     */
    public boolean isGeckoLibArmor(ItemStack stack) {
        return this.geoOutline.isGeckoLibArmor(stack);
    }

    /**
     * GeckoLib 盔甲描边(门面:委托 {@link GeoOutline#renderGeckoLibArmor},实现与拆分前一致)。
     *
     * @param pose      盔甲层 PoseStack
     * @param stack     盔甲物品
     * @param entity    穿戴实体
     * @param slot      盔甲槽位
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     * @param baseModel 父级人形模型(骨骼姿势对齐源)
     * @return true = 已绘制描边
     */
    public boolean renderGeckoLibArmorOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                              EquipmentSlot slot, int color, float thickness,
                                              HumanoidModel<?> baseModel) {
        return this.geoOutline.renderGeckoLibArmor(pose, stack, entity, slot, color, thickness, baseModel);
    }
}
