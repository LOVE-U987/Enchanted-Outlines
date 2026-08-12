package com.enchantedoutlines.mod.diag;

import com.enchantedoutlines.mod.EnchantedOutlines;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 临时诊断类(定位 1.20.1 方形轮廓,定位后删除)。
 * <p>
 * 进世界后:主手附魔剑 + 副手附魔镐,5 秒后自动截图到 run/screenshots,
 * 便于分析第一人称手持描边形状。
 */
@Mod.EventBusSubscriber(modid = EnchantedOutlines.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OutlineDiag {

    private static int ticks = 0;

    private OutlineDiag() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return;
        }
        ticks++;
        if (ticks == 20) {
            // 主手:附魔剑(扁平)
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(Enchantments.SHARPNESS, 3);
            p.getInventory().setItem(p.getInventory().selected, sword);
            // 副手:附魔镐(扁平)
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            pick.enchant(Enchantments.UNBREAKING, 3);
            p.getInventory().offhand.set(0, pick);
        }
        if (ticks == 160) {
            // 进世界 ~8 秒后自动截图(第一人称手持)
            try {
                // 1.20.1 的 grab(File, String, RenderTarget, Consumer) 内部会追加 "screenshots" 子目录
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, "diag_hand_"
                        + System.currentTimeMillis() + ".png", mc.getMainRenderTarget(),
                        msg -> EnchantedOutlines.LOGGER.info("shot: " + msg.getString()));
            } catch (Exception e) {
                EnchantedOutlines.LOGGER.error("shot fail", e);
            }
        }
    }
}
