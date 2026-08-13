package com.enchantedoutlines.mod;

import java.io.IOException;

import com.enchantedoutlines.mod.config.EnchantedConfigScreen;
import com.enchantedoutlines.mod.outline.OutlineColorEvent;
import com.enchantedoutlines.mod.outline.OutlineRenderer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 客户端入口:把自定义配置界面挂到 Mod 列表的「配置」按钮上,
 * 注册描边核心着色器,并在客户端启动时触发扩展注册事件。
 */
@Mod(value = EnchantedOutlines.MODID, dist = Dist.CLIENT)
public class EnchantedOutlinesClient {

    /** 描边着色器位置 → enchanted_outlines:shaders/core/outline.{json,vsh,fsh} */
    private static final ResourceLocation OUTLINE_SHADER =
            ResourceLocation.fromNamespaceAndPath(EnchantedOutlines.MODID, "outline");

    public EnchantedOutlinesClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parentScreen) -> new EnchantedConfigScreen(parentScreen));
        modEventBus.addListener(this::onRegisterShaders);
        modEventBus.addListener(this::onClientSetup);
        // 进出服务器/世界时清空渲染缓存(issue #1:多人"退出后重进渲染错误"根因是缓存
        // 引用已失效资源;单人世界靠资源重载才清缓存,服务器直连无重载 → 事件兜底)。
        // LevelEvent/ClientPlayerNetworkEvent 都是 NeoForge 游戏总线事件,不能挂 MOD 总线。
        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
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

    /**
     * 客户端世界加载(进单人/服务器)时清空渲染缓存。
     * <p>
     * 服务器推送资源包时的资源重载已由 {@link #onRegisterShaders} 清理;但<b>无资源包</b>
     * 的服务器直连不触发重载,此前会话残留的缓存(形状纹理/RenderType)可能引用旧资源 →
     * 进入新世界前统一清空,保证每次进世界都是干净状态。
     */
    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            OutlineRenderer.INSTANCE.invalidateCaches();
        }
    }

    /**
     * NeoForge 游戏事件(非 MOD 总线):离开服务器时清空渲染缓存。
     * <p>
     * 与 {@link #onLevelLoad} 互补:从服务器断开回主菜单时立刻清理,避免缓存跨越
     * 会话残留(尤其服务器资源包在退出后仍缓存于客户端)。
     */
    @EventBusSubscriber(modid = EnchantedOutlines.MODID, value = Dist.CLIENT)
    public static final class ClientEvents {

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            OutlineRenderer.INSTANCE.invalidateCaches();
        }
    }
}
