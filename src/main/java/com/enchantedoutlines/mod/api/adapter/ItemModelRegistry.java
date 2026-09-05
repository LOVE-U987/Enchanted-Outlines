package com.enchantedoutlines.mod.api.adapter;

import com.enchantedoutlines.mod.api.core.ItemTypeInfo;
import com.enchantedoutlines.mod.config.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品模型类型注册表。
 * <p>
 * 负责解析物品的类型信息并缓存结果，避免重复反射判断。
 */
public final class ItemModelRegistry {

    public static final ItemModelRegistry INSTANCE = new ItemModelRegistry();

    /** 物品 → 类型信息 (缓存)。 */
    private final ConcurrentHashMap<Item, ItemTypeInfo> typeInfoCache = new ConcurrentHashMap<>();

    /** 注册的适配器列表 (按优先级顺序)。 */
    private final List<ItemModelAdapter> adapters = new ArrayList<>();

    private ItemModelRegistry() {
        // 注册内置适配器
        registerAdapter(new BewlrAdapter());
        registerAdapter(new GeoAdapter());
        registerAdapter(new LionfishAdapter());
        registerAdapter(new TaczAdapter());
    }

    /**
     * 解析物品的类型信息。
     *
     * @param item 物品
     * @return 物品类型信息
     */
    public ItemTypeInfo resolve(Item item) {
        ItemTypeInfo cached = typeInfoCache.get(item);
        if (cached != null) {
            return cached;
        }

        ItemTypeInfo info = buildTypeInfo(item);
        typeInfoCache.put(item, info);
        return info;
    }

    /**
     * 构建物品类型信息。
     */
    private ItemTypeInfo buildTypeInfo(Item item) {
        // 基础属性
        boolean isFlat = !is3dModel(item);
        boolean isArmor = isArmorItem(item);
        boolean isElytra = isElytra(item);
        boolean isProjectile = isProjectile(item);

        // 尝试各适配器
        BewlrAdapter bewlrAdapter = null;
        GeoAdapter geoAdapter = null;
        LionfishAdapter lionfishAdapter = null;
        TaczAdapter taczAdapter = null;
        HazenAdapter hazenAdapter = null;

        boolean isBewlr = false;
        boolean isGeo = false;
        boolean isAzureLib = false;
        boolean isLionfish = false;
        boolean isTacz = false;
        boolean isHazen = false;

        // 按优先级检查各适配器
        for (ItemModelAdapter adapter : adapters) {
            if (adapter instanceof BewlrAdapter && adapter.matches(item)) {
                isBewlr = true;
                bewlrAdapter = (BewlrAdapter) adapter;
            } else if (adapter instanceof GeoAdapter && adapter.matches(item)) {
                isGeo = true;
                geoAdapter = (GeoAdapter) adapter;
                if (geoAdapter.isAzureLib(item)) {
                    isAzureLib = true;
                }
            } else if (adapter instanceof LionfishAdapter && adapter.matches(item)) {
                isLionfish = true;
                lionfishAdapter = (LionfishAdapter) adapter;
            } else if (adapter instanceof TaczAdapter && adapter.matches(item)) {
                isTacz = true;
                taczAdapter = (TaczAdapter) adapter;
            } else if (adapter instanceof HazenAdapter && adapter.matches(item)) {
                isHazen = true;
                hazenAdapter = (HazenAdapter) adapter;
            }
        }

        return new ItemTypeInfo(
            item, isBewlr, isGeo, isAzureLib, isLionfish, isTacz, isFlat,
            bewlrAdapter, geoAdapter, lionfishAdapter, taczAdapter, hazenAdapter,
            isArmor, isElytra, isProjectile
        );
    }

    /**
     * 判断物品是否为 3D 模型。
     */
    private boolean is3dModel(Item item) {
        try {
            // 通过 Item 的默认渲染器判断
            // 简化处理：三叉戟/盾牌/方块等为 3D
            return item instanceof net.minecraft.world.item.TridentItem ||
                   item instanceof net.minecraft.world.item.ShieldItem ||
                   item instanceof net.minecraft.world.item.BlockItem;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断物品是否为盔甲。
     */
    private boolean isArmorItem(Item item) {
        return item instanceof net.minecraft.world.item.ArmorItem;
    }

    /**
     * 判断物品是否为鞘翅。
     */
    private boolean isElytra(Item item) {
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        return loc != null && "minecraft:elytra".equals(loc.toString());
    }

    /**
     * 判断物品是否为投掷物。
     */
    private boolean isProjectile(Item item) {
        ResourceLocation loc = BuiltInRegistries.ITEM.getKey(item);
        return loc != null && "minecraft:trident".equals(loc.toString());
    }

    /**
     * 注册自定义适配器。
     *
     * @param adapter 适配器
     */
    public void registerAdapter(ItemModelAdapter adapter) {
        adapters.add(adapter);
    }

    /**
     * 获取所有注册的适配器。
     */
    public List<ItemModelAdapter> getAdapters() {
        return new ArrayList<>(adapters);
    }

    /**
     * 清空类型信息缓存。
     */
    public void invalidateCache() {
        typeInfoCache.clear();
    }
}
