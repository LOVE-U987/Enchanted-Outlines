package com.enchantedoutlines.mod.api.core;

import com.enchantedoutlines.mod.api.adapter.BewlrAdapter;
import com.enchantedoutlines.mod.api.adapter.GeoAdapter;
import com.enchantedoutlines.mod.api.adapter.HazenAdapter;
import com.enchantedoutlines.mod.api.adapter.ItemModelAdapter;
import com.enchantedoutlines.mod.api.adapter.LionfishAdapter;
import com.enchantedoutlines.mod.api.adapter.TaczAdapter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品模型注册表。
 * <p>
 * 负责管理所有适配器，检测物品的模型类型，并缓存结果。
 */
public final class ItemModelRegistry {

    public static final ItemModelRegistry INSTANCE = new ItemModelRegistry();

    /** 已注册的适配器列表。 */
    private final List<ItemModelAdapter> adapters = new ArrayList<>();

    /** 物品类型信息缓存 (Item → ItemTypeInfo)。 */
    private final Map<ResourceLocation, ItemTypeInfo> typeCache = new HashMap<>();

    private ItemModelRegistry() {
        // 注册内置适配器
        registerAdapter(BewlrAdapter.INSTANCE);
        registerAdapter(GeoAdapter.INSTANCE);
        registerAdapter(HazenAdapter.INSTANCE);
        registerAdapter(LionfishAdapter.INSTANCE);
        registerAdapter(TaczAdapter.INSTANCE);
    }

    /**
     * 注册适配器。
     */
    public void registerAdapter(ItemModelAdapter adapter) {
        adapters.add(adapter);
        // 按优先级排序
        adapters.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    }

    /**
     * 获取所有适配器。
     */
    public List<ItemModelAdapter> getAdapters() {
        return new ArrayList<>(adapters);
    }

    /**
     * 解析物品的类型信息。
     */
    public ItemTypeInfo resolve(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        if (key == null) {
            return null;
        }

        ItemTypeInfo cached = typeCache.get(key);
        if (cached != null) {
            return cached;
        }

        ItemTypeInfo result = buildTypeInfo(item, key);
        typeCache.put(key, result);
        return result;
    }

    /**
     * 构建物品类型信息。
     */
    private ItemTypeInfo buildTypeInfo(Item item, ResourceLocation key) {
        boolean isBewlr = false;
        boolean isGeo = false;
        boolean isAzureLib = false;
        boolean isLionfish = false;
        boolean isTacz = false;
        boolean isFlat = true;
        boolean isArmor = false;
        boolean isElytra = false;
        boolean isProjectile = false;

        BewlrAdapter bewlrAdapter = null;
        GeoAdapter geoAdapter = null;
        HazenAdapter hazenAdapter = null;
        LionfishAdapter lionfishAdapter = null;
        TaczAdapter taczAdapter = null;

        // 检测是否为扁平物品
        String itemName = key.getPath().toLowerCase();
        isFlat = !itemName.contains("3d") && !itemName.contains("model");

        // 检测是否为盔甲
        if (itemName.contains("helmet") || itemName.contains("chest") || 
            itemName.contains("leggings") || itemName.contains("boots") ||
            itemName.contains("armor") || itemName.contains("suit")) {
            isArmor = true;
        }

        // 检测是否为鞘翅
        if (itemName.contains("elytra") || itemName.contains("wing")) {
            isElytra = true;
        }

        // 检测是否为投掷物
        if (itemName.contains("trident") || itemName.contains("throw") || 
            itemName.contains("projectile") || itemName.contains("arrow") ||
            itemName.contains("bolt")) {
            isProjectile = true;
        }

        // 检测是否为 Hazen'N Stuff 物品
        if (key.getNamespace().equals("hazenn") || key.getNamespace().equals("hazennstuff")) {
            isBewlr = true;
            hazenAdapter = HazenAdapter.INSTANCE;
        }

        // 检测是否为真菌感染 (Spore) 物品
        if (key.getNamespace().equals("spore") || key.getNamespace().equals("harbinger")) {
            isBewlr = true;
            // Spore 使用通用 BEWLR 路径，不需要特殊 adapter
        }

        // 检测是否为 TACZ 物品
        // TACZ 使用 GeoLib 渲染，不是 BEWLR，需要显式设置 isBewlr=false
        if (key.getNamespace().equals("tacz") || key.getNamespace().equals("thearsenalcollection")) {
            isTacz = true;
            taczAdapter = TaczAdapter.INSTANCE;
            // ⚠️ 关键：TACZ 是 Geo 物品，不是 BEWLR，必须显式跳过 isBewlrItem 检测
            //     否则进入 renderBewlrEntityOutline 后 fallback 失败
            isBewlr = false;
        }

        // 检测为 AzureLib 盔甲
        if (isAzureLibArmor(item)) {
            isAzureLib = true;
            isGeo = true; // AzureLib 基于 Geo
            geoAdapter = GeoAdapter.INSTANCE;
        }

        // 检测是否为 LionfishAPI 物品
        if (isLionfishItem(item)) {
            isLionfish = true;
            lionfishAdapter = LionfishAdapter.INSTANCE;
        }

        // 检测是否为 BEWLR 物品
        if (isBewlrItem(item)) {
            isBewlr = true;
            bewlrAdapter = BewlrAdapter.INSTANCE;
        }

        // 检测是否为 Geo 物品 (非 AzureLib)
        if (isGeoItem(item) && !isAzureLib) {
            isGeo = true;
            if (geoAdapter == null) {
                geoAdapter = GeoAdapter.INSTANCE;
            }
        }

        return new ItemTypeInfo(
            item, isBewlr, isGeo, isAzureLib, isLionfish, isTacz, isFlat,
            bewlrAdapter, geoAdapter, lionfishAdapter, taczAdapter, hazenAdapter,
            isArmor, isElytra, isProjectile
        );
    }

    /**
     * 检查是否为 AzureLib 盔甲。
     */
    private boolean isAzureLibArmor(Item item) {
        String className = item.getClass().getName();
        return className.contains("azurelib") || className.contains("AzureLib");
    }

    /**
     * 检查是否为 LionfishAPI 物品。
     */
    private boolean isLionfishItem(Item item) {
        String className = item.getClass().getName();
        return className.contains("lionfish") || className.contains("Lionfish");
    }

    /**
     * 检查是否为 BEWLR 物品。
     */
    private boolean isBewlrItem(Item item) {
        // BEWLR 物品通常有特定的渲染器类
        try {
            Class<?> rendererClass = Class.forName("com.bawnorton.more_item_model_rework.renderer.ItemStackRenderer");
            return rendererClass.isAssignableFrom(item.getClass()) ||
                   item.getClass().getDeclaredFields().length > 0;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 检查是否为 Geo 物品 (非 AzureLib)。
     */
    private boolean isGeoItem(Item item) {
        String className = item.getClass().getName();
        return className.contains("geckolib") && !className.contains("azurelib");
    }

    /**
     * 清空缓存。
     */
    public void invalidateCache() {
        typeCache.clear();
    }
}
