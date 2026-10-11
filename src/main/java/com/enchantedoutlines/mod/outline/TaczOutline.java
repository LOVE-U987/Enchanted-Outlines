package com.enchantedoutlines.mod.outline;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * TACZ(The Arsenal Collection Z)与 Hazen'N Stuff 命名空间判定(纯静态工具类)。
 * <p>
 * <b>2026-10-02 全局缓冲防护统一</b>:TACZ 的专项模型复刻路径(renderBedrock)经实测
 * 仍错位(1.1.8 存在复刻之外的变换来源),已删除。全局缓冲替换已统一并入
 * BewlrCapture.capturePlain(捕获窗口内恒替换 RenderBuffers.bufferSource):TACZ
 * 直写全局的渲染链被整体写进捕获器 —— v2(identity)直接捕获成功(每帧 1 次渲染
 * 调用,描边随动画);v1(真实 pose)回退语义不变(捕获 = 最终绘制坐标,原样发射)。
 * 泄漏根治:未替换全局时直写全局渲染器会把 identity 顶点漏进真实渲染管线
 * (flush 时被解释为相机/GUI 空间 → 视野错位图形)。
 * <p>
 * 本类只保留命名空间判定(派发逻辑的开关门禁与 TACZ 第一人称跳过判断用),
 * 不再持有渲染器实例。
 */
final class TaczOutline {

    /** 工具类:禁止实例化。 */
    private TaczOutline() {
    }

    /**
     * 物品注册 ID 是否属于 TACZ 命名空间。
     * <p>
     * TACZ 枪械(如 {@code tacz:modern_kinetic_gun})是<b>自绘 BEWLR</b>(渲染器
     * GunItemRendererWrapper extends AnimateGeoItemRenderer,simplebedrockmodel 的
     * Bedrock 骨骼):GUI 本体 = 2D slot 纹理平面,手持本体 = 3D Bedrock 模型,既无静态
     * 模型字段也不属于 GeckoLib/AzureLib/Lionfish 体系 → 由 BEWLR 派发逻辑按命名空间
     * 特殊处理(第一人称跳过 / 其余 context 走捕获;全局缓冲防护已统一并入
     * capturePlain,直写全局的渲染链被整体捕获)。
     *
     * @param stack 物品
     * @return true = TACZ 物品
     */
    static boolean isTaczNamespace(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && ("tacz".equals(id.getNamespace())
                || "thearsenalcollection".equals(id.getNamespace()));
    }

    /**
     * 物品注册 ID 是否为 Hazen'N Stuff 命名空间({@code hazennstuff:})。
     * <p>
     * Hazen 武器大量为 GeckoLib GeoItem(StarfuryRenderer/黑曜石巨剑等),由 BEWLR
     * 派发逻辑的 Hazen 分支处理:先走 Geo 贴图取样路径(形状由本体贴图 alpha 决定,
     * 扁平 quad 不会膨胀成整块平面),失败再回退捕获兜底。受
     * {@code Config.ENABLE_HAZEN_COMPAT} 控制(默认开启);关闭时整个命名空间的
     * BEWLR/Geo 物品不描边。普通扁平物品(非 customRenderer,走扁平形状纹理)与
     * AzureLib 盔甲(独立 HumanoidArmorLayer 路径)不受影响。
     *
     * @param stack 物品
     * @return true = Hazen'N Stuff 物品
     */
    static boolean isHazenNamespace(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "hazennstuff".equals(id.getNamespace());
    }
}
