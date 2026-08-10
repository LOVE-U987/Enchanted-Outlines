package com.enchantedoutlines.mod;

import java.io.IOException;

import com.enchantedoutlines.mod.config.EnchantedConfigScreen;
import com.enchantedoutlines.mod.outline.OutlineColorEvent;
import com.enchantedoutlines.mod.outline.OutlineRenderer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端入口:把自定义配置界面挂到 Mod 列表的「配置」按钮上,
 * 注册描边核心着色器,并在客户端启动时触发扩展注册事件。
 * <p>
 * 1.20.1 的 {@code @Mod} 注解没有 {@code dist} 参数(那是 1.21.x),客户端专用类
 * 用 {@code @OnlyIn(Dist.CLIENT)} 标注:服务器端该类被 Forge 类加载器剥离,
 * 仅客户端生效(1.20.1 标准做法)。
 */
@Mod(value = EnchantedOutlines.MODID)
@OnlyIn(Dist.CLIENT)
public class EnchantedOutlinesClient {

    /** 描边着色器位置 → enchanted_outlines:shaders/core/outline.{json,vsh,fsh} */
    private static final ResourceLocation OUTLINE_SHADER =
            ResourceLocation.fromNamespaceAndPath(EnchantedOutlines.MODID, "outline");

    public EnchantedOutlinesClient(IEventBus modEventBus, ModContainer container) {
        // 1.20.1:配置界面扩展点是 ConfigScreenHandler.ConfigScreenFactory(1.21.1 才改名为
        // IConfigScreenFactory);registerExtensionPoint 第二参是 IExtensionPoint.Consumer(无参工厂)
        container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parentScreen) -> new EnchantedConfigScreen(parentScreen)));
        modEventBus.addListener(this::onRegisterShaders);
        modEventBus.addListener(this::onClientSetup);
    }

    /**
     * 核心着色器注册。F3+T 资源重载时会重新触发,onLoaded 回调把新实例写回渲染器
     * (自定义 RenderType 用 Supplier 引用着色器,自动跟随新实例,无需重建)。
     */
    private void onRegisterShaders(RegisterShadersEvent event) {
        try {
            ShaderInstance shader = new ShaderInstance(
                    event.getResourceProvider(), OUTLINE_SHADER, DefaultVertexFormat.NEW_ENTITY);
            event.registerShader(shader, OutlineRenderer.INSTANCE::setOutlineShader);
        } catch (IOException e) {
            EnchantedOutlines.LOGGER.error("Failed to load the outline shader; outlines are disabled.", e);
        }
    }

    /** 客户端启动:触发扩展注册事件(供其他模组注册描边规则)。 */
    private void onClientSetup(FMLClientSetupEvent event) {
        OutlineColorEvent.fire();
    }
}
