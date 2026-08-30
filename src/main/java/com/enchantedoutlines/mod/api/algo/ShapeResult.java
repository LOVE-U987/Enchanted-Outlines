package com.enchantedoutlines.mod.api.algo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 形状检测结果。
 * <p>
 * 包含物品形状分析的所有信息，用于后续渲染决策。
 */
public record ShapeResult(
    /** 是否为扁平物品 */
    boolean isFlat,
    /** 是否为 3D 物品 */
    boolean is3d,
    /** 是否为单一材质 */
    boolean isSingleTexture,
    /** 形状算法类型 */
    ShapeAlgorithm algorithm,
    /** 模型的 quads */
    List<BakedQuad> quads,
    /** 按 sprite 分组的 quads (扁平物品用) */
    Map<TextureAtlasSprite, List<BakedQuad>> quadsBySprite,
    /** 每个 quad 的法线 (用于外扩) */
    List<Vec3i> quadNormals,
    /** 外扩预处理顶点 (用于 3D 外扩) */
    List<ExpandVertex[]> expandVertices,
    /** 主材质的平均亮度 (用于曝光调整) */
    float mainLuma,
    /** 是否全部使用 stone 精灵 (盾牌/三叉戟盒模型) */
    boolean allStone
) {
    /**
     * 形状算法枚举。
     */
    public enum ShapeAlgorithm {
        /** 通用扁平 (屏幕偏移) */
        FLAT_SCREEN_OFFSET,
        /** 通用 3D(顶点法线外扩) */
        FLAT_VERTEX_NORMAL,
        /** BEWLR 3D(几何放大壳) */
        BEWLR_3D_INFLATE,
        /** Lionfish 逐 cube 外扩 */
        LIONFISH_PER_CUBE,
        /** Geo 骨骼逐 cube 外扩 */
        GEO_PER_CUBE,
        /** 盔甲/鞘翅逐 cube 放大 */
        ARMOR_PER_CUBE
    }

    /**
     * 外扩预处理顶点。
     */
    public record ExpandVertex(
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz
    ) {
    }

    /**
     * 渲染扁平物品形状 (使用形状纹理)。
     */
    public void renderFlatShape(PoseStack pose, VertexConsumer consumer,
                                 int color, float thickness,
                                 OutlineShapeTextureProvider shapeTextureProvider) {
        pose.pushPose();
        try {
            for (Map.Entry<TextureAtlasSprite, List<BakedQuad>> entry : quadsBySprite.entrySet()) {
                TextureAtlasSprite sprite = entry.getKey();
                List<BakedQuad> spriteQuads = entry.getValue();
                
                // 生成/获取形状纹理
                int packedColor = 0xFF000000 | (color & 0xFFFFFF);
                shapeTextureProvider.provideShapeTexture(sprite, color, false, loc -> {
                    // 渲染 quad
                    for (BakedQuad quad : spriteQuads) {
                        renderQuad(pose, consumer, quad, loc, packedColor, thickness);
                    }
                });
            }
        } finally {
            pose.popPose();
        }
    }

    /**
     * 渲染 3D 物品 (顶点法线外扩)。
     */
    public void render3dExpand(PoseStack pose, VertexConsumer consumer,
                                int color, float offset) {
        pose.pushPose();
        try {
            for (int qi = 0; qi < quads.size(); qi++) {
                Vec3i n = quadNormals.get(qi);
                for (ExpandVertex v : expandVertices.get(qi)) {
                    // 顶点外扩
                    float ex = v.x() + v.nx() * offset;
                    float ey = v.y() + v.ny() * offset;
                    float ez = v.z() + v.nz() * offset;
                    
                    consumer.addVertex(
                            pose.last().pose()[0][0] * ex + pose.last().pose()[0][1] * ey + pose.last().pose()[0][2] * ez,
                            pose.last().pose()[1][0] * ex + pose.last().pose()[1][1] * ey + pose.last().pose()[1][2] * ez,
                            pose.last().pose()[2][0] * ex + pose.last().pose()[2][1] * ey + pose.last().pose()[2][2] * ez,
                            color, v.u(), v.v()
                    );
                }
            }
        } finally {
            pose.popPose();
        }
    }

    private void renderQuad(PoseStack pose, VertexConsumer consumer, BakedQuad quad,
                            net.minecraft.resources.ResourceLocation texture,
                            int color, float thickness) {
        // 简化处理：实际应完整实现 quad 渲染
        int[] vertices = quad.getVertices();
        for (int i = 0; i < vertices.length; i += 8) {
            float x = Float.intBitsToFloat(vertices[i]);
            float y = Float.intBitsToFloat(vertices[i + 1]);
            float z = Float.intBitsToFloat(vertices[i + 2]);
            float u = Float.intBitsToFloat(vertices[i + 4]);
            float v = Float.intBitsToFloat(vertices[i + 5]);
            
            consumer.addVertex(
                    pose.last().pose()[0][0] * x + pose.last().pose()[0][1] * y + pose.last().pose()[0][2] * z,
                    pose.last().pose()[1][0] * x + pose.last().pose()[1][1] * y + pose.last().pose()[1][2] * z,
                    pose.last().pose()[2][0] * x + pose.last().pose()[2][1] * y + pose.last().pose()[2][2] * z,
                    color, u, v
            );
        }
    }
}
