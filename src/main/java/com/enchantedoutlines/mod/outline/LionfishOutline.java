package com.enchantedoutlines.mod.outline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * LionfishAPI 骨骼模型(灾变等)的 3D 描边子模块。
 * <p>
 * 2026-09-25 行为保持拆分:BEWLR 家族按子系统从 {@link OutlineRenderer} 拆为独立类,
 * 本类承接灾变(LionfishAPI)武器的两条描边路径 —— <b>逐 cube 顶点法线外扩</b>
 * (默认,等厚贴身)与<b>整体包围盒放大壳</b>(回退)。渲染语义、变换链、缓存纪律
 * 与拆分前完全一致(AGENTS.md 铁律:Lionfish 变换链逐部件复刻 / 顶点法线按位置聚合 /
 * 整体壳 AABB 中心 ÷16 / 失败必须回退整体壳保证任意版本都有描边)。
 * <p>
 * 对 {@link OutlineRenderer} 的依赖(包私有):描边缓冲 {@code outlineBuffers}、
 * uniform 设置 {@code setOutlineAlphaBoost}、{@code armorOutlineRenderType}、共享
 * 常量与反射工具 —— 全部经构造时注入的 renderer 实例访问,渲染管线仍由
 * OutlineRenderer 统一调度(描边批次闭合/缓存失效仍走其统一入口)。
 */
final class LionfishOutline {

    /** 宿主渲染器(描边缓冲/RenderType/uniform/反射工具的统一入口,循环引用:仅存引用不触构造)。 */
    private final OutlineRenderer renderer;

