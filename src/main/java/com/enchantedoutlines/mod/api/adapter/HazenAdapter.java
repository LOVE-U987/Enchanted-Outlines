package com.enchantedoutlines.mod.api.adapter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Hazen'N Stuff 专项适配器。
 * <p>
 * 处理 Hazen'N Stuff 模组的特殊渲染需求。
 * <p>
 * Hazen'N Stuff 使用 BEWLR 协议渲染武器，需要特殊处理 BEWLR 描边。
 */
public class HazenAdapter implements ItemModelAdapter {

    public static final HazenAdapter INSTANCE = new HazenAdapter();

    @Override
    public String getName() {
        return "HazenAdapter";
    }

    @Override
    public int getPriority() {
        // Hazen 优先级高于通用 BEWLR，确保先匹配
        return 50;
    }

    @Override
    public boolean matches(Item item) {
        try {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) {
                return false;
            }
            // Hazen'N Stuff 物品通常在 hazenn 或 hazennstuff 命名空间下
            String itemIdStr = itemId.toString();
            return itemIdStr.startsWith("hazenn:") || itemIdStr.startsWith("hazennstuff:");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Hazen'N Stuff 武器字段名映射。
     * <p>
     * 某些 Hazen 武器字段名不遵循通用约定，需显式映射。
     * <p>
     * 字段名格式：<b>去掉注册 ID 中的 _THE_ 和末尾的 _WEAPON</b> + "_MODEL"。
     */
    private static final java.util.Map<String, String> HAZEN_WEAPON_FIELD_MAP = java.util.Map.of(
        "hazenn:wrath_of_the_desert", "WRATH_OF_DESERT_MODEL",      // 愤怒：荒漠之怒 (去掉 _THE_)
        "hazenn:wrath_of_the_desert_weapon", "WRATH_OF_DESERT_MODEL", // 愤怒：荒漠之怒 (完整版)
        "hazenn:wrath_weapon", "WRATH_MODEL",                        // 愤怒：星怒 (简化版)
        "hazenn:wrath", "WRATH_MODEL"                                // 愤怒：星怒 (最简版)
    );

    @Nullable
    @Override
    public com.enchantedoutlines.mod.api.core.ItemTypeInfo resolve(Item item) {
        if (!matches(item)) {
            return null;
        }
        
        // Hazen'N Stuff 使用 BEWLR 协议渲染武器
        // 类型信息：isBewlr=true, isGeo=false, isAzureLib=false, isLionfish=false, isTacz=false, isFlat=false,
        //           bewlrAdapter=null, geoAdapter=null, lionfishAdapter=null, taczAdapter=null, hazenAdapter=this,
        //           isArmor=false, isElytra=false, isProjectile=false
        return new com.enchantedoutlines.mod.api.core.ItemTypeInfo(
            item, true, false, false, false, false, false, null, null, null, null, HazenAdapter.INSTANCE, false, false, false
        );
    }
}
