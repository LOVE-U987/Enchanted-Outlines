package com.enchantedoutlines.mod.mixin;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link ElytraLayer} 私有 {@code elytraModel} 字段。
 * <p>
 * 鞘翅描边必须使用 ElytraLayer <b>正在渲染本体用的同一个模型实例</b>,
 * 这样描边与本体姿势 100% 同步(static 缓存的独立实例可能因跨帧/多实体
 * 渲染而姿势残留,导致描边与本体错位)。
 */
@Mixin(ElytraLayer.class)
public interface ElytraLayerAccessor {

    @Accessor("elytraModel")
    ElytraModel<?> enchantedoutlines$getElytraModel();
}