    /**
     * @param renderer 宿主渲染器(OutlineRenderer 单例;构造只存引用,无回调)
     */
    LionfishOutline(OutlineRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Lionfish cube 静态几何的 WeakHashMap 缓存(ModelBox → 展开几何,弱引用:
     * 模型被 GC 时条目自动清除)。位置/UV/法线均与帧无关,可安全缓存
     * (AGENTS.md 性能纪律:只缓存与帧无关的输入;部件变换由 PoseStack 提供)。
     */
    private final Map<Object, ExpandedLionfishCube> lionfishCubeCache = new WeakHashMap<>();

    /**
     * LionfishAPI 骨骼模型(灾变等)<b>逐 cube 顶点法线外扩</b>描边(默认算法)。
     * <p>
     * 与整体放大壳({@link #renderInflated})不同,这里<b>精确复刻本体</b>
     * {@code BasicModelPart.render} 的变换链(translate(rotationPoint/16) →
     * mulPose(rotationZYX) → scale(xScale,yScale,zScale)),在每个部件<b>局部
     * 坐标系</b>内把 cube 的 24 个 quad 顶点沿<b>聚合顶点法线</b>(相邻面法线
     * 平均后归一化)外扩固定距离 offset 再写入描边 buffer:
     * <ul>
     *   <li><b>等厚贴身</b>:offset 与顶点到整体中心的距离无关 → 细长武器
     *       (枪杆/刀身)端部不再随整体长度膨胀,各部件描边粗细均匀;</li>
     *   <li><b>无裂缝</b>:cube 24 个 quad 顶点按位置聚合为 8 个角点,每组面法线
     *       累加归一化 → 共享顶点在相邻面上外扩方向一致,面间不裂开;</li>
     *   <li><b>UV 与本体一致</b>:直接复用 {@code PositionTextureVertex.textureU/V}
     *       (构造时已按 textureOffset 算好的归一化 UV),描边 alpha 遮罩形状与
     *       本体完全重合;</li>
     *   <li><b>静态几何缓存</b>:位置/UV/法线由 {@link #lionfishCubeCache} 缓存
     *       ({@link ExpandedLionfishCube}),每帧只做矩阵变换与顶点写入,零反射。</li>
     * </ul>
     * cube 坐标与 rotationPoint 均为<b>像素单位</b>(渲染时 ÷16 转模型单位);
     * offset 以模型单位计 = thickness × THICKNESS_SCALE × bewlrInflatePerThickness()
     * (与整体壳 (scale-1) 语义等价,但位移固定不随距离放大)。
     *
     * @param pose      已复刻 display transform 的 PoseStack
     * @param model     LionfishAPI 模型对象(AdvancedEntityModel 子类,如灾变武器)
     * @param texture   本体纹理(颜色混合)
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     * @return true = 已绘制描边
     */
    boolean renderPerCube(PoseStack pose, Object model, ResourceLocation texture,
                          int color, float thickness) {
        try {
            Object root = lionfishRootOf(model);
            if (root == null) {
                return false;
            }
            renderer.setOutlineAlphaBoost(2.0f);
            int packedColor = 0xFF000000 | (color & 0xFFFFFF);
            // 顶点外扩距离(模型单位):位移固定 → 等厚贴身,端部不膨胀
            float offset = thickness * OutlineRenderer.THICKNESS_SCALE
                    * OutlineRenderer.bewlrInflatePerThickness();
            VertexConsumer consumer = renderer.outlineBuffers
                    .getBuffer(renderer.armorOutlineRenderType(texture, color));
            pose.pushPose();
            int drawn = 0;
            try {
                drawn = renderLionfishPart(pose, root, consumer, packedColor, offset);
            } finally {
                pose.popPose();
            }
            if (drawn <= 0) {
                // 没有可渲染的 cube(反射失败 / 无 quads)→ 未 endBatch,无副作用,
                // 返回 false 由调用方回退整体放大壳,保证其他 LionfishAPI 版本仍有描边
                return false;
            }
            renderer.outlineBuffers.endBatch();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 递归渲染 Lionfish 部件树,<b>逐部件复刻 {@code BasicModelPart.render}
     * 变换链</b>:
     * <pre>pushPose → translate(rotationPoint/16) → mulPose(rotationZYX(z,y,x)) →
     * scale(xScale,yScale,zScale) → [本部件 cubes] → [子部件递归] → popPose</pre>
     * rotationPoint 与 cube 坐标均为像素单位,渲染时 ÷16 转模型单位。
     *
     * @param pose        当前 PoseStack(已含父级变换)
     * @param part        LionfishAPI 部件(BasicModelPart 或子类)
     * @param consumer    描边顶点写入目标
     * @param packedColor 描边色(ABGR 打包)
     * @param offset      顶点外扩距离(模型单位)
     * @return 实际渲染的 cube 数(0 = 无 cube 可渲染,调用方应回退整体壳)
     */
    private int renderLionfishPart(PoseStack pose, Object part, VertexConsumer consumer,
                                   int packedColor, float offset) {
        int drawn = 0;
        pose.pushPose();
        try {
            // translateAndRotate 复刻(rotationPoint ÷16 转模型单位)
            pose.translate(partFieldF(part, "rotationPointX") / 16f,
                    partFieldF(part, "rotationPointY") / 16f,
                    partFieldF(part, "rotationPointZ") / 16f);
            float ax = partFieldF(part, "rotateAngleX");
            float ay = partFieldF(part, "rotateAngleY");
            float az = partFieldF(part, "rotateAngleZ");
            if (ax != 0f || ay != 0f || az != 0f) {
                pose.mulPose(new Quaternionf().rotationZYX(az, ay, ax));
            }
            float xs = partFieldF(part, "xScale");
            float ys = partFieldF(part, "yScale");
            float zs = partFieldF(part, "zScale");
            if (xs != 1f || ys != 1f || zs != 1f) {
                pose.scale(xs, ys, zs);
            }
            Field cubesField = OutlineRenderer.findField(part.getClass(), "cubeList");
            if (cubesField != null) {
                cubesField.setAccessible(true);
                Object list = cubesField.get(part);
                if (list instanceof List<?> cubes) {
                    for (Object cube : cubes) {
                        if (renderLionfishCube(pose, cube, consumer, packedColor, offset)) {
                            drawn++;
                        }
                    }
                }
            }
            Field childrenField = OutlineRenderer.findField(part.getClass(), "childModels");
            if (childrenField != null) {
                childrenField.setAccessible(true);
                Object children = childrenField.get(part);
                if (children instanceof List<?> childList) {
                    for (Object child : childList) {
                        drawn += renderLionfishPart(pose, child, consumer, packedColor, offset);
                    }
                }
            }
        } catch (Exception ignored) {
            // 单部件反射失败:跳过该部件,不影响其余部件与描边
        } finally {
            pose.popPose();
        }
        return drawn;
    }

    /**
     * 渲染单个 Lionfish cube 的描边:24 个 quad 顶点(6 面 × 4)逐一沿<b>聚合顶点
     * 法线</b>外扩 offset 后,经当前 PoseStack 矩阵变换写入描边 buffer。
     * 静态几何(坐标/UV/法线)由 {@link #lionfishCubeCache} 缓存,此处零反射。
     */
    private boolean renderLionfishCube(PoseStack pose, Object cube, VertexConsumer consumer,
                                       int packedColor, float offset) {
        ExpandedLionfishCube ec = lionfishCubeCache.get(cube);
        if (ec == null) {
            ec = buildExpandedLionfishCube(cube);
            if (ec == null) {
                return false;
            }
            lionfishCubeCache.put(cube, ec);
        }
        Matrix4f poseMatrix = pose.last().pose();
        Vector3f tmp = new Vector3f(); // 复用,避免每顶点分配
        for (int i = 0; i < ExpandedLionfishCube.QUAD_VERTICES; i++) {
            Vector3f p = poseMatrix.transformPosition(
                    ec.x[i] + ec.nx[i] * offset,
                    ec.y[i] + ec.ny[i] * offset,
                    ec.z[i] + ec.nz[i] * offset, tmp);
            consumer.addVertex(p.x(), p.y(), p.z(), packedColor,
                    ec.u[i], ec.v[i], OverlayTexture.NO_OVERLAY, OutlineRenderer.FULL_BRIGHT,
                    ec.nx[i], ec.ny[i], ec.nz[i]);
        }
        return true;
    }

    /**
     * Lionfish cube 的<b>静态几何缓存</b>(只依赖局部坐标,与帧无关):
     * 24 个 quad 顶点的局部坐标(已 ÷16 转模型单位)、UV(与本体一致)、
     * <b>聚合顶点法线</b>(相邻面法线平均后归一化,即外扩方向)。
     * 部件变换(rotationPoint/rotateAngle/xScale)每帧由 PoseStack 提供,不入缓存。
     */
    private static final class ExpandedLionfishCube {
        /** 6 面 × 4 顶点 = 24。 */
        static final int QUAD_VERTICES = 24;

        final float[] x = new float[QUAD_VERTICES];
        final float[] y = new float[QUAD_VERTICES];
        final float[] z = new float[QUAD_VERTICES];
        final float[] u = new float[QUAD_VERTICES];
        final float[] v = new float[QUAD_VERTICES];
        final float[] nx = new float[QUAD_VERTICES];
        final float[] ny = new float[QUAD_VERTICES];
        final float[] nz = new float[QUAD_VERTICES];
    }

    /**
     * 构建 cube 静态几何缓存:反射读取 {@code ModelBox.quads}(TexturedQuad[]),
     * 每 quad 的 {@code normal}(面法线)与 4 个 {@code PositionTextureVertex}
     * (position 像素 / textureU,V)。
     * <p>
     * <b>顶点法线聚合</b>:cube 的 24 个 quad 顶点中,同一位置(8 个角点)出现在
     * 3 个相邻面上,若直接用所属面法线外扩,共享顶点会外扩到不同位置 → 面间裂缝。
     * 这里按位置(float 精确相等,同 cube 内同一角点的坐标值必然逐位相同)分组,
     * 累加每组的面法线并归一化得到顶点法线 —— 所有共享该角点的 quad 顶点沿同一
     * 方向外扩,无缝且等厚。
     *
     * @param cube LionfishAPI ModelBox 对象
     * @return 静态几何缓存;反射失败返回 null(调用方跳过该 cube)
     */
    private ExpandedLionfishCube buildExpandedLionfishCube(Object cube) {
        try {
            Field quadsField = OutlineRenderer.findField(cube.getClass(), "quads");
            if (quadsField == null) {
                return null;
            }
            quadsField.setAccessible(true);
            Object[] quads = (Object[]) quadsField.get(cube);
            if (quads == null || quads.length == 0) {
                return null;
            }
            final int n = quads.length * 4;
            // 预读全部顶点位置(像素)与 UV,分组/输出阶段零重复反射(一次性成本)
            float[][] pos = new float[n][];
            float[] us = new float[n], vs = new float[n];
            for (int i = 0; i < n; i++) {
                pos[i] = lionfishVertexPositionOf(quads, i);
                float[] uv = lionfishUvOf(quads, i);
                us[i] = uv[0];
                vs[i] = uv[1];
            }
            // 1) 按位置分组:位置完全相等(同一 float 值)的顶点归一组(同一角点)
            int[] group = new int[n];
            float[] gx = new float[n], gy = new float[n], gz = new float[n];
            java.util.Arrays.fill(group, -1);
            int numGroups = 0;
            for (int i = 0; i < n; i++) {
                if (group[i] >= 0) {
                    continue;
                }
                float[] p = pos[i];
                group[i] = numGroups;
                gx[numGroups] = p[0];
                gy[numGroups] = p[1];
                gz[numGroups] = p[2];
                for (int j = i + 1; j < n; j++) {
                    if (group[j] >= 0) {
                        continue;
                    }
                    float[] q = pos[j];
                    if (q[0] == p[0] && q[1] == p[1] && q[2] == p[2]) {
                        group[j] = numGroups;
                    }
                }
                numGroups++;
            }
            // 2) 每顶点累加所属 quad 的面法线(3 个相邻面 → 顶点法线方向)
            float[] ax = new float[numGroups], ay = new float[numGroups], az = new float[numGroups];
            for (int i = 0; i < n; i++) {
                Vector3f qn = lionfishQuadNormalOf(quads[i / 4]);
                int g = group[i];
                ax[g] += qn.x();
                ay[g] += qn.y();
                az[g] += qn.z();
            }
            // 3) 归一化 + 输出缓存(坐标 ÷16 转模型单位,UV 与本体一致)
            ExpandedLionfishCube out = new ExpandedLionfishCube();
            for (int i = 0; i < n; i++) {
                float[] p = pos[i];
                int g = group[i];
                float len = (float) Math.sqrt(ax[g] * ax[g] + ay[g] * ay[g] + az[g] * az[g]);
                float inv = len > 1e-6f ? 1f / len : 0f;
                out.x[i] = p[0] / 16f;
                out.y[i] = p[1] / 16f;
                out.z[i] = p[2] / 16f;
                out.nx[i] = ax[g] * inv;
                out.ny[i] = ay[g] * inv;
                out.nz[i] = az[g] * inv;
                out.u[i] = us[i];
                out.v[i] = vs[i];
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 读 quad 数组第 idx 个顶点的像素坐标 [x, y, z](PositionTextureVertex.position)。 */
    private static float[] lionfishVertexPositionOf(Object[] quads, int idx) throws Exception {
        Vector3f pos = lionfishVertexPosition(lionfishVertexOf(quads[idx / 4], idx % 4));
        return new float[]{pos.x(), pos.y(), pos.z()};
    }

    /** 读 quad 的归一化 UV [u, v](PositionTextureVertex.textureU/V,与本体一致)。 */
    private static float[] lionfishUvOf(Object[] quads, int idx) throws Exception {
        Object vtx = lionfishVertexOf(quads[idx / 4], idx % 4);
        Field fu = OutlineRenderer.findField(vtx.getClass(), "textureU");
        fu.setAccessible(true);
        Field fv = OutlineRenderer.findField(vtx.getClass(), "textureV");
        fv.setAccessible(true);
        return new float[]{fu.getFloat(vtx), fv.getFloat(vtx)};
    }

    /** 读 quad 的 4 个 PositionTextureVertex 中第 idx 个。 */
    private static Object lionfishVertexOf(Object quad, int idx) throws Exception {
        Field f = OutlineRenderer.findField(quad.getClass(), "vertexPositions");
        f.setAccessible(true);
        return ((Object[]) f.get(quad))[idx];
    }

    /** 读顶点的像素坐标(org.joml.Vector3f)。 */
    private static Vector3f lionfishVertexPosition(Object vtx) throws Exception {
        Field f = OutlineRenderer.findField(vtx.getClass(), "position");
        f.setAccessible(true);
        return (Vector3f) f.get(vtx);
    }

    /** 读 quad 的面法线(TexturedQuad.normal,已归一化)。 */
    private static Vector3f lionfishQuadNormalOf(Object quad) throws Exception {
        Field f = OutlineRenderer.findField(quad.getClass(), "normal");
        f.setAccessible(true);
        return (Vector3f) f.get(quad);
    }

    /**
     * ⚠️ 旧算法 / 回退路径:默认已由 {@link #renderPerCube} 逐 cube 顶点
     * 法线外扩取代(Config.BEWLR_3D_PER_CUBE = false 时启用本路径)。
     * <p>
     * LionfishAPI 骨骼模型(灾变等)整体放大壳:
     * <ol>
     *   <li>拿 root(优先调用 {@code root()} 方法,回退 {@code root} 字段);</li>
     *   <li>递归收集全部 cube({@code cubeList},字段 {@code posX1..posZ2})的包围盒,
     *       <b>并累加每级部件的 {@code rotationPointX/Y/Z}(代码偏移)</b> → 全局像素 AABB;</li>
     *   <li>中心 ÷16 转模型单位(⚠️ cube 坐标是像素,渲染时 Lionfish 内部 ÷16,不改会
     *       错位 16 倍),在 PoseStack 上 T(c)·S·T(-c),调用 {@code root.render(pose,
     *       consumer, light, overlay)} 渲染放大壳(几何形状由放大决定,纹理 = 本体
     *       贴图(颜色混合)或纯白)。</li>
     * </ol>
     * 整体放大对细长武器远端外扩略多(外扩 = (scale-1)×到中心距离),但形状贴合、
     * 描边可见;保留用于对比与回退(新算法见 {@link #renderPerCube})。
     */
    boolean renderInflated(PoseStack pose, Object model, ResourceLocation texture,
                           int color, float thickness) {
        try {
            Object root = lionfishRootOf(model);
            if (root == null) {
                return false;
            }
            // 像素单位全局 AABB(已含各部件 rotationPoint 代码偏移)
            float[] bb = lionfishBounds(root, 0f, 0f, 0f);
            if (bb == null) {
                return false;
            }
            // ⚠️ cube 坐标是像素单位,渲染时 Lionfish 内部 ÷16 → 中心必须 ÷16 转模型单位,
            // 否则描边壳偏出本体 16 倍(2026-08-09 灾变描边错位根因)。
            float cx = (bb[0] + bb[3]) / 2f / 16f;
            float cy = (bb[1] + bb[4]) / 2f / 16f;
            float cz = (bb[2] + bb[5]) / 2f / 16f;
            // 方法查找/可访问化提前到 getBuffer 之前:方法不存在(常见失败)不再占用批次
            Method render = OutlineRenderer.findMethod(root.getClass(), "render",
                    PoseStack.class, VertexConsumer.class, int.class, int.class);
            if (render == null) {
                return false;
            }
            render.setAccessible(true);
            renderer.setOutlineAlphaBoost(2.0f);
            float scale = 1.0f + thickness * OutlineRenderer.THICKNESS_SCALE
                    * OutlineRenderer.bewlrInflatePerThickness();
            VertexConsumer consumer = renderer.outlineBuffers
                    .getBuffer(renderer.armorOutlineRenderType(texture, color));
            // ⚠️ 批次闭合铁律(2026-09-25,"头顶巨大模型"根因之一):下面反射调用的是
            // <b>模组自己的 render</b>,中途任何异常(字段/模型版本漂移)都会带着已写入
            // 的半批顶点逃逸,被外层 catch 吞掉后脏批滞留缓冲、在错误的渲染阶段被画出。
            // invoke 失败必须立即 endBatch —— 半批顶点留在本帧本坐标系内画完(或不产生
            // 绘制),绝不跨阶段滞留。
            try {
                pose.pushPose();
                try {
                    pose.translate(cx, cy, cz);
                    pose.scale(scale, scale, scale);
                    pose.translate(-cx, -cy, -cz);
                    render.invoke(root, pose, consumer, OutlineRenderer.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                } finally {
                    pose.popPose();
                }
            } catch (Exception invokeFailure) {
                try {
                    renderer.outlineBuffers.endBatch();
                } catch (Throwable ignoredFlush) {
                    // flush 失败也不影响本体渲染
                }
                return false;
            }
            renderer.outlineBuffers.endBatch();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** LionfishAPI 模型 root:优先调用 {@code root()} 方法,回退 {@code root} 字段。 */
    private Object lionfishRootOf(Object model) {
        try {
            Method m = OutlineRenderer.findMethod(model.getClass(), "root");
            if (m != null) {
                m.setAccessible(true);
                Object v = m.invoke(model);
                if (v != null) {
                    return v;
                }
            }
            Field f = OutlineRenderer.findField(model.getClass(), "root");
            if (f != null) {
                f.setAccessible(true);
                Object v = f.get(model);
                if (v != null) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 递归收集 LionfishAPI 部件树的全部 cube 包围盒(<b>全局像素 AABB</b>)。
     * <p>
     * {@code cubeList} 是 {@code ObjectList<...ModelBox>}(实现 {@link List}),
     * cube 有公开字段 {@code posX1..posZ2}(<b>像素单位</b>,渲染时 Lionfish 内部 ÷16);
     * 子部件在 {@code childModels}。
     * <p>
     * ⚠️ <b>代码偏移</b>:每个部件的 {@code rotationPointX/Y/Z} 是其相对父级的平移
     * (BasicModelPart 公开字段,像素单位)。cube 坐标是相对所属部件的局部坐标,
     * 必须沿父链累加 rotationPoint 才能得到全局 AABB —— 否则多部件武器(刀柄+刀身
     * 分属不同部件)的整体中心算错,描边壳与本体错位。
     *
     * @param part 当前部件
     * @param ox   父链累计的 rotationPointX(像素)
     * @param oy   父链累计的 rotationPointY(像素)
     * @param oz   父链累计的 rotationPointZ(像素)
     * @return [minX, minY, minZ, maxX, maxY, maxZ](像素,含偏移);无 cube 时 null
     */
    @SuppressWarnings("unchecked")
    private float[] lionfishBounds(Object part, float ox, float oy, float oz) {
        float[] bb = null;
        try {
            // 本部件相对父级的代码偏移(rotationPointX/Y/Z 是 BasicModelPart 公开字段)
            float px = ox + partFieldF(part, "rotationPointX");
            float py = oy + partFieldF(part, "rotationPointY");
            float pz = oz + partFieldF(part, "rotationPointZ");
            // ⚠️ 必须用沿父类链查找的 findField(与 renderLionfishPart 一致):运行时部件类
            // 常是 BasicModelPart 子类(如 AdvancedModelBox),getDeclaredField 只查本类
            // 会 NoSuchFieldException → 整个子树静默跳过(整体壳回退路径无描边)。
            Field cubesField = OutlineRenderer.findField(part.getClass(), "cubeList");
            cubesField.setAccessible(true);
            Object list = cubesField.get(part);
            if (list instanceof List<?> cubes) {
                for (Object cube : cubes) {
                    float x1 = px + cubeFieldF(cube, "posX1"), y1 = py + cubeFieldF(cube, "posY1"), z1 = pz + cubeFieldF(cube, "posZ1");
                    float x2 = px + cubeFieldF(cube, "posX2"), y2 = py + cubeFieldF(cube, "posY2"), z2 = pz + cubeFieldF(cube, "posZ2");
                    if (bb == null) {
                        bb = new float[]{x1, y1, z1, x2, y2, z2};
                    } else {
                        bb[0] = Math.min(bb[0], x1);
                        bb[1] = Math.min(bb[1], y1);
                        bb[2] = Math.min(bb[2], z1);
                        bb[3] = Math.max(bb[3], x2);
                        bb[4] = Math.max(bb[4], y2);
                        bb[5] = Math.max(bb[5], z2);
                    }
                }
            }
            Field childrenField = OutlineRenderer.findField(part.getClass(), "childModels");
            childrenField.setAccessible(true);
            Object children = childrenField.get(part);
            if (children instanceof List<?> childList) {
                for (Object child : childList) {
                    float[] cb = lionfishBounds(child, px, py, pz);
                    if (cb == null) {
                        continue;
                    }
                    if (bb == null) {
                        bb = cb;
                    } else {
                        bb[0] = Math.min(bb[0], cb[0]);
                        bb[1] = Math.min(bb[1], cb[1]);
                        bb[2] = Math.min(bb[2], cb[2]);
                        bb[3] = Math.max(bb[3], cb[3]);
                        bb[4] = Math.max(bb[4], cb[4]);
                        bb[5] = Math.max(bb[5], cb[5]);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return bb;
    }

    /** 读部件公开 float 字段(BasicModelPart.rotationPointX/Y/Z 等),失败返回 0。 */
    private static float partFieldF(Object part, String name) {
        try {
            Field f = part.getClass().getField(name);
            return f.getFloat(part);
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private static float cubeFieldF(Object cube, String name) {
        try {
            Field f = cube.getClass().getField(name); // 公开字段
            return f.getFloat(cube);
        } catch (Exception ignored) {
            return 0f;
        }
    }

    /**
     * 清空静态几何缓存(由 OutlineRenderer.invalidateCaches 统一调用:
     * 资源重载/进出世界时,模型实例全换,旧缓存必须随之失效)。
     */
    void invalidate() {
        this.lionfishCubeCache.clear();
    }
}
