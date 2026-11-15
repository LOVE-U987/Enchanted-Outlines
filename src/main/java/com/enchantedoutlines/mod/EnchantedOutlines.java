package com.enchantedoutlines.mod;

import org.slf4j.Logger;

import com.enchantedoutlines.mod.config.Config;
import com.enchantedoutlines.mod.platform.NeoForgePlatformSupport;
import com.enchantedoutlines.mod.platform.Platform;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Enchanted Outlines 主类（通用侧）。
 * <p>
 * 仅注册配置；渲染逻辑全部在客户端。
 */
@Mod(EnchantedOutlines.MODID)
public class EnchantedOutlines {

    public static final String MODID = "enchanted_outlines";

    public static final Logger LOGGER = LogUtils.getLogger();

    public EnchantedOutlines(IEventBus modEventBus, ModContainer modContainer) {
        // 平台门面必须最先安装：共享源码中的资源位置构造/模组查询都依赖它。
        Platform.install(new NeoForgePlatformSupport());
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
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
