package com.enchantedoutlines.mod.platform;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * 1.21.1 NeoForge 平台实现：逐项委托给 NeoForge 提供的原版工厂。
 * <p>
 * 本类只做转发，不引入任何额外语义；1.20.1 Forge 版本将提供同契约的另一实现。
 */
public final class NeoForgePlatformSupport implements Platform.Support {

    /**
     * 构造带命名空间的资源位置。
     *
     * @param namespace 命名空间
     * @param path 路径
     * @return 资源位置
     */
    @Override
    public ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    /**
     * 构造 {@code minecraft} 命名空间下的资源位置。
     *
     * @param path 路径
     * @return 资源位置
     */
    @Override
    public ResourceLocation vanilla(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    /**
     * 解析字符串形式的资源位置。
     *
     * @param value 待解析字符串
     * @return 解析结果；非法输入返回 null
     */
    @Override
    public ResourceLocation tryParse(String value) {
        return ResourceLocation.tryParse(value);
    }

    /**
     * 查询模组是否已加载。
     *
     * @param modId 模组 id
     * @return true = 已加载
     */
    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}
