package com.enchantedoutlines.mod.mixin;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.outline.ColorResolver;
import com.enchantedoutlines.mod.outline.OutlineRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI 物品描边钩子。
 * <p>
 * 注入 {@link GuiGraphics} 私有 7 参 {@code renderItem} 的 HEAD——这是所有公开
 * renderItem 重载(含快捷栏 HUD、背包、容器界面)的唯一漏斗。描边 pass 在物品本体
 * 之前写入同一个 BufferSource,flush 时先绘制 → 描边垫底、物品覆盖中心,露出外扩环。
 * <p>
 * 跳过:总开关关闭、物品为空、无附魔、entity == null(renderFakeItem / JEI 幽灵)、
 * BEWLR 自定义渲染物品(盾牌、钓鱼竿等,占位模型无形状)。
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {

    /** 复刻 ItemRenderer.render 在 GUI 下对特殊物品的模型替换(与 ItemRenderer 同源)。 */
    private static final ModelResourceLocation TRIDENT_MODEL =
            ModelResourceLocation.inventory(ResourceLocation.withDefaultNamespace("trident"));
    private static final ModelResourceLocation SPYGLASS_MODEL =
            ModelResourceLocation.inventory(ResourceLocation.withDefaultNamespace("spyglass"));

    @Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
            at = @At("HEAD"))
    private void enchantedoutlines$renderOutline(LivingEntity entity, Level level, ItemStack stack,
                                                 int x, int y, int seed, int quadSize, CallbackInfo ci) {
        if (stack.isEmpty() || entity == null || !Config.ENABLE.get()) {
            return;
        }
        int color = ColorResolver.resolve(stack);
        if (color == -1) {
            return;
        }
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, level, entity, seed);
        // 三叉戟/望远镜在 GUI 下用完整模型渲染,复刻 ItemRenderer.render 的替换;
        // 盾牌是 BEWLR,用近似盒模型描边
        if (stack.is(Items.TRIDENT)) {
            model = itemRenderer.getItemModelShaper().getModelManager().getModel(TRIDENT_MODEL);
        } else if (stack.is(Items.SPYGLASS)) {
            model = itemRenderer.getItemModelShaper().getModelManager().getModel(SPYGLASS_MODEL);
        } else if (stack.is(Items.SHIELD)) {
            // 传当前解析出的模型 transforms(GUI 下 blocking/non-blocking 同用 gui display,无差异)
            model = OutlineRenderer.INSTANCE.shieldModel(model.getTransforms());
        }
        if (model.isCustomRenderer()) {
            return;
        }
        float thickness = ColorResolver.thickness(stack);
        if (thickness <= 0f) {
            return;
        }

        GuiGraphics self = (GuiGraphics) (Object) this;
        OutlineRenderer.INSTANCE.renderOutline(
                self.pose(), model, x, y, quadSize, color, thickness);
    }
}
