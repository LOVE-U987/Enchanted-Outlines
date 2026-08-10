package com.enchantedoutlines.mod.outline;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * {@link RenderStateShard} 的 protected 常量访问器(1.20.1 专属适配)。
 * <p>
 * 1.20.1 的 {@link RenderStateShard} 大多数状态常量是 {@code protected}
 * (1.21.1 才改为 public)。{@link RenderType} 继承 {@link RenderStateShard},
 * 因此创建一个 RenderType 子类即可在静态字段中转发这些常量,
 * 供描边自定义 RenderType 使用(1.21.1 原代码直接引用 public 常量)。
 */
public final class RenderTypeAccess extends RenderType {

    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.TextureStateShard BLOCK_SHEET = RenderStateShard.BLOCK_SHEET;
    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.TransparencyStateShard TRANSLUCENT_TRANSPARENCY = RenderStateShard.TRANSLUCENT_TRANSPARENCY;
    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.CullStateShard NO_CULL = RenderStateShard.NO_CULL;
    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.DepthTestStateShard LEQUAL_DEPTH_TEST = RenderStateShard.LEQUAL_DEPTH_TEST;
    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.WriteMaskStateShard COLOR_WRITE = RenderStateShard.COLOR_WRITE;
    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.LightmapStateShard NO_LIGHTMAP = RenderStateShard.NO_LIGHTMAP;
    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.LightmapStateShard LIGHTMAP = RenderStateShard.LIGHTMAP;
    /** 1.20.1 下为 protected,经子类转发公开。 */
    public static final RenderStateShard.OverlayStateShard OVERLAY = RenderStateShard.OVERLAY;
    /** 1.20.1 下为 protected,经子类转发公开(内置 emissive shader,光影兼容模式)。 */
    public static final RenderStateShard.ShaderStateShard RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER =
            RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER;

    private RenderTypeAccess() {
        super("enchanted_outlines_access", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                256, false, false, () -> {
                }, () -> {
        });
    }
}
