package com.enchantedoutlines.mod.api.algo;

import com.enchantedoutlines.mod.api.core.ItemTypeInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

/**
 * 形状检测器。
 * <p>
 * 负责分析物品的形状特征，选择对应的渲染算法。
 */
public final class ShapeDetector {

    public static final ShapeDetector INSTANCE = new ShapeDetector();

    /** 形状结果缓存 (BakedModel → ShapeResult)。 */
    private final Map<BakedModel, ShapeResult> shapeCache = new HashMap<>();

    private ShapeDetector() {
    }

    /**
     * 检测物品形状。
     *
     * @param stack     物品栈
     * @param model     物品模型
     * @param typeInfo  物品类型信息
     * @return 形状检测结果
     */
    public ShapeResult detect(ItemStack stack, BakedModel model, @Nullable ItemTypeInfo typeInfo) {
        ShapeResult cached = shapeCache.get(model);
        if (cached != null) {
            return cached;
        }

        ShapeResult result = buildShapeResult(stack, model, typeInfo);
        shapeCache.put(model, result);
        return result;
    }

    /**
     * 构建形状结果。
     */
    private ShapeResult buildShapeResult(ItemStack stack, BakedModel model, @Nullable ItemTypeInfo typeInfo) {
        List<BakedQuad> quads = collectQuads(model);
        if (quads.isEmpty()) {
            return createEmptyResult();
        }

        // 基础属性
        boolean isFlat = !model.isGui3d();
        boolean is3d = model.isGui3d();

        // 按 sprite 分组
        Map<TextureAtlasSprite, List<BakedQuad>> quadsBySprite = groupBySprite(quads);

        // 计算法线
        List<Vec3i> quadNormals = new ArrayList<>();
        for (BakedQuad q : quads) {
            quadNormals.add(safeQuadNormal(q));
        }

        // 计算平均亮度
        float mainLuma = calculateMainLuma(quads);

        // 检查是否全部使用 stone 精灵 (盾牌/三叉戟盒模型)
        boolean allStone = usesOnlyStoneSprite(quads);

        // 顶点外扩预处理 (仅 3D 物品需要)
        List<ShapeResult.ExpandVertex[]> expandVertices = new ArrayList<>();
        if (is3d) {
            expandVertices = prepareExpand(quads, quadNormals);
        }

        return new ShapeResult(
                isFlat, is3d, quadsBySprite.size() == 1,
                selectAlgorithm(model, typeInfo),
                quads, quadsBySprite, quadNormals, expandVertices,
                mainLuma, allStone
        );
    }

