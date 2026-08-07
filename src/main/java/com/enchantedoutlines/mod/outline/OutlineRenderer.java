package com.enchantedoutlines.mod.outline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private BakedModel shieldModel;
    /** 生成 shieldModel 时使用的 transforms(blocking 与非 blocking 不同,需跟踪重建)。 */
    private ItemTransforms shieldTransforms;
    private BakedModel tridentModel;
    /** 生成 tridentModel 时使用的 transforms(in_hand 与 throwing 不同,需跟踪重建)。 */
    private ItemTransforms tridentTransforms;
    private final Map<ResourceLocation, RenderType> armorRenderTypes = new HashMap<>();

    /** 三叉戟实体纹理(投掷物 ThrownTrident 的模型材质)。 */
    private static final ResourceLocation TRIDENT_ENTITY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/trident.png");

    /** 鞘翅实体纹理(ElytraLayer 的默认鞘翅材质)。 */
    private static final ResourceLocation ELYTRA_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/elytra.png");

    private OutlineRenderer() {
        this.outlineBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(262144));
    }

    /** 着色器加载回调入口(供 EnchantedOutlinesClient 调用)。 */
    public void setOutlineShader(ShaderInstance shader) {
        this.outlineShader = shader;
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

        VertexConsumer consumer = outlineBuffers.getBuffer(handOutlineRenderType());
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
        VertexConsumer consumer = outlineBuffers.getBuffer(armorOutlineRenderType(texture));
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
        VertexConsumer consumer = outlineBuffers.getBuffer(armorOutlineRenderType(texture));
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
     * 盔甲描边 RenderType:同着色器 + 该部件 armor 材质 + <b>半透明混合</b> + 只写颜色不写深度。
     * <p>
     * 用 TRANSLUCENT_TRANSPARENCY(而非 NO_TRANSPARENCY):描边着色器输出
     * alpha = texel.a × vertexColor.a × boost。若 blend 关闭,RGB 会被无脑写满整个盒子
     * (单层纹理的透明/镂空区域也被涂成描边色);blend 开启后 alpha 生效 → 描边只出现在
     * 纹理不透明区域,贴合单层纹理的轮廓形状。
     */
    private RenderType armorOutlineRenderType(ResourceLocation texture) {
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
