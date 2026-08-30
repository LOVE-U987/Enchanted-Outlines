package com.enchantedoutlines.mod.api.algo;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 形状纹理提供者接口。
 * <p>
 * 负责为扁平物品生成/提供"描边色形状纹理"(RGBA=描边色，A=贴图 alpha)。
 */
public interface OutlineShapeTextureProvider {

    /**
     * 获取描边色形状纹理。
     *
     * @param sprite       源贴图
     * @param color        描边颜色 (RGB)
     * @param reduceExposure 是否压暗曝光
     * @return 纹理位置，或 null 表示失败
     */
    @Nullable
    ResourceLocation getShapeTexture(TextureAtlasSprite sprite, int color, boolean reduceExposure);

    /**
     * 检查是否需要使用 vanilla shader fallback(光影兼容)。
     */
    boolean needVanillaShaderFallback();

    /**
     * 设置描边 alpha 强化倍率。
     */
    void setOutlineAlphaBoost(float boost);

    /**
     * 设置描边是否硬切不透明。
     */
    void setOutlineCutout(boolean opaque);

    /**
     * 世界 emissive outline render type。
     */
    java.util.function.Supplier<net.minecraft.client.renderer.RenderType> worldEmissiveOutlineRenderType();

    /**
     * 世界 emissive outline render type (带纹理)。
     */
    java.util.function.Supplier<net.minecraft.client.renderer.RenderType> worldEmissiveOutlineRenderType(ResourceLocation texture);

    /**
     * 纯色描边 render type。
     */
    net.minecraft.client.renderer.RenderType armorOutlineRenderType(ResourceLocation texture, int color);
}
