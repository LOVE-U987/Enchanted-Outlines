package com.enchantedoutlines.mod.outline;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

import com.enchantedoutlines.mod.EnchantedOutlines;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 资源包轮廓定义加载器:从资源包读取逐物品描边定义,供 {@link ColorResolver} 查询。
 * <p>
 * 扫描本模组命名空间下的
 * {@code assets/enchanted_outlines/outlines/<物品命名空间>/<物品路径>.json},
 * 文件路径即物品 id。跨资源包覆盖语义由原版资源系统保证:两个包提供同一路径文件时,
 * 资源包列表中排前的包胜出({@code listResources} 每路径只保留最顶包),本类无需
 * 任何自定义优先级逻辑。
 * <p>
 * 扫描实现(2026-10-31 修复):不能用 {@code SimpleJsonResourceReloadListener}
 * 的目录扫描 —— 其目录参数相对<b>任意命名空间</b>
 * ({@code assets/<ns>/<directory>/**}),传 "enchanted_outlines/outlines" 会去
 * {@code assets/<ns>/enchanted_outlines/outlines/**}(多一层嵌套)找文件,扫不到
 * 本模组命名空间下的定义,且静默零命中。故继承
 * {@link SimplePreparableReloadListener} 自实现 prepare:全命名空间列出
 * {@code outlines/} 目录后按命名空间过滤。
 * <p>
 * 生命周期:挂 {@code RegisterClientReloadListenersEvent},资源包加载/切换(F3+T、
 * 服务器推送资源包)时自动重扫并整体替换定义表;表为不可变 Map,查询线程与重载线程
 * 均为渲染线程。解析失败/指向不存在物品的定义记录 warn 后跳过,不影响其余定义。
 * 本类不新建渲染缓存(定义表本身即状态,随资源重载刷新),无需登记
 * {@code OutlineRenderer.invalidateCaches()}。
 */
public final class OutlineResources extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    /** JSON 解析器(定义文件自行做字段级校验)。 */
    private static final Gson GSON = new Gson();

    /** 定义文件目录转换器:assets/enchanted_outlines/outlines/**.json。 */
    private static final FileToIdConverter OUTLINES_CONVERTER = FileToIdConverter.json("outlines");

    /** 物品 id → 轮廓定义;不可变,资源重载时整体替换(volatile 保证跨方法可见性)。 */
    private static volatile Map<ResourceLocation, OutlineDef> DEFS = Map.of();

    private OutlineResources() {
    }

    /**
     * 创建挂接到客户端资源管理器的加载器实例。
     *
     * @return 新的加载器(每次资源注册调用一次)
     */
    public static OutlineResources create() {
        return new OutlineResources();
    }

    /**
     * 资源重载扫描:解析本模组命名空间下的全部定义文件。
     *
     * @param resourceManager 资源管理器
     * @param profiler        性能分析器(本类未用到,签名要求)
     * @return 扫描 key → JSON 根元素(key path 形如
     *         {@code <物品命名空间>/<物品路径>},已剥去目录前缀与 {@code .json})
     */
    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : OUTLINES_CONVERTER.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation fileKey = entry.getKey();
            // 仅取本模组命名空间下的 outlines 目录,避免误读其他模组同名目录的文件
            if (!fileKey.getNamespace().equals(EnchantedOutlines.MODID)) {
                continue;
            }
            try (Reader reader = entry.getValue().openAsReader()) {
                files.put(OUTLINES_CONVERTER.fileToId(fileKey),
                        GsonHelper.fromJson(GSON, reader, JsonElement.class));
            } catch (IllegalArgumentException | IOException | JsonParseException e) {
                EnchantedOutlines.LOGGER.error("[OutlineResources] 无法解析轮廓定义文件 {}", fileKey, e);
            }
        }
        return files;
    }

    /**
     * 资源重载回调:解析全部定义并整体替换定义表。
     *
     * @param files           {@link #prepare} 扫描到的定义文件
     * @param resourceManager 资源管理器(本类未用到,签名要求)
     * @param profiler        性能分析器(本类未用到,签名要求)
     */
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        Map<ResourceLocation, OutlineDef> parsed = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation itemId = itemIdFromKey(entry.getKey());
            if (itemId == null) {
                EnchantedOutlines.LOGGER.warn("[OutlineResources] 跳过路径不合法的轮廓定义文件: {}", entry.getKey());
                continue;
            }
            if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
                EnchantedOutlines.LOGGER.warn("[OutlineResources] 轮廓定义指向不存在的物品 {}, 已跳过", itemId);
                continue;
            }
            try {
                parsed.put(itemId, OutlineDef.parse(entry.getValue()));
            } catch (IllegalArgumentException e) {
                EnchantedOutlines.LOGGER.warn("[OutlineResources] 物品 {} 的轮廓定义解析失败, 已跳过: {}",
                        itemId, e.getMessage());
            }
        }
        DEFS = Map.copyOf(parsed);
        if (!parsed.isEmpty()) {
            EnchantedOutlines.LOGGER.info("[OutlineResources] 已加载 {} 个资源包轮廓定义", parsed.size());
        }
    }

    /**
     * 从扫描 key 反解物品 id。
     * <p>
     * 扫描 key 形如 {@code enchanted_outlines:minecraft/diamond_sword}(path 部分为
     * {@code <物品命名空间>/<物品路径>},由 {@link FileToIdConverter#fileToId} 剥去
     * 目录前缀与扩展名所得);物品注册名不含 '/',多级嵌套一律视为不合法。
     *
     * @param key 扫描 key
     * @return 物品 id;路径结构不合法返回 null
     */
    private static ResourceLocation itemIdFromKey(ResourceLocation key) {
        String path = key.getPath();
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1 || path.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath(path.substring(0, slash), path.substring(slash + 1));
    }

    /**
     * 查询物品的资源包轮廓定义。
     *
     * @param itemId 物品注册 id(可为 null)
     * @return 命中的定义;未命中或 itemId 为 null 返回 null
     */
    public static OutlineDef def(ResourceLocation itemId) {
        return itemId == null ? null : DEFS.get(itemId);
    }

    /**
     * 物品是否命中颜色循环动画定义(扁平路径据此切换"白色烘焙 + 顶点色"模式)。
     *
     * @param itemId 物品注册 id(可为 null)
     * @return true = 命中且为动画定义
     */
    public static boolean isAnimated(ResourceLocation itemId) {
        OutlineDef def = def(itemId);
        return def != null && def.isAnimated();
    }
}
