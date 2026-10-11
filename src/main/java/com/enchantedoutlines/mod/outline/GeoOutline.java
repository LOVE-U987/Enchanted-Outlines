package com.enchantedoutlines.mod.outline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * GeckoLib / AzureLib Geo 骨骼描边子模块(物品武器 + 模组盔甲)。
 * <p>
 * 2026-09-25 行为保持拆分:从 {@link OutlineRenderer} 拆出的 BEWLR 家族子模块之一。
 * GeckoLib 4 与 AzureLib(其 fork)的物品/盔甲用同一套 Geo 骨骼结构:
 * BakedGeoModel / AzBakedModel → GeoBone / AzBone → GeoCube → GeoQuad / GeoVertex,
 * 两者骨骼层几乎同构(方法名一致,仅包名不同),故用一套反射遍历逻辑覆盖两库。
 * 渲染语义与拆分前完全一致(AGENTS.md 铁律 3.6:统一采样本体贴图 alpha 轮廓,
 * 贴图透明部件无描边;盔甲必须反射调用原模组骨骼准备,不得硬编码复刻)。
 * <p>
 * 对外入口(供 {@link OutlineRenderer} 派发/门面调用):
 * <ul>
 *   <li>{@link #renderItem}:Geo 物品描边(应用库内置物品预变换);</li>
 *   <li>{@link #renderAzureLibArmor} / {@link #renderGeckoLibArmor}:模组盔甲描边
 *       (HumanoidArmorLayerMixin 经 OutlineRenderer 门面转调);</li>
 *   <li>{@link #isAzureLibArmor} / {@link #isGeckoLibArmor}:盔甲类型检测(带缓存)。</li>
 * </ul>
 * 所有外部库访问走纯反射(不引入 compile-time 依赖),与 LionfishAPI/灾变适配一致。
 * <p>
 * 对 {@link OutlineRenderer} 的依赖(包私有):描边缓冲/uniform/armorOutlineRenderType/
 * 共享常量/反射工具,经构造时注入的 renderer 实例访问。
 */
final class GeoOutline {

    /** 宿主渲染器(描边缓冲/RenderType/uniform 的统一入口,循环引用:仅存引用不触构造)。 */
    private final OutlineRenderer renderer;

    /**
     * @param renderer 宿主渲染器(OutlineRenderer 单例;构造只存引用,无回调)
     */
    GeoOutline(OutlineRenderer renderer) {
        this.renderer = renderer;
    }

    /** 物品预变换(库内置,GeckoLib/AzureLib 一致):translate(0.5, 0.51, 0.5)。 */
    private static final float GEO_ITEM_TRANSLATE = 0.5f;
    private static final float GEO_ITEM_TRANSLATE_Y = 0.51f;

    /** Geo cube 静态几何的 WeakHashMap 缓存(GeoCube → 展开几何,弱引用;位置/UV/法线与帧无关,可缓存)。 */
    private final Map<Object, ExpandedGeoCube> geoCubeCache = new WeakHashMap<>();

    /** 物品 → Geo 种类(0=非 Geo,1=GeckoLib,2=AzureLib),检测结果缓存。 */
    private final Map<Item, Integer> geoItemKindCache = new HashMap<>();

    /** 物品 → 是否 AzureLib 盔甲(检测结果缓存,热路径避免每帧反射)。 */
    private final Map<Item, Boolean> azureLibArmorCache = new HashMap<>();

    /** 物品 → 是否 GeckoLib 盔甲(GeoRenderProvider.getGeoArmorRenderer 非空,检测结果缓存)。 */
    private final Map<Item, Boolean> geckoLibArmorCache = new HashMap<>();

    /** Geo 反射 Method 缓存(key = className#name#paramTypes,静态,类加载后不变)。 */
    private static final Map<String, Method> GEO_METHOD_CACHE = new HashMap<>();

    /** 已解析的 Geo 模型:顶层骨骼列表(可为 GeckoLib GeoBone 或 AzureLib AzBone)+ 本体纹理。 */
    private record GeoModelData(List<?> topLevelBones, ResourceLocation texture) {
    }

    /** Geo cube 静态几何缓存(只依赖局部坐标,与帧无关):24 顶点坐标/UV/聚合顶点法线。 */
    private static final class ExpandedGeoCube {
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

    // ---- Geo 反射辅助(record accessor / getter,Method 缓存) ----

    private static Method geoMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        String key = clazz.getName() + '#' + name + Arrays.toString(paramTypes);
        Method m = GEO_METHOD_CACHE.get(key);
        if (m == null && !GEO_METHOD_CACHE.containsKey(key)) {
            m = OutlineRenderer.findMethod(clazz, name, paramTypes);
            if (m != null) {
                // ⚠️ JPMS 模块化下,匿名类(如 GeoRenderProvider 的匿名实现)非 public,
                // 跨模块反射 invoke 会抛 IllegalAccessException,必须 setAccessible(true)
                m.setAccessible(true);
            }
            GEO_METHOD_CACHE.put(key, m);
        }
        return m;
    }

    /** 反射调用无参方法(record accessor / getter)。 */
    private static Object geoInvoke(Object obj, String name) {
        Method m = geoMethod(obj.getClass(), name);
        if (m == null) {
            return null;
        }
        try {
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 反射调用单参数方法(如 AzArmorBoneContext.applyBaseTransformations / applyBoneVisibilityBySlot)。 */
    private static void geoInvokeArg(Object obj, String name, Object arg) {
        Method m = geoMethod(obj.getClass(), name, Object.class);
        if (m == null) {
            return;
        }
        try {
            m.setAccessible(true);
            m.invoke(obj, arg);
        } catch (Exception ignored) {
        }
    }

    /** 反射调用双参数方法(如 AzArmorBoneContext.grabRelevantBones)。 */
    private static void geoInvokeArgs(Object obj, String name, Object a, Object b) {
        Method m = geoMethod(obj.getClass(), name, Object.class, Object.class);
        if (m == null) {
            return;
        }
        try {
            m.setAccessible(true);
            m.invoke(obj, a, b);
        } catch (Exception ignored) {
        }
    }

    private static float geoFloat(Object obj, String getter) {
        Object v = geoInvoke(obj, getter);
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    private static List<?> geoList(Object obj, String getter) {
        Object v = geoInvoke(obj, getter);
        return v instanceof List<?> l ? l : null;
    }

    private static boolean geoBool(Object obj, String getter) {
        Object v = geoInvoke(obj, getter);
        return v instanceof Boolean b && b;
    }

    /** 读 cube 的 Vec3 分量(pivot()/rotation(),net.minecraft.world.phys.Vec3)。 */
    private static double geoCubeVec(Object cube, String accessor, char axis) {
        Object v = geoInvoke(cube, accessor);
        if (!(v instanceof Vec3 vec)) {
            return 0.0;
        }
        return switch (axis) {
            case 'x' -> vec.x();
            case 'y' -> vec.y();
            default -> vec.z();
        };
    }

    /** 反射调用已解析的 Method(带 setAccessible)。 */
    private static Object geoInvokeSafe(Method m, Object target, Object... args) {
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- Geo 立方体静态几何 ----

    /**
     * 构建 Geo cube 静态几何缓存:反射 record accessor 读 6 面 × 4 顶点的局部坐标、UV、
     * 面法线,并按位置聚合相邻面法线得到顶点法线(外扩方向),避免面间裂缝。
     * <p>
     * 与 LionfishOutline 的 buildExpandedLionfishCube 同算法,但数据来自 GeoCube/GeoQuad/
     * GeoVertex record accessor(比 LionfishAPI 的反射字段更稳),且顶点 position 已是
     * <b>模型单位</b>(加载时已 ÷16),无需再除 16。
     *
     * @param cube GeoCube 对象
     * @return 静态几何缓存;反射失败返回 null(调用方跳过该 cube)
     */
    private ExpandedGeoCube buildExpandedGeoCube(Object cube) {
        try {
            Object qarr = geoInvoke(cube, "quads");
            if (!(qarr instanceof Object[] quads) || quads.length == 0) {
                return null;
            }
            final int n = quads.length * 4;
            float[][] pos = new float[n][];
            float[] us = new float[n];
            float[] vs = new float[n];
            Vector3f[] qn = new Vector3f[quads.length];
            for (int i = 0; i < quads.length; i++) {
                Object quad = quads[i];
                Object varr = geoInvoke(quad, "vertices");
                if (!(varr instanceof Object[] vertices) || vertices.length < 4) {
                    return null;
                }
                qn[i] = geoInvoke(quad, "normal") instanceof Vector3f normal ? normal : null;
                for (int j = 0; j < 4; j++) {
                    Object vtx = vertices[j];
                    Vector3f p = geoInvoke(vtx, "position") instanceof Vector3f pp ? pp : null;
                    if (p == null || qn[i] == null) {
                        return null;
                    }
                    int idx = i * 4 + j;
                    pos[idx] = new float[]{p.x(), p.y(), p.z()};
                    us[idx] = geoFloat(vtx, "texU");
                    vs[idx] = geoFloat(vtx, "texV");
                }
            }
            // 顶点法线聚合(按位置分组,累加面法线归一化)
            int[] group = new int[n];
            float[] gx = new float[n];
            float[] gy = new float[n];
            float[] gz = new float[n];
            Arrays.fill(group, -1);
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
            float[] ax = new float[numGroups];
            float[] ay = new float[numGroups];
            float[] az = new float[numGroups];
            for (int i = 0; i < n; i++) {
                Vector3f normal = qn[i / 4];
                int g = group[i];
                ax[g] += normal.x();
                ay[g] += normal.y();
                az[g] += normal.z();
            }
            ExpandedGeoCube out = new ExpandedGeoCube();
            for (int i = 0; i < n; i++) {
                float[] p = pos[i];
                int g = group[i];
                float len = (float) Math.sqrt(ax[g] * ax[g] + ay[g] * ay[g] + az[g] * az[g]);
                float inv = len > 1e-6f ? 1f / len : 0f;
                out.x[i] = p[0];
                out.y[i] = p[1];
                out.z[i] = p[2];
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

    // ---- Geo 骨骼树遍历渲染 ----

    /**
     * 递归渲染 Geo 骨骼树。骨骼变换链复刻 GeckoLib/AzureLib 的
     * {@code prepMatrixForBone}:translate(-posX/16, posY/16, posZ/16)(X 取反)
     * → pivot/16 → rotate(ZYX) → scale → -pivot/16。
     *
     * @param pose        当前 PoseStack
     * @param bone        GeoBone / AzBone 骨骼对象
     * @param consumer    描边顶点写入目标
     * @param packedColor 描边色(ABGR 打包)
     * @param offset      顶点外扩距离(模型单位)
     * @param checkHidden true = 尊重骨骼 isHidden/isHidingChildren(物品,与本体一致
     *        地跳过隐藏骨骼);false = 无视隐藏(盔甲,可见性由调用方按槽位过滤)
     * @return 实际渲染的 cube 数(0 = 无可渲染几何)
     */
    private int renderGeoBone(PoseStack pose, Object bone, VertexConsumer consumer, int packedColor,
                              float offset, boolean checkHidden) {
        if (bone == null) {
            return 0;
        }
        if (checkHidden && geoBool(bone, "isHidden")) {
            return 0;
        }
        int drawn = 0;
        pose.pushPose();
        try {
            pose.translate(-geoFloat(bone, "getPosX") / 16f, geoFloat(bone, "getPosY") / 16f,
                    geoFloat(bone, "getPosZ") / 16f);
            pose.translate(geoFloat(bone, "getPivotX") / 16f, geoFloat(bone, "getPivotY") / 16f,
                    geoFloat(bone, "getPivotZ") / 16f);
            float ax = geoFloat(bone, "getRotX");
            float ay = geoFloat(bone, "getRotY");
            float az = geoFloat(bone, "getRotZ");
            if (ax != 0f || ay != 0f || az != 0f) {
                pose.mulPose(new Quaternionf().rotationZYX(az, ay, ax));
            }
            float sx = geoFloat(bone, "getScaleX");
            float sy = geoFloat(bone, "getScaleY");
            float sz = geoFloat(bone, "getScaleZ");
            if (sx != 1f || sy != 1f || sz != 1f) {
                pose.scale(sx, sy, sz);
            }
            pose.translate(-geoFloat(bone, "getPivotX") / 16f, -geoFloat(bone, "getPivotY") / 16f,
                    -geoFloat(bone, "getPivotZ") / 16f);

            List<?> cubes = geoList(bone, "getCubes");
            if (cubes != null) {
                for (Object cube : cubes) {
                    if (renderGeoCube(pose, cube, consumer, packedColor, offset)) {
                        drawn++;
                    }
                }
            }
            if (!checkHidden || !geoBool(bone, "isHidingChildren")) {
                List<?> children = geoList(bone, "getChildBones");
                if (children != null) {
                    for (Object child : children) {
                        drawn += renderGeoBone(pose, child, consumer, packedColor, offset, checkHidden);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            pose.popPose();
        }
        return drawn;
    }

    /**
     * 渲染单个 Geo cube:cube 变换链 pivot/16 → rotate → -pivot/16,顶点沿聚合顶点法线外扩,
     * <b>统一采样贴图</b>(纹理轮廓)—— 贴图有内容的部件描边(剑形/符号轮廓),
     * 贴图全透明的部件(如光环)无描边。3D cube 采样贴图,UV 是模型 UV。
     */
    private boolean renderGeoCube(PoseStack pose, Object cube, VertexConsumer consumer, int packedColor,
                                  float offset) {
        ExpandedGeoCube ec = geoCubeCache.get(cube);
        if (ec == null) {
            ec = buildExpandedGeoCube(cube);
            if (ec == null) {
                return false;
            }
            geoCubeCache.put(cube, ec);
        }
        pose.pushPose();
        try {
            float px = (float) geoCubeVec(cube, "pivot", 'x') / 16f;
            float py = (float) geoCubeVec(cube, "pivot", 'y') / 16f;
            float pz = (float) geoCubeVec(cube, "pivot", 'z') / 16f;
            pose.translate(px, py, pz);
            float rx = (float) geoCubeVec(cube, "rotation", 'x');
            float ry = (float) geoCubeVec(cube, "rotation", 'y');
            float rz = (float) geoCubeVec(cube, "rotation", 'z');
            if (rx != 0f || ry != 0f || rz != 0f) {
                pose.mulPose(new Quaternionf().rotationZYX(rz, ry, rx));
            }
            pose.translate(-px, -py, -pz);

            writeGeoVertices(pose, ec, consumer, packedColor, offset);
        } finally {
            pose.popPose();
        }
        return true;
    }

    /** 写 Geo cube 的 24 个外扩顶点到 consumer(顶点法线外扩 / 采样贴图共用)。 */
    private static void writeGeoVertices(PoseStack pose, ExpandedGeoCube ec, VertexConsumer consumer,
                                         int packedColor, float offset) {
        Matrix4f poseMatrix = pose.last().pose();
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < ExpandedGeoCube.QUAD_VERTICES; i++) {
            Vector3f p = poseMatrix.transformPosition(
                    ec.x[i] + ec.nx[i] * offset,
                    ec.y[i] + ec.ny[i] * offset,
                    ec.z[i] + ec.nz[i] * offset, tmp);
            consumer.addVertex(p.x(), p.y(), p.z(), packedColor,
                    ec.u[i], ec.v[i], OverlayTexture.NO_OVERLAY, OutlineRenderer.FULL_BRIGHT,
                    ec.nx[i], ec.ny[i], ec.nz[i]);
        }
    }

    /**
     * Geo 模型描边统一入口(GeckoLib / AzureLib 物品与盔甲共用)。
     *
     * @param topLevelBones   顶层骨骼列表(GeoBone 或 AzBone)
     * @param inflatePerThick 每厚度放大增量(物品 BEWLR_3D_SCALE / 盔甲 ARMOR 系数)
     * @return true = 已绘制描边
     */
    private boolean renderGeoModelOutline(PoseStack pose, List<?> topLevelBones, ResourceLocation texture,
                                          int color, float thickness, float inflatePerThick) {
        if (thickness <= 0f || renderer.outlineShader == null || topLevelBones == null || topLevelBones.isEmpty()) {
            return false;
        }
        renderer.setOutlineAlphaBoost(2.0f);
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        float offset = thickness * OutlineRenderer.THICKNESS_SCALE * inflatePerThick;
        // ⚠️ Geo 物品(武器/法杖/工具)采样本体贴图 alpha 做形状 —— 这些物品几何多为
        // 2D 平面 quad(扁平化武器),纯几何描边(geoOutlineRenderType)会画成平面正方形,
        // 不遵循贴图轮廓。用 armorOutlineRenderType(本体纹理)让形状由贴图 alpha 决定:
        //   - 无光影:自定义 shader 采样本体贴图,texel.a 裁出轮廓;
        //   - 光影 fallback + 混色开:emissive + 本体贴图;混色关:形状纹理(CPU 读 alpha)。
        // ⚠️ 物品(武器/法杖)以 2D 贴图轮廓为主:统一采样贴图(剑形轮廓)。
        VertexConsumer consumer = renderer.outlineBuffers
                .getBuffer(renderer.armorOutlineRenderType(texture, color));
        int drawn = 0;
        pose.pushPose();
        try {
            for (Object bone : topLevelBones) {
                drawn += renderGeoBone(pose, bone, consumer, packedColor, offset, true);
            }
        } finally {
            pose.popPose();
        }
        if (drawn <= 0) {
            return false;
        }
        renderer.outlineBuffers.endBatch();
        return true;
    }

    // ---- 物品入口 ----

    /**
     * GeckoLib / AzureLib 物品描边入口:拿到模型后应用库内置物品预变换
     * translate(0.5, 0.51, 0.5) 再渲染(该预变换在 display transform 之后、模型渲染之前)。
     *
     * @param stack     Geo 物品
     * @param pose      已复刻 display transform 的 PoseStack
     * @param color     描边 ARGB
     * @param thickness 描边厚度(像素语义)
     * @return true = 已绘制描边
     */
    boolean renderItem(ItemStack stack, PoseStack pose, int color, float thickness) {
        GeoModelData data = geoItemModel(stack);
        if (data == null) {
            return false;
        }
        pose.pushPose();
        try {
            pose.translate(GEO_ITEM_TRANSLATE, GEO_ITEM_TRANSLATE_Y, GEO_ITEM_TRANSLATE);
            return renderGeoModelOutline(pose, data.topLevelBones(), data.texture(), color, thickness,
                    OutlineRenderer.bewlrInflatePerThickness());
        } finally {
            pose.popPose();
        }
    }

    /**
     * 解析 GeckoLib / AzureLib 物品模型。<b>只缓存"种类"判定</b>(便宜、与帧无关),
     * 骨骼列表与纹理<b>每帧重新解析</b> —— AzureLib/GeckoLib 的渲染管线每帧把动画
     * 姿态/可见性/缩放应用到渲染上下文的烘焙模型上(preRender: itemRenderTranslations
     * /scaleModelForRender/leftArm·rightArm 强制隐藏),缓存解析时刻的骨骼引用会让
     * 描边渲染<b>陈旧/静止的旧实例</b> → 轮廓与本体动画完全脱节(2026-09-26 用户实测:
     * 序列图武器描边不随本体动画)。每帧解析成本 = 几次已缓存的 Method invoke,可接受。
     */
    private GeoModelData geoItemModel(ItemStack stack) {
        Item item = stack.getItem();
        Integer kind = geoItemKindCache.get(item);
        if (kind != null && kind > 0) {
            // kind 命中:每帧重新解析(骨骼/纹理不缓存,见方法 javadoc)
            return kind == 1 ? findGeckoLibModel(stack) : findAzureLibItemModel(stack);
        }
        // kind 为 null(未检测)或 0(之前失败):重新尝试两个库。失败结果不缓存,
        // 避免模型未加载/资源未就绪等临时失败被永久缓存导致"永远无描边"。
        GeoModelData data = findGeckoLibModel(stack);
        int k = 0;
        if (data != null) {
            k = 1;
        } else {
            data = findAzureLibItemModel(stack);
            if (data != null) {
                k = 2;
            }
        }
        if (k > 0) {
            geoItemKindCache.put(item, k);
        }
        return data;
    }

    /** 解析 GeckoLib 4 物品模型(IClientItemExtensions → GeoItemRenderer → GeoModel → BakedGeoModel)。 */
    private GeoModelData findGeckoLibModel(ItemStack stack) {
        try {
            Item item = stack.getItem();
            // ⚠️ GeckoLib 物品的 GeoItemRenderer 不通过 IClientItemExtensions.getCustomRenderer()
            // 暴露(那返回原版 BEWLR 或 null);GeckoLib 用 BlockEntityWithoutLevelRendererMixin
            // 拦截 renderByItem,再经 GeoRenderProvider.of(item).getGeoItemRenderer() 拿到。
            Class<?> providerClass = Class.forName("software.bernie.geckolib.animatable.client.GeoRenderProvider");
            Method of = OutlineRenderer.findMethod(providerClass, "of", Item.class);
            if (of == null) {
                return null;
            }
            Object provider = of.invoke(null, item);
            if (provider == null) {
                return null;
            }
            Method getGeoItemRenderer;
            try {
                getGeoItemRenderer = provider.getClass().getMethod("getGeoItemRenderer");
            } catch (NoSuchMethodException e) {
                return null;
            }
            getGeoItemRenderer.setAccessible(true);
            Object bewlr = getGeoItemRenderer.invoke(provider);
            if (bewlr == null) {
                return null;
            }
            Method getGeoModel = geoMethod(bewlr.getClass(), "getGeoModel");
            if (getGeoModel == null) {
                return null; // 非 GeckoLib BEWLR
            }
            Object geoModel = getGeoModel.invoke(bewlr);
            if (geoModel == null) {
                return null;
            }
            Method getModelResource = geoMethod(geoModel.getClass(), "getModelResource", Object.class);
            if (getModelResource == null) {
                return null;
            }
            Object modelResource = getModelResource.invoke(geoModel, item);
            if (!(modelResource instanceof ResourceLocation loc)) {
                return null;
            }
            Method getBakedModel = geoMethod(geoModel.getClass(), "getBakedModel", ResourceLocation.class);
            if (getBakedModel == null) {
                return null;
            }
            Object bakedModel = getBakedModel.invoke(geoModel, loc);
            if (bakedModel == null) {
                return null;
            }
            Object bones = geoInvoke(bakedModel, "topLevelBones");
            Method getTextureResource = geoMethod(geoModel.getClass(), "getTextureResource", Object.class);
            Object texture = getTextureResource != null ? getTextureResource.invoke(geoModel, item) : null;
            if (bones instanceof List<?> list && texture instanceof ResourceLocation tex) {
                return new GeoModelData(list, tex);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 解析 AzureLib 物品模型(AzItemRendererRegistry → AzItemRenderer → provider → AzBakedModel)。 */
    private GeoModelData findAzureLibItemModel(ItemStack stack) {
        try {
            Class<?> regClass = Class.forName("mod.azure.azurelib.common.render.item.AzItemRendererRegistry");
            Method getOrNull = geoMethod(regClass, "getOrNull", Item.class);
            if (getOrNull == null) {
                return null;
            }
            Object renderer = getOrNull.invoke(null, stack.getItem());
            if (renderer == null) {
                return null;
            }
            Field providerField = OutlineRenderer.findField(renderer.getClass(), "provider");
            if (providerField == null) {
                return null;
            }
            providerField.setAccessible(true);
            Object provider = providerField.get(renderer);
            if (provider == null) {
                return null;
            }
            Method provideBakedModel = geoMethod(provider.getClass(), "provideBakedModel", Object.class, Object.class);
            if (provideBakedModel == null) {
                return null;
            }
            Object bakedModel = provideBakedModel.invoke(provider, null, stack);
            if (bakedModel == null) {
                return null;
            }
            Object bones = geoInvoke(bakedModel, "getTopLevelBones");
            Object texture = null;
            Method configM = geoMethod(renderer.getClass(), "config");
            if (configM != null) {
                Object config = configM.invoke(renderer);
                if (config != null) {
                    Method textureM = geoMethod(config.getClass(), "textureLocation", Object.class);
                    if (textureM != null) {
                        texture = textureM.invoke(config, stack);
                    }
                }
            }
            if (bones instanceof List<?> list && texture instanceof ResourceLocation tex) {
                return new GeoModelData(list, tex);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- AzureLib 盔甲 ----

    /** 检测是否 AzureLib 盔甲(结果按 Item 缓存)。 */
    boolean isAzureLibArmor(ItemStack stack) {
        Item item = stack.getItem();
        Boolean cached = azureLibArmorCache.get(item);
        if (cached != null) {
            return cached;
        }
        boolean result = detectAzureLibArmor(stack);
        azureLibArmorCache.put(item, result);
        return result;
    }

    private boolean detectAzureLibArmor(ItemStack stack) {
        try {
            Class<?> regClass = Class.forName("mod.azure.azurelib.common.render.armor.AzArmorRendererRegistry");
            Method getOrNull = geoMethod(regClass, "getOrNull", ItemStack.class);
            if (getOrNull == null) {
                return false;
            }
            return getOrNull.invoke(null, stack) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * AzureLib 盔甲描边:反射调用原模组的骨骼准备(grabRelevantBones + applyBaseTransformations
     * + applyBoneVisibilityBySlot),精确复刻盔甲 preRender(包括 Hazen 'N Stuff 自定义的
     * AzArmorLeggingTorsoLayerPipeline 对 armorLeggingTorsoLayer 的额外处理),再逐 cube
     * 顶点法线外扩渲染。避免走原版 renderArmorOutline 遍历 ModelPart(会画成原版盔甲轮廓,错位)。
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
    boolean renderAzureLibArmor(PoseStack pose, ItemStack stack, LivingEntity entity,
                                EquipmentSlot slot, int color, float thickness,
                                HumanoidModel<?> baseModel) {
        if (thickness <= 0f || renderer.outlineShader == null) {
            return false;
        }
        Object renderer = azureLibArmorRenderer(stack);
        if (renderer == null) {
            return false;
        }
        Object model = azureLibArmorBakedModel(renderer, entity, stack);
        if (model == null) {
            return false;
        }
        // 反射调用原模组骨骼准备(姿势对齐 + 按槽位可见性),精确复刻本体
        if (!azureLibArmorPrepare(renderer, model, baseModel, slot)) {
            return false;
        }
        List<?> bones = geoList(model, "getTopLevelBones");
        if (bones == null || bones.isEmpty()) {
            return false;
        }
        this.renderer.setOutlineAlphaBoost(2.0f);
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        float offset = thickness * OutlineRenderer.THICKNESS_SCALE * OutlineRenderer.ARMOR_INFLATE_PER_THICKNESS;
        // ⚠️ 纯采样贴图(2026-08-16):全部 cube 采样本体纹理(纹理轮廓),贴图有内容的
        // 部件描边(sigil 符号等),贴图全透明的部件(光环)无描边。
        ResourceLocation texture = azureLibArmorTexture(renderer, entity, stack);
        if (texture == null) {
            texture = OutlineRenderer.WHITE_TEXTURE;
        }
        VertexConsumer consumer = this.renderer.outlineBuffers
                .getBuffer(this.renderer.armorOutlineRenderType(texture, color));
        int drawn = 0;
        pose.pushPose();
        try {
            // 复刻 AzArmorModelRenderer.render 的全局变换:translate(0, 24/16, 0) + scale(-1,-1,1)
            // (盔甲骨骼在模型空间是 Y 上移 1.5 单位 + X/Y 镜像,缺这个会上下颠倒)
            pose.translate(0f, 24f / 16f, 0f);
            pose.scale(-1f, -1f, 1f);
            // checkHidden=true:尊重 applyBoneVisibilityBySlot 设置的骨骼 hidden 标志
            for (Object bone : bones) {
                drawn += renderGeoBone(pose, bone, consumer, packedColor, offset, true);
            }
        } finally {
            pose.popPose();
        }
        if (drawn <= 0) {
            return false;
        }
        this.renderer.outlineBuffers.endBatch();
        return true;
    }

    /** 拿 AzureLib 盔甲纹理(renderer.rendererPipeline().config().textureLocation(entity, stack))。 */
    private ResourceLocation azureLibArmorTexture(Object renderer, LivingEntity entity, ItemStack stack) {
        try {
            Method pipelineM = geoMethod(renderer.getClass(), "rendererPipeline");
            if (pipelineM == null) {
                return null;
            }
            Object pipeline = pipelineM.invoke(renderer);
            if (pipeline == null) {
                return null;
            }
            Method configM = geoMethod(pipeline.getClass(), "config");
            if (configM == null) {
                return null;
            }
            Object config = configM.invoke(pipeline);
            if (config == null) {
                return null;
            }
            Method textureM = geoMethod(config.getClass(), "textureLocation", Object.class, Object.class);
            if (textureM == null) {
                return null;
            }
            Object texture = textureM.invoke(config, entity, stack);
            return texture instanceof ResourceLocation tex ? tex : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 拿 AzureLib 盔甲渲染器(AzArmorRendererRegistry.getOrNull(stack))。 */
    private Object azureLibArmorRenderer(ItemStack stack) {
        try {
            Class<?> regClass = Class.forName("mod.azure.azurelib.common.render.armor.AzArmorRendererRegistry");
            Method getOrNull = geoMethod(regClass, "getOrNull", ItemStack.class);
            if (getOrNull == null) {
                return null;
            }
            return getOrNull.invoke(null, stack);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 拿 AzureLib 盔甲 baked model(renderer.provider().provideBakedModel(entity, stack))。 */
    private Object azureLibArmorBakedModel(Object renderer, LivingEntity entity, ItemStack stack) {
        try {
            Method providerM = geoMethod(renderer.getClass(), "provider");
            if (providerM == null) {
                return null;
            }
            Object provider = providerM.invoke(renderer);
            if (provider == null) {
                return null;
            }
            Method provideBakedModel = geoMethod(provider.getClass(), "provideBakedModel", Object.class, Object.class);
            if (provideBakedModel == null) {
                return null;
            }
            return provideBakedModel.invoke(provider, entity, stack);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 反射调用原模组骨骼准备:grabRelevantBones(抓骨骼)+ applyBaseTransformations(姿势对齐)
     * + applyBoneVisibilityBySlot(按槽位可见性)。这比硬编码复刻更精确,且兼容模组自定义的
     * boneContext(如 Hazen 'N Stuff 的 armorLeggingTorsoLayer)。
     */
    private boolean azureLibArmorPrepare(Object renderer, Object model, HumanoidModel<?> baseModel, EquipmentSlot slot) {
        try {
            Object pipeline = geoInvoke(renderer, "rendererPipeline");
            if (pipeline == null) {
                return false;
            }
            Object context = geoInvoke(pipeline, "context");
            if (context == null) {
                return false;
            }
            Object boneContext = geoInvoke(context, "boneContext");
            if (boneContext == null) {
                return false;
            }
            Object config = geoInvoke(pipeline, "config");
            if (config == null) {
                return false;
            }
            Object boneProvider = geoInvoke(config, "boneProvider");
            geoInvokeArgs(boneContext, "grabRelevantBones", model, boneProvider);
            geoInvokeArg(boneContext, "applyBaseTransformations", baseModel);
            geoInvokeArg(boneContext, "applyBoneVisibilityBySlot", slot);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---- GeckoLib 盔甲 ----

    /** 检测是否 GeckoLib 盔甲(GeoRenderProvider.getGeoArmorRenderer 非空,结果按 Item 缓存)。 */
    boolean isGeckoLibArmor(ItemStack stack) {
        Item item = stack.getItem();
        Boolean cached = geckoLibArmorCache.get(item);
        if (cached != null) {
            return cached;
        }
        boolean result;
        try {
            result = geckoLibArmorRenderer(stack, null, null, null) != null;
        } catch (Exception ignored) {
            result = false;
        }
        geckoLibArmorCache.put(item, result);
        return result;
    }

    /**
     * 拿 GeckoLib 盔甲渲染器(GeoRenderProvider.of(item).getGeoArmorRenderer(...))。
     * <p>
     * GeckoLib 盔甲(GeoArmorRenderer,如 Iron's Spells 的 GenericCustomArmorRenderer)通过
     * GeoItem.createGeoRenderer 注册的匿名 GeoRenderProvider 暴露,不经过 IClientItemExtensions。
     */
    private Object geckoLibArmorRenderer(ItemStack stack, LivingEntity entity, EquipmentSlot slot,
                                         HumanoidModel<?> baseModel) {
        try {
            Class<?> providerClass = Class.forName("software.bernie.geckolib.animatable.client.GeoRenderProvider");
            Method of = geoMethod(providerClass, "of", Item.class);
            if (of == null) {
                return null;
            }
            Object provider = of.invoke(null, stack.getItem());
            if (provider == null) {
                return null;
            }
            // getGeoArmorRenderer 是泛型接口方法,匿名类 override;遍历找同名 public 方法
            Method getRenderer = null;
            for (Method m : provider.getClass().getMethods()) {
                if (m.getName().equals("getGeoArmorRenderer")) {
                    getRenderer = m;
                    break;
                }
            }
            if (getRenderer == null) {
                return null;
            }
            getRenderer.setAccessible(true);
            return getRenderer.invoke(provider, entity, stack, slot, baseModel);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * GeckoLib 盔甲描边:拿到 GeoArmorRenderer 的模型后,反射调用其骨骼准备
     * (grabRelevantBones + applyBaseTransformations + applyBoneVisibilityBySlot),再复刻
     * actuallyRender 的全局变换 translate(0, 24/16, 0) + scale(-1,-1,1),逐 cube 顶点法线
     * 外扩渲染。
     * <p>
     * ⚠️ 渲染时机:GeckoLib 的 HumanoidArmorLayerMixin 用 WrapWithCondition 拦截 render 里的
     * renderArmorPiece 调用并 cancel → 原版 12 参 renderArmorPiece HEAD 不触发。本方法由
     * {@code HumanoidArmorLayerMixin} 在 render 方法 HEAD 遍历 4 槽位时经 OutlineRenderer
     * 门面调用。
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
    boolean renderGeckoLibArmor(PoseStack pose, ItemStack stack, LivingEntity entity,
                                EquipmentSlot slot, int color, float thickness,
                                HumanoidModel<?> baseModel) {
        if (thickness <= 0f || renderer.outlineShader == null) {
            return false;
        }
        Object renderer = geckoLibArmorRenderer(stack, entity, slot, baseModel);
        if (renderer == null) {
            return false;
        }
        Object geoModel = geoInvoke(renderer, "getGeoModel");
        if (geoModel == null) {
            return false;
        }
        Method getModelResource = geoMethod(geoModel.getClass(), "getModelResource", Object.class);
        Object loc = getModelResource != null ? geoInvokeSafe(getModelResource, geoModel, stack.getItem()) : null;
        if (!(loc instanceof ResourceLocation mrl)) {
            return false;
        }
        Method getTextureResource = geoMethod(geoModel.getClass(), "getTextureResource", Object.class);
        Object tex = getTextureResource != null ? geoInvokeSafe(getTextureResource, geoModel, stack.getItem()) : null;
        if (!(tex instanceof ResourceLocation texture)) {
            return false;
        }
        Method getBakedModel = geoMethod(geoModel.getClass(), "getBakedModel", ResourceLocation.class);
        Object baked = getBakedModel != null ? geoInvokeSafe(getBakedModel, geoModel, mrl) : null;
        if (baked == null) {
            return false;
        }
        Object bonesObj = geoInvoke(baked, "topLevelBones");
        if (!(bonesObj instanceof List<?> bones) || bones.isEmpty()) {
            return false;
        }
        // 骨骼准备:反射 renderer 的 grabRelevantBones/applyBaseTransformations/applyBoneVisibilityBySlot
        // (protected,geoMethod 已 setAccessible),精确复刻 GeckoLib 本体渲染前的骨骼状态
        try {
            geoInvokeArg(renderer, "grabRelevantBones", baked);
            geoInvokeArg(renderer, "applyBaseTransformations", baseModel);
            geoInvokeArg(renderer, "applyBoneVisibilityBySlot", slot);
        } catch (Exception ignored) {
            return false;
        }
        this.renderer.setOutlineAlphaBoost(2.0f);
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        float offset = thickness * OutlineRenderer.THICKNESS_SCALE * OutlineRenderer.ARMOR_INFLATE_PER_THICKNESS;
        // ⚠️ 纯采样贴图(2026-08-16):全部 cube 采样本体纹理(纹理轮廓),贴图透明部件无描边
        VertexConsumer consumer = this.renderer.outlineBuffers
                .getBuffer(this.renderer.armorOutlineRenderType(texture, color));
        int drawn = 0;
        pose.pushPose();
        try {
            // 复刻 GeoArmorRenderer.actuallyRender 的全局变换(上下翻转根因)
            pose.translate(0f, 24f / 16f, 0f);
            pose.scale(-1f, -1f, 1f);
            // checkHidden=true:尊重 applyBoneVisibilityBySlot 设置的骨骼 hidden 标志
            for (Object bone : bones) {
                drawn += renderGeoBone(pose, bone, consumer, packedColor, offset, true);
            }
        } finally {
            pose.popPose();
        }
        if (drawn <= 0) {
            return false;
        }
        this.renderer.outlineBuffers.endBatch();
        return true;
    }

    /**
     * 清空 Geo 全部缓存(由 OutlineRenderer.invalidateCaches 统一调用:
     * 资源重载/进出世界时模型实例与检测结果全换,旧缓存必须随之失效)。
     */
    void invalidate() {
        this.geoCubeCache.clear();
        this.geoItemKindCache.clear();
        this.azureLibArmorCache.clear();
        this.geckoLibArmorCache.clear();
    }
}
