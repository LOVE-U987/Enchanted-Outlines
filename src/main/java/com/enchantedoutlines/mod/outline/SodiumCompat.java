package com.enchantedoutlines.mod.outline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.enchantedoutlines.mod.EnchantedOutlines;
import com.enchantedoutlines.mod.config.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.neoforged.fml.ModList;

/**
 * Sodium 动画纹理兼容(2026-10-25,动态物品附魔后动画冻结修复)。
 * <p>
 * <b>根因</b>:Sodium 0.8 的"仅动画可见纹理"({@code animate_only_visible_textures})
 * 优化只 tick 上帧被标记可见的 atlas 精灵,而附魔物品渲染路径(glint+main 双绘 /
 * BEWLR 捕获)未进入其可见性跟踪 → 精灵被判不可见 → 动画冻结;同款无附魔物品渲染
 * 会重新标记精灵,冻结随之解除。用户实测确认,详见知识库「项目经验」2026-10-25 条目。
 * <p>
 * <b>修复</b>:利用 Sodium 公开兼容 API
 * {@code net.caffeinemc.mods.sodium.api.texture.SpriteUtil#markSpriteActive},
 * 在物品渲染入口(三个 mixin 的 HEAD)对附魔物品模型引用的 atlas 精灵逐帧标记
 * active —— 保留 Sodium 优化,仅豁免附魔物品。Sodium 类名只经反射触达,无硬依赖;
 * 未安装 Sodium 时全部调用零成本返回。本兼容<b>独立于描边总开关</b>
 * (冻结与描边无关,由 {@link Config#SODIUM_TEXTURE_COMPAT} 单独门禁)。
 * <p>
 * 性能:精灵列表按 BakedModel 弱引用缓存(帧无关输入,铁律 4);标记为幂等 flag 写。
 * 局限:separate_transforms 等把几何拆进子模型的加载器,基础模型 getQuads 可能
 * 为空 → 精灵列表为空(不标记);此类物品如遇冻结,关 Sodium 选项回退。
 */
public final class SodiumCompat {

    /** Sodium 的 modid(存在性探测)。 */
    private static final String SODIUM_MODID = "sodium";
    /** Sodium 公开兼容 API 全名(仅以字符串形式出现,无编译期依赖)。 */
    private static final String SPRITE_UTIL_CLASS = "net.caffeinemc.mods.sodium.api.texture.SpriteUtil";

    /** API 解析只做一次:true = 已尝试解析。 */
    private static boolean resolved;
    /** 解析出的 markSpriteActive 方法实例;null = Sodium 未装/API 不兼容(静默降级)。 */
    private static Method markSpriteActive;
    /** SpriteUtil.INSTANCE(调用接收者)。 */
    private static Object spriteUtilInstance;
    /** 解析失败/调用异常的节流日志时间戳(常驻诊断,10 秒窗口)。 */
    private static long lastDiagLogMs;

    /** BakedModel → 其 quads 引用的去重精灵列表(帧无关,弱引用随模型回收)。 */
    private static final Map<BakedModel, List<TextureAtlasSprite>> MODEL_SPRITES = new WeakHashMap<>();

    private SodiumCompat() {
    }

    /**
     * 标记物品模型引用的 atlas 精灵为活跃(手持/掉落物/展示框/GUI 通用入口)。
     * <p>
     * 模型由调用方提供的 seed 解析,保证与本体渲染同一变体;解析失败静默跳过。
     *
     * @param stack  当前渲染的物品栈(需为附魔物品)
     * @param level  渲染所在关卡(null 时回退客户端当前关卡)
     * @param entity 渲染关联实体(可为 null)
     * @param seed   模型解析种子(与本体 renderStatic 一致)
     */
    public static void markStackSprites(ItemStack stack, Level level, LivingEntity entity, int seed) {
        if (stack.isEmpty() || !stack.hasFoil() || !Config.SODIUM_TEXTURE_COMPAT.get()) {
            return;
        }
        try {
            Level resolvedLevel = level != null ? level : Minecraft.getInstance().level;
            BakedModel model = Minecraft.getInstance().getItemRenderer()
                    .getModel(stack, resolvedLevel, entity, seed);
            markStackSprites(stack, model);
        } catch (Throwable ignored) {
            // 模型解析失败:跳过标记,不影响本体渲染
        }
    }

