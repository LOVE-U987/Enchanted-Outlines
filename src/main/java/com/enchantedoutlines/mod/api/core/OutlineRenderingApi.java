package com.enchantedoutlines.mod.api.core;

import com.enchantedoutlines.mod.api.adapter.ItemModelAdapter;
import com.enchantedoutlines.mod.api.algo.ShapeResult;
import com.enchantedoutlines.mod.api.color.ColorResolver;
import com.enchantedoutlines.mod.config.Config;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.block.model.BakedModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import org.jetbrains.annotations.Nullable;

/**
 * 描边渲染 API 主接口。
 * <p>
 * 提供统一的渲染入口，内部封装各适配器/算法/渲染器的调用。
 * 所有对外调用通过本类，确保逻辑一致性与可测试性。
 */
public final class OutlineRenderingApi {

    public static final OutlineRenderingApi INSTANCE = new OutlineRenderingApi();

    private final ColorResolver colorResolver;
    private final ItemModelRegistry itemTypeRegistry;
    private final RenderPipeline renderPipeline;

    private OutlineRenderingApi() {
        this.colorResolver = ColorResolver.INSTANCE;
        this.itemTypeRegistry = ItemModelRegistry.INSTANCE;
        this.renderPipeline = RenderPipeline.INSTANCE;
    }

    /**
     * 是否允许对给定物品渲染描边 (检查总开关/白名单/黑名单)。
     *
     * @param stack 物品栈
     * @return true = 允许渲染
     */
    public boolean shouldRenderOutline(ItemStack stack) {
        if (!Config.ENABLE.get()) {
            return false;
        }
        Item item = stack.getItem();
        if (!Config.isItemEnabled(item.getRegistryName())) {
            return false;
        }
        if (Config.isItemDisabled(item.getRegistryName())) {
            return false;
        }
        return true;
    }

    /**
     * 解析物品的类型信息 (BEWLR/Geo/Lionfish/通用)。
     * <p>
     * 结果会被缓存，避免重复反射判断。
     *
     * @param item 物品
     * @return 物品类型信息
     */
    public ItemTypeInfo resolveItemType(Item item) {
        return itemTypeRegistry.resolve(item);
    }

    /**
     * 解析物品类型的缓存版本。
     *
     * @param stack 物品栈
     * @return 物品类型信息
     */
    public ItemTypeInfo resolveItemType(ItemStack stack) {
        return itemTypeRegistry.resolve(stack.getItem());
    }

    /**
     * 检测物品的形状特征，选择对应的形状算法。
     *
     * @param stack     物品栈
     * @param model     物品模型
     * @param typeInfo  物品类型信息 (可为 null，由方法内解析)
     * @return 形状检测结果
     */
    public ShapeResult detectShape(ItemStack stack, BakedModel model, @Nullable ItemTypeInfo typeInfo) {
        if (typeInfo == null) {
            typeInfo = resolveItemType(stack);
        }
        return renderPipeline.detectShape(stack, model, typeInfo);
    }

    /**
     * 解析物品的描边颜色 (按优先级：RPG→数据包→配置)。
     *
     * @param stack 物品栈
     * @return 描边颜色 (ARGB)
     */
    public int resolveColor(ItemStack stack) {
        return colorResolver.resolve(stack);
    }

    /**
     * 渲染 GUI/GROUND/FIXED 下的物品描边。
     *
     * @param pose    渲染姿势
     * @param model   物品模型
     * @param x       物品格子 x 坐标
     * @param y       物品格子 y 坐标
     * @param quadSize 格子尺寸
     * @param color   描边颜色
     * @param厚度    描边厚度
     */
    public void renderGuiOutline(PoseStack pose, BakedModel model, int x, int y, int quadSize,
                                  int color, float thickness) {
        renderPipeline.renderGuiOutline(pose, model, x, y, quadSize, color, thickness);
    }

    /**
     * 渲染手持物品的描边 (世界渲染)。
     *
     * @param model    物品模型
     * @param pose     渲染姿势
     * @param color    描边颜色
     * @param thickness 描边厚度
     */
    public void renderHandOutline(BakedModel model, PoseStack pose, int color, float thickness) {
        renderPipeline.renderHandOutline(model, pose, color, thickness);
    }

    /**
     * 渲染 BEWLR 手持物品的 3D 描边。
     *
     * @param stack     物品栈
     * @param pose      渲染姿势
     * @param color     描边颜色
     * @param thickness 描边厚度
     * @return true = 成功渲染
     */
    public boolean renderBewlrOutline(ItemStack stack, PoseStack pose, int color, float thickness) {
        return renderPipeline.renderBewlrOutline(stack, pose, color, thickness);
    }

    /**
     * 渲染盔甲描边 (穿戴在实体上)。
     *
     * @param pose       渲染姿势
     * @param stack      物品栈
     * @param entity     穿戴实体
     * @param slot       穿戴槽位
     * @param color      描边颜色
     * @param thickness  描边厚度
     * @param baseModel  基础盔甲模型
     * @return true = 成功渲染
     */
    public boolean renderArmorOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                       EquipmentSlot slot, int color, float thickness,
                                       HumanoidModel<?> baseModel) {
        return renderPipeline.renderArmorOutline(pose, stack, entity, slot, color, thickness, baseModel);
    }

    /**
     * 渲染投掷物 (三叉戟) 的描边。
     *
     * @param pose      渲染姿势
     * @param stack     物品栈
     * @param entity    投掷实体
     * @param color     描边颜色
     * @param thickness 描边厚度
     * @return true = 成功渲染
     */
    public boolean renderProjectileOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                            int color, float thickness) {
        return renderPipeline.renderProjectileOutline(pose, stack, entity, color, thickness);
    }

    /**
     * 渲染鞘翅的描边。
     *
     * @param pose      渲染姿势
     * @param stack     物品栈
     * @param entity    穿戴实体
     * @param color     描边颜色
     * @param thickness 描边厚度
     * @return true = 成功渲染
     */
    public boolean renderElytraOutline(PoseStack pose, ItemStack stack, LivingEntity entity,
                                        int color, float thickness) {
        return renderPipeline.renderElytraOutline(pose, stack, entity, color, thickness);
    }

    /**
     * 清空所有缓存 (资源重载/进出世界时调用)。
     */
    public void invalidateAllCaches() {
        itemTypeRegistry.invalidateCache();
        colorResolver.invalidateCache();
        renderPipeline.invalidateCaches();
    }

    /**
     * 注册自定义的物品适配器。
     * <p>
     * 用于兼容新模组，在渲染前被尝试匹配。
     *
     * @param adapter 适配器
     */
    public void registerAdapter(ItemModelAdapter adapter) {
        itemTypeRegistry.registerAdapter(adapter);
    }

    /**
     * 获取物品适配器列表 (用于调试/元数据)。
     */
    public java.util.List<ItemModelAdapter> getAdapters() {
        return itemTypeRegistry.getAdapters();
    }
}