    /**
     * 收集模型全部 quads。
     */
    private List<BakedQuad> collectQuads(BakedModel model) {
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
     * 按 sprite 分组 quads。
     */
    private Map<TextureAtlasSprite, List<BakedQuad>> groupBySprite(List<BakedQuad> quads) {
        Map<TextureAtlasSprite, List<BakedQuad>> bySprite = new HashMap<>();
        
        for (BakedQuad q : quads) {
            TextureAtlasSprite sprite = q.getSprite();
            if (sprite != null) {
                bySprite.computeIfAbsent(sprite, k -> new ArrayList<>()).add(q);
            }
        }
        
        return bySprite;
    }

    /**
     * 获取 quad 的法线，兼容任意模组模型。
     */
    private Vec3i safeQuadNormal(BakedQuad quad) {
        Direction dir = quad.getDirection();
        if (dir != null) {
            return dir.getNormal();
        }
        
        int[] v = quad.getVertices();
        if (v.length < 24) {
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
        
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float acx = cx - ax, acy = cy - ay, acz = cz - az;
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        float lenSq = nx * nx + ny * ny + nz * nz;
        
        if (lenSq <= 1e-6f) {
            return Vec3i.ZERO;
        }
        
        float inv = 1.0f / (float) Math.sqrt(lenSq);
        return new Vec3i(Math.round(nx * inv), Math.round(ny * inv), Math.round(nz * inv));
    }

    /**
     * 计算主 sprite 的平均亮度。
     */
    private float calculateMainLuma(List<BakedQuad> quads) {
        for (BakedQuad q : quads) {
            TextureAtlasSprite s = q.getSprite();
            if (s != null) {
                NativeImage img = spriteOriginalImage(s);
                if (img != null) {
                    return averageLuma(img);
                }
            }
        }
        return -1f;
    }

    /**
     * 判断是否全部使用 stone 精灵。
     */
    private boolean usesOnlyStoneSprite(List<BakedQuad> quads) {
        ResourceLocation stoneLocation = ResourceLocation.fromNamespaceAndPath("minecraft", "block/stone");
        
        for (BakedQuad q : quads) {
            TextureAtlasSprite sprite = q.getSprite();
            if (sprite == null) {
                continue;
            }
            
            ResourceLocation loc = sprite.getId();
            if (!stoneLocation.equals(loc)) {
                return false;
            }
        }
        
        return !quads.isEmpty();
    }

    /**
     * 顶点外扩预处理。
     */
    private List<ShapeResult.ExpandVertex[]> prepareExpand(List<BakedQuad> quads, List<Vec3i> quadNormals) {
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
        
        List<ShapeResult.ExpandVertex[]> out = new ArrayList<>(quads.size());
        for (int qi = 0; qi < quads.size(); qi++) {
            int[] v = quads.get(qi).getVertices();
            ShapeResult.ExpandVertex[] verts = new ShapeResult.ExpandVertex[v.length / 8];
            int vi = 0;
            
            for (int i = 0; i + 8 <= v.length; i += 8, vi++) {
                float x = Float.intBitsToFloat(v[i]);
                float y = Float.intBitsToFloat(v[i + 1]);
                float z = Float.intBitsToFloat(v[i + 2]);
                float u = Float.intBitsToFloat(v[i + 4]);
                float vv = Float.intBitsToFloat(v[i + 5]);
                
                Vector3f dir = normals.get(new Position(x, y, z));
                float dx = dir != null ? dir.x() : 0f;
                float dy = dir != null ? dir.y() : 0f;
                float dz = dir != null ? dir.z() : 0f;
                
                verts[vi] = new ShapeResult.ExpandVertex(x, y, z, u, vv, dx, dy, dz);
            }
            out.add(verts);
        }
        
        return out;
    }

    /**
     * 选择形状算法。
     */
    private ShapeResult.ShapeAlgorithm selectAlgorithm(BakedModel model, @Nullable ItemTypeInfo typeInfo) {
        if (typeInfo == null) {
            return model.isGui3d() ? ShapeResult.ShapeAlgorithm.FLAT_VERTEX_NORMAL : ShapeResult.ShapeAlgorithm.FLAT_SCREEN_OFFSET;
        }
        
        // 按类型选择算法
        if (typeInfo.isLionfish() && Config.BEWLR_3D_PER_CUBE.get()) {
            return ShapeResult.ShapeAlgorithm.LIONFISH_PER_CUBE;
        }
        
        if (typeInfo.isGeo()) {
            return ShapeResult.ShapeAlgorithm.GEO_PER_CUBE;
        }
        
        if (typeInfo.isBewlr()) {
            return ShapeResult.ShapeAlgorithm.BEWLR_3D_INFLATE;
        }
        
        if (typeInfo.isArmor() || typeInfo.isElytra()) {
            return ShapeResult.ShapeAlgorithm.ARMOR_PER_CUBE;
        }
        
        return model.isGui3d() ? ShapeResult.ShapeAlgorithm.FLAT_VERTEX_NORMAL : ShapeResult.ShapeAlgorithm.FLAT_SCREEN_OFFSET;
    }

    /**
     * 创建空结果。
     */
    private ShapeResult createEmptyResult() {
        return new ShapeResult(
                true, false, true,
                ShapeResult.ShapeAlgorithm.FLAT_SCREEN_OFFSET,
                new ArrayList<>(), new HashMap<>(), new ArrayList<>(),
                new ArrayList<>(), -1f, false
        );
    }

    /**
     * 获取源贴图的原始 NativeImage。
     */
    private NativeImage spriteOriginalImage(TextureAtlasSprite sprite) {
        try {
            Field originalImageField = sprite.getClass().getSuperclass()
                    .getDeclaredField("originalImage");
            originalImageField.setAccessible(true);
            return (NativeImage) originalImageField.get(sprite);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算平均亮度。
     */
    private float averageLuma(NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int step = Math.max(1, (w * h) / 4096);
        long sum = 0, count = 0;
        
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
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
        
        return (float) (sum / (double) (count * 1000 * 255));
    }

    /**
     * 清空缓存。
     */
    public void invalidateCache() {
        shapeCache.clear();
    }

    /**
     * 顶点位置 key。
     */
    private record Position(float x, float y, float z) {
    }
}
