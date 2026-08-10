package com.enchantedoutlines.mod.mixin;

import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link ThrownTrident} 私有 {@code tridentItem} 字段。
 * <p>
 * 1.20.1 的 {@code ThrownTrident} 没有 {@code getPickupItemStackOrigin()}
 * (那是 1.21.x 的 API),投掷物三叉戟描边需要读取其私有物品栈
 * ({@code (EntityType, Level)} 构造器创建的客户端实体固定为无附魔三叉戟,
 * 仅用于按物品 ID 解析固定色/默认色)。
 */
@Mixin(ThrownTrident.class)
public interface ThrownTridentAccessor {

    @Accessor("tridentItem")
    ItemStack enchantedoutlines$getTridentItem();
}
