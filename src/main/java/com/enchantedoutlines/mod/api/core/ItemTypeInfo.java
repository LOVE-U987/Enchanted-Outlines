package com.enchantedoutlines.mod.api.core;

import com.enchantedoutlines.mod.api.adapter.BewlrAdapter;
import com.enchantedoutlines.mod.api.adapter.GeoAdapter;
import com.enchantedoutlines.mod.api.adapter.HazenAdapter;
import com.enchantedoutlines.mod.api.adapter.ItemModelAdapter;
import com.enchantedoutlines.mod.api.adapter.LionfishAdapter;
import com.enchantedoutlines.mod.api.adapter.TaczAdapter;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * 物品类型信息。
 * <p>
 * 包含物品的所有渲染相关信息，由 {@link com.enchantedoutlines.mod.api.core.ItemModelRegistry} 解析并缓存。
 */
public record ItemTypeInfo(
    /** 物品 */
    Item item,
    /** 是否为 BEWLR 物品 */
    boolean isBewlr,
    /** 是否为 Geo 骨骼物品/盔甲 */
    boolean isGeo,
    /** 是否为 AzureLib 盔甲 */
    boolean isAzureLib,
    /** 是否为 LionfishAPI 物品 */
    boolean isLionfish,
    /** 是否为 TACZ 物品 */
    boolean isTacz,
    /** 是否为扁平物品 (非 3D 模型) */
    boolean isFlat,
    /** BEWLR 适配器 (若为 BEWLR 物品) */
    @Nullable BewlrAdapter bewlrAdapter,
    /** Geo 适配器 (若为 Geo 物品/盔甲) */
    @Nullable GeoAdapter geoAdapter,
    /** Lionfish 适配器 (若为 LionfishAPI 物品) */
    @Nullable LionfishAdapter lionfishAdapter,
    /** TACZ 适配器 (若为 TACZ 物品) */
    @Nullable TaczAdapter taczAdapter,
    /** Hazen'N Stuff 适配器 (若为 Hazen'N Stuff 物品) */
    @Nullable HazenAdapter hazenAdapter,
    /** 是否为盔甲 */
    boolean isArmor,
    /** 是否为鞘翅 */
    boolean isElytra,
    /** 是否为投掷物 (三叉戟等) */
    boolean isProjectile
) {
    /**
     * 判断是否需要使用 3D 渲染路径。
     */
    public boolean needs3dRender() {
        return isGeo || isLionfish || isBewlr || !isFlat;
    }

    /**
     * 获取主要的适配器 (按优先级：TACZ → Lionfish → Geo → Hazen → BEWLR → null)。
     * 返回 Object 以避免循环依赖。
     */
    @Nullable
    public ItemModelAdapter getPrimaryAdapter() {
        if (taczAdapter != null) return taczAdapter;
        if (lionfishAdapter != null) return lionfishAdapter;
        if (geoAdapter != null) return geoAdapter;
        if (hazenAdapter != null) return hazenAdapter;
        if (bewlrAdapter != null) return bewlrAdapter;
        return null;
    }

    /**
     * 是否为专项模组物品 (BEWLR/Geo/Lionfish/TACZ/Hazen)。
     */
    public boolean isModded() {
        return isBewlr || isGeo || isLionfish || isTacz || hazenAdapter != null;
    }
}
