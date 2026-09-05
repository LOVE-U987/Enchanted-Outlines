package com.enchantedoutlines.mod.api.adapter;

import com.enchantedoutlines.mod.api.core.ItemTypeInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 物品模型适配器接口。
 * <p>
 * 用于兼容各种模组的物品/盔甲模型，在渲染前进行判断与适配。
 */
public interface ItemModelAdapter {

    /**
     * 适配器名称。
     */
    String getName();

    /**
     * 是否为优先级最高的适配器。
     * <p>
     * 优先级高的适配器会优先匹配。
     */
    default int getPriority() {
        return 0;
    }

    /**
     * 检测物品是否属于该适配器。
     *
     * @param item 物品
     * @return true = 该适配器适用
     */
    boolean matches(Item item);

    /**
     * 解析物品的类型信息。
     *
     * @param item 物品
     * @return 类型信息，null = 不适用
     */
    @Nullable
    ItemTypeInfo resolve(Item item);

    /**
     * 是否可以处理 GUI/GROUND/FIXED 渲染。
     *
     * @param stack 物品栈
     * @return true = 可以处理
     */
    default boolean canHandleGui(ItemStack stack) {
        return false;
    }

    /**
     * 渲染 GUI/GROUND/FIXED 下的物品描边。
     *
     * @param pose       渲染姿势
     * @param model      物品模型
     * @param x          物品格子 x 坐标
     * @param y          物品格子 y 坐标
     * @param quadSize   格子尺寸
     * @param color      描边颜色
     * @param thickness  描边厚度
     * @return true = 成功渲染
     */
    default boolean renderGui(PoseStack pose, BakedModel model, int x, int y, int quadSize,
                              int color, float thickness) {
        return false;
    }

    /**
     * 渲染手持物品的描边。
     *
     * @param pose       渲染姿势
     * @param model      物品模型
     * @param color      描边颜色
     * @param thickness  描边厚度
     * @return true = 成功渲染
     */
    default boolean renderHand(PoseStack pose, BakedModel model, int color, float thickness) {
        return false;
    }

    /**
     * 渲染盔甲描边。
     *
     * @param pose        渲染姿势
     * @param stack       物品栈
     * @param entity      穿戴实体
     * @param slot        穿戴槽位
     * @param color       描边颜色
     * @param thickness   描边厚度
     * @param baseModel   基础盔甲模型
     * @return true = 成功渲染
     */
    default boolean renderArmor(PoseStack pose, ItemStack stack, LivingEntity entity,
                                EquipmentSlot slot, int color, float thickness,
                                HumanoidModel<?> baseModel) {
        return false;
    }

    /**
     * 获取自定义模型。
     *
     * @param stack  物品栈
     * @param world  世界
     * @param entity 实体
     * @return 自定义模型，null = 使用默认模型
     */
    @Nullable
    default BakedModel getCustomModel(ItemStack stack, @Nullable net.minecraft.world.level.Level world,
                                      @Nullable net.minecraft.world.entity.Entity entity) {
        return null;
    }
}
