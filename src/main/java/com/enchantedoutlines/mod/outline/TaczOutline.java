package com.enchantedoutlines.mod.outline;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * TACZ(The Arsenal Collection Z)与 Hazen'N Stuff 命名空间判定(纯静态工具类)。
 * <p>
 * <b>2026-10-18 TaCZ 兼容重做</b>:世界场景(第一/第三人称/GROUND/FIXED)的 TaCZ
 * 枪械描边改由 <b>TaCZ 内部注入</b>走查 —— {@code TaczGunModelMixin}(TaCZ
 * {@code BedrockGunModel.render} 六参重载 TAIL,经 {@code TaczMixinPlugin} 条件
 * 应用)+ {@link TaczGunOutline}(模型树走查外扩壳)。旧路径为何作废(反编译
 * 实证):simplebedrockmodel IFP 系统取消原版第一人称渲染 → 原版注入点不执行;
 * LOD/附件/改枪的代码级模型修改 → 捕获派(调 renderByItem)拿到的几何与本体
 * 不同步。GUI(背包 slot 2D 平面)不经新 mixin,仍走 v2.1 平坦捕获(用户决策
 * 保留)。本类保持纯静态命名空间判定(派发链门禁用),不持有渲染器实例。
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
     * 特殊处理(2026-10-18 重做:世界 context 由 TaczGunModelMixin 走查接管,GUI 走
     * 平坦捕获,见类注释)。
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
