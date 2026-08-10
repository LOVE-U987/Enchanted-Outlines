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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 客户端专用事件订阅器(1.20.1 架构)。
 * <p>
 * ⚠️ 1.20.1 的 {@code @Mod} 注解只有 {@code value()} 参数(无 1.21.x 的
 * {@code dist}),且一个 modId 只能有一个 @Mod 主类。客户端专用逻辑用
 * {@code @Mod.EventBusSubscriber(bus = MOD)} 挂在 MOD 总线上,配合
 * {@code @OnlyIn(Dist.CLIENT)} 标注:服务器端类加载器会剥离本类(不引用任何
 * 客户端类),仅客户端生效。
 * <ul>
 *   <li>{@link #onRegisterShaders}:注册描边核心着色器(F3+T 资源重载时重新触发);</li>
 *   <li>{@link #onClientSetup}:注册配置界面扩展点 + 触发扩展注册事件。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = EnchantedOutlines.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class EnchantedOutlinesClient {

    /** 描边着色器位置 → enchanted_outlines:shaders/core/outline.{json,vsh,fsh} */
    private static final ResourceLocation OUTLINE_SHADER =
            ResourceLocation.fromNamespaceAndPath(EnchantedOutlines.MODID, "outline");

    /**
     * 核心着色器注册。F3+T 资源重载时会重新触发,onLoaded 回调把新实例写回渲染器
     * (自定义 RenderType 用 Supplier 引用着色器,自动跟随新实例,无需重建)。
     */
    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            ShaderInstance shader = new ShaderInstance(
                    event.getResourceProvider(), OUTLINE_SHADER, DefaultVertexFormat.NEW_ENTITY);
            event.registerShader(shader, OutlineRenderer.INSTANCE::setOutlineShader);
        } catch (IOException e) {
            EnchantedOutlines.LOGGER.error("Failed to load the outline shader; outlines are disabled.", e);
        }
    }

    /** 客户端启动:注册配置界面扩展点(1.20.1 为 ConfigScreenHandler.ConfigScreenFactory)+ 触发扩展注册事件。 */
    @SuppressWarnings("removal") // FMLJavaModLoadingContext.get() 在 1.20.1 被标记待移除,仍为 mod lifecycle 内取 container 的标准方式
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 1.20.1:配置界面扩展点是 ConfigScreenHandler.ConfigScreenFactory(1.21.1 才改名为
        // IConfigScreenFactory);registerExtensionPoint 第二参是 IExtensionPoint.Consumer(无参工厂)。
        // 经 FMLJavaModLoadingContext.get().getContainer() 拿 ModContainer(客户端专用,类只在此加载)。
        ModContainer container = FMLJavaModLoadingContext.get().getContainer();
        container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parentScreen) -> new EnchantedConfigScreen(parentScreen)));
        OutlineColorEvent.fire();
    }
}
