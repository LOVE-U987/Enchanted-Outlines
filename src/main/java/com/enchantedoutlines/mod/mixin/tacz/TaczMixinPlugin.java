package com.enchantedoutlines.mod.mixin.tacz;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * TACZ 专项 mixin 配置插件(2026-10-18 TaCZ 兼容重做的安全门)。
 * <p>
 * 本配置(enchanted_outlines.tacz.mixins.json)内的 mixin 目标是 <b>第三方模组
 * TACZ 的类</b>({@code com.tacz.guns.client.model.BedrockGunModel})—— TACZ
 * 不在类路径上时若仍然应用,mixin 解析目标失败会直接崩溃。本插件在
 * {@link #shouldApplyMixin} 阶段做类路径探测:TACZ 存在才放行,否则整个配置
 * 内的 mixin 一律不应用(本模组其余功能不受影响)。
 * <p>
 * 探测方式:检查 TACZ 发行 jar 根部的 mixin 配置资源 {@code tacz.mixins.json}
 * 是否可见。<b>纯资源查找,不加载任何 TACZ 类</b> —— 规避 mixin 插件阶段
 * 目标类尚未就绪的时序问题(注入目标类正在被转换时,对它 Class.forName 会
 * 因递归定义而不可靠)。
 */
public final class TaczMixinPlugin implements IMixinConfigPlugin {

    /** TACZ 发行 jar 根部的 mixin 配置资源(存在性 = TACZ 在类路径上)。 */
    private static final String TACZ_MARKER_RESOURCE = "tacz.mixins.json";

    /**
     * 判定 TACZ 是否在类路径上。
     *
     * @return true = TACZ 发行 jar 可见(探测其根部标记资源;零类加载)
     */
    private static boolean isTaczPresent() {
        return TaczMixinPlugin.class.getClassLoader().getResource(TACZ_MARKER_RESOURCE) != null;
    }

    /**
     * 配置加载回调(无额外初始化需求)。
     *
     * @param mixinPackage mixin 包名
     */
    @Override
    public void onLoad(String mixinPackage) {
    }

    /**
     * refmap 配置:NeoForge 1.21.1 运行时即 mojmap 命名,本配置目标又是模组类
     * (不在任何映射表内),无需 refmap。
     *
     * @return null = 不使用 refmap
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * 逐 mixin 应用判定:TACZ 存在才应用。
     *
     * @param targetClassName 注入目标类全名
     * @param mixinClassName  mixin 类全名
     * @return true = TACZ 在类路径上,允许应用
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isTaczPresent();
    }

    /**
     * 目标集合协商回调(无需处理)。
     *
     * @param myTargets    本配置声明的目标
     * @param otherTargets 其他配置声明的目标
     */
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    /**
     * 额外 mixin 列表(全部在 JSON 中声明,不动态追加)。
     *
     * @return null = 无追加
     */
    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * 注入前回调(无需处理)。
     *
     * @param targetClassName 目标类全名
     * @param targetClass     目标类字节码
     * @param mixinClassName  mixin 类全名
     * @param mixinInfo       mixin 元信息
     */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /**
     * 注入后回调(无需处理)。
     *
     * @param targetClassName 目标类全名
     * @param targetClass     目标类字节码
     * @param mixinClassName  mixin 类全名
     * @param mixinInfo       mixin 元信息
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
