package com.enchantedoutlines.mod.outline;

import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Enchanted Outlines 扩展注册事件。
 * <p>
 * 客户端启动时(FMLClientSetup)在<b>游戏事件总线</b>触发一次,供其他模组注册描边规则。
 * 本事件本身不带负载:直接调用 {@link OutlineColorRegistry} 的静态方法注册即可。
 * <p>
 * 消费方示例(任意客户端类,在构造函数中注册):
 * <pre>{@code
 * NeoForge.EVENT_BUS.addListener(OutlineColorEvent.class, event -> {
 *     OutlineColorRegistry.registerItemColor(
 *             ResourceLocation.fromNamespaceAndPath("mymod", "magic_sword"), 0xFF00FF00);
 *     OutlineColorRegistry.disableItem(
 *             ResourceLocation.fromNamespaceAndPath("mymod", "special_item"));
 * });
 * }</pre>
 */
public final class OutlineColorEvent extends Event {

    public OutlineColorEvent() {
    }

    /**
     * 供本模组内部触发;其他模组无需调用。
     */
    public static void fire() {
        NeoForge.EVENT_BUS.post(new OutlineColorEvent());
    }
}
