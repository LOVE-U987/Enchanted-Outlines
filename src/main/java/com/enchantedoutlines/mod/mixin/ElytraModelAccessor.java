package com.enchantedoutlines.mod.mixin;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link ElytraModel} 私有左右翼片字段。
 * <p>
 * 鞘翅描边需要逐翼片绕各自包围盒中心放大壳(两翼分别膨胀),而 leftWing/rightWing
 * 是私有字段且 bodyParts() 是 protected → 用 accessor 读取。
 */
@Mixin(ElytraModel.class)
public interface ElytraModelAccessor {

    @Accessor("leftWing")
    ModelPart enchantedoutlines$getLeftWing();

    @Accessor("rightWing")
    ModelPart enchantedoutlines$getRightWing();
}
