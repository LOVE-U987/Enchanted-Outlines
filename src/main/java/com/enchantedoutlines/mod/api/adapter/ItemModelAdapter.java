package com.enchantedoutlines.mod.api.adapter;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedModel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 物品模型适配器接口。
 * <p>
 * 用于兼容各种自定义渲染模组 (BEWLR/Geo/TACZ 等)。
 * 每个适配器负责检测和处理特定模组的物品。
 */
public interface ItemModelAdapter {

    /**
     * 适配器名称 (用于日志/调试)。
     */
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * 判断该适配器能否处理给定物品。
     *
     * @param item 物品
     * @return true = 可以处理
     */
    boolean canHandle(Item item);

    /**
     * 判断该适配器能否处理给定物品栈 (GUI/GROUND/FIXED 上下文)。
     *
     * @param stack 物品栈
     * @return true = 可以处理
     */
    default boolean canHandleGui(ItemStack stack) {
        return canHandle(stack.getItem());
    }

    /**
     * 判断该适配器能否处理手持物品 (世界渲染上下文)。
     *
     * @param stack 物品栈
     * @return true = 可以处理
     */
    default boolean canHandleHand(ItemStack stack) {
        return canHandle(stack.getItem());
    }

    /**
     * 渲染 GUI/GROUND/FIXED 下的物品描边。
     *
     * @param pose      渲染姿势
     * @param model     物品模型
     * @param x         物品格子 x 坐标
     * @param y         物品格子 y 坐标
     * @param quadSize  格子尺寸
     * @param color     描边颜色
     * @param thickness 描边厚度
     * @return true = 成功渲染
     */
    default boolean renderGui(PoseStack pose, BakedModel model, int x, int y, int quadSize,
                              int color, float thickness) {
        return false;
    }

    /**
     * 渲染手持物品的描边。
     *
     * @param pose      渲染姿势
     * @param model     物品模型
     * @param color     描边颜色
     * @param thickness 描边厚度
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
    default boolean renderArmor(PoseStack pose, ItemStack stack, net.minecraft.world.entity.LivingEntity entity,
                                net.minecraft.world.entity.EquipmentSlot slot, int color, float thickness,
                                net.minecraft.client.model.HumanoidModel<?> baseModel) {
        return false;
    }

    /**
     * 获取物品的自定义模型 (若需要替换默认模型)。
     *
     * @param stack 物品栈
     * @param world 世界 (可能为 null)
     * @param entity 实体 (可能为 null)
     * @return 自定义模型，或 null 使用默认
     */
    @Nullable
    default BakedModel getCustomModel(ItemStack stack, @Nullable net.minecraft.world.level.Level world,
                                       @Nullable net.minecraft.world.entity.Entity entity) {
        return null;
    }

    /**
     * 获取物品的纹理位置 (若需要自定义纹理)。
     *
     * @param stack 物品栈
     * @return 纹理位置，或 null 使用默认
     */
    @Nullable
    default net.minecraft.resources.ResourceLocation getTexture(ItemStack stack) {
        return null;
    }
}
