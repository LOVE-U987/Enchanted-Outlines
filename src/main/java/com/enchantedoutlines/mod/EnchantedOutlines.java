package com.enchantedoutlines.mod;

import org.slf4j.Logger;

import com.enchantedoutlines.mod.config.Config;
import com.mojang.logging.LogUtils;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Enchanted Outlines 主类（通用侧）。
 * <p>
 * 仅注册配置；渲染逻辑全部在客户端。
 * <p>
 * ⚠️ 1.20.1 的 FML 只支持两种 mod 主类构造器:
 * {@code (FMLJavaModLoadingContext)} 或无参 —— <b>不支持</b> 1.21.x 的
 * {@code (IEventBus, ModContainer)}。事件总线经 {@code context.getModEventBus()} 获取。
 */
@Mod(EnchantedOutlines.MODID)
public class EnchantedOutlines {

    public static final String MODID = "enchanted_outlines";

    public static final Logger LOGGER = LogUtils.getLogger();

    public EnchantedOutlines(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        // 1.20.1:ModContainer 没有 registerConfig(那是 1.21.x),配置注册经 ModLoadingContext;
        @SuppressWarnings({"deprecation", "removal"}) // ModLoadingContext.get() 被 Forge 标记为待移除,1.20.1 下仍为唯一标准方式
        ModLoadingContext mlc = ModLoadingContext.get();
        mlc.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::onModConfig);
    }

    /** 配置加载/重载时捕获 ModConfig 引用。 */
    private void onModConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            Config.MOD_CONFIG = event.getConfig();
            Config.invalidateCache();
        }
    }
}
