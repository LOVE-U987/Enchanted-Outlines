package com.enchantedoutlines.mod.platform;

import net.minecraft.resources.ResourceLocation;

/**
 * 版本无关的平台门面：屏蔽 1.21.1 NeoForge 与 1.20.1 Forge 在
 * {@link ResourceLocation} 工厂与模组加载查询上的 API 差异。
 * <p>
 * 共享源码（common/）只允许通过本门面访问这些能力；具体实现由各版本的
 * {@link Support} 在模组构造阶段注入。设计目标是最小表面积：只收纳
 * 「两个版本签名不同、无法直接编译通过」的 API，其余一律直接使用原版类型。
 * <p>
 * 线程安全：{@link #install(Support)} 只在模组构造期调用一次，
 * 其余读取走 volatile，避免渲染线程看到未发布实例。
 */
public final class Platform {

    /** 各版本注入的实现；未安装时所有调用抛 IllegalStateException。 */
    private static volatile Support support;

    private Platform() {
    }

    /**
     * 安装平台实现（每个版本在模组构造器最早处调用一次）。
     *
     * @param impl 平台实现，不得为 null
     * @throws IllegalStateException 实现为 null 或重复安装
     */
    public static void install(Support impl) {
        if (impl == null) {
            throw new IllegalStateException("Platform support must not be null");
        }
        if (support != null) {
            throw new IllegalStateException("Platform support already installed");
        }
        support = impl;
    }

    /**
     * 构造带命名空间的资源位置。
     *
     * @param namespace 命名空间，如 {@code enchanted_outlines}
     * @param path 路径，如 {@code textures/white.png}
     * @return 资源位置
     */
    public static ResourceLocation id(String namespace, String path) {
        return require().id(namespace, path);
    }

    /**
     * 构造 {@code minecraft} 命名空间下的资源位置。
     *
     * @param path 路径，如 {@code block/stone}
     * @return 资源位置
     */
    public static ResourceLocation vanilla(String path) {
        return require().vanilla(path);
    }

    /**
     * 解析字符串形式的资源位置。
     *
     * @param value 形如 {@code namespace:path} 或 {@code path} 的字符串
     * @return 解析结果；非法输入返回 null
     */
    public static ResourceLocation tryParse(String value) {
        return require().tryParse(value);
    }

    /**
     * 查询模组是否已加载。
     *
     * @param modId 模组 id
     * @return true = 已加载
     */
    public static boolean isModLoaded(String modId) {
        return require().isModLoaded(modId);
    }

    /**
     * 取已安装的实现。
     *
     * @return 平台实现
     * @throws IllegalStateException 尚未安装
     */
    private static Support require() {
        Support impl = support;
        if (impl == null) {
            throw new IllegalStateException(
                    "Platform support not installed; call Platform.install in the mod constructor");
        }
        return impl;
    }

    /**
     * 平台能力契约：每个 Minecraft/加载器版本提供一份实现。
     */
    public interface Support {

        /**
         * 构造带命名空间的资源位置。
         *
         * @param namespace 命名空间
         * @param path 路径
         * @return 资源位置
         */
        ResourceLocation id(String namespace, String path);

        /**
         * 构造 {@code minecraft} 命名空间下的资源位置。
         *
         * @param path 路径
         * @return 资源位置
         */
        ResourceLocation vanilla(String path);

        /**
         * 解析字符串形式的资源位置。
         *
         * @param value 待解析字符串
         * @return 解析结果；非法输入返回 null
         */
        ResourceLocation tryParse(String value);

        /**
         * 查询模组是否已加载。
         *
         * @param modId 模组 id
         * @return true = 已加载
         */
        boolean isModLoaded(String modId);
    }
}