    /**
     * 标记物品模型引用的 atlas 精灵为活跃(手持入口重载:关卡由实体回退解析)。
     *
     * @param stack 当前渲染的物品栈(需为附魔物品)
     * @param entity 手持物品的实体(可为 null)
     * @param seed   模型解析种子(与本体 renderStatic 一致)
     */
    public static void markStackSprites(ItemStack stack, LivingEntity entity, int seed) {
        markStackSprites(stack, null, entity, seed);
    }

    /**
     * 标记物品模型引用的 atlas 精灵为活跃(调用方已持有模型的重载)。
     *
     * @param stack 当前渲染的物品栈(需为附魔物品)
     * @param model 本体渲染使用的 BakedModel
     */
    public static void markStackSprites(ItemStack stack, BakedModel model) {
        if (stack.isEmpty() || !stack.hasFoil() || !Config.SODIUM_TEXTURE_COMPAT.get() || model == null) {
            return;
        }
        Method mark = resolve();
        if (mark == null || spriteUtilInstance == null) {
            return;
        }
        for (TextureAtlasSprite sprite : collectSprites(model)) {
            try {
                mark.invoke(spriteUtilInstance, sprite);
            } catch (Throwable ignored) {
                // 单个标记失败(Sodium 版本差异等):静默,不影响渲染
            }
        }
    }

    /**
     * 收集模型 quads 引用的去重精灵列表(带弱引用缓存)。
     * <p>
     * 随机种子与精灵列表无关(物品模型 quads 不随 random 变化),固定 42 与
     * vanilla renderModelLists 同源。BEWLR 物品 quads 为空 → 空列表(no-op)。
     *
     * @param model 物品模型
     * @return 去重精灵列表(可能为空,不返回 null)
     */
    private static List<TextureAtlasSprite> collectSprites(BakedModel model) {
        List<TextureAtlasSprite> cached = MODEL_SPRITES.get(model);
        if (cached != null) {
            return cached;
        }
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        try {
            for (var quad : model.getQuads(null, null, RandomSource.create(42L))) {
                TextureAtlasSprite sprite = quad.getSprite();
                if (sprite != null && !sprites.contains(sprite)) {
                    sprites.add(sprite);
                }
            }
        } catch (Throwable ignored) {
            sprites.clear();
        }
        MODEL_SPRITES.put(model, sprites);
        return sprites;
    }

    /**
     * 惰性解析 Sodium 兼容 API(一次性;失败静默降级 + 节流日志)。
     *
     * @return markSpriteActive 方法;不可用返回 null
     */
    private static Method resolve() {
        if (resolved) {
            return markSpriteActive;
        }
        resolved = true;
        try {
            if (!ModList.get().isLoaded(SODIUM_MODID)) {
                return null;
            }
            Class<?> api = Class.forName(SPRITE_UTIL_CLASS);
            Field instanceField = api.getField("INSTANCE");
            spriteUtilInstance = instanceField.get(null);
            markSpriteActive = api.getMethod("markSpriteActive", TextureAtlasSprite.class);
        } catch (Throwable t) {
            markSpriteActive = null;
            spriteUtilInstance = null;
            logDiag("解析失败: " + t.getClass().getSimpleName());
        }
        return markSpriteActive;
    }

    /**
     * 兼容不可用/异常的节流诊断日志(10 秒窗口,常驻诊断模式,info 级不刷屏)。
     *
     * @param detail 日志内容
     */
    private static void logDiag(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastDiagLogMs < 10_000L) {
            return;
        }
        lastDiagLogMs = now;
        EnchantedOutlines.LOGGER.info("[Enchanted Outlines] Sodium 动画兼容不可用({}): 如遇附魔物品动画冻结,请关闭 Sodium 的\"仅动画可见纹理\"", detail);
    }
}
