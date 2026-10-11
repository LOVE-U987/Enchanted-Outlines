package com.enchantedoutlines.mod.outline;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * BEWLR 静态字段模型解析器(永恒星光/灾变等"持有模型静态字段"的模组适配表)。
 * <p>
 * 2026-09-25 行为保持拆分:从 {@link OutlineRenderer} 拆出的 BEWLR 家族子模块之一,
 * 承接"反射读取模组渲染器持有的<b>模型静态字段</b> + <b>按物品映射的内部预变换</b> +
 * <b>本体纹理字段</b> + <b>标准 Mojang Model 的 root</b>"。解析语义、字段名映射表、
 * 预变换类别与拆分前完全一致(AGENTS.md 铁律:BEWLR 内部预变换必须按物品映射,
 * 灾变字段名不遵循统一约定,见 {@link #CATACLYSM_WEAPON_SPECS})。
 * <p>
 * <b>新模组 BEWLR 适配指引</b>:模组渲染器把模型存在静态字段(如
 * {@code ESItemStackRenderer.CRESCENT_SPEAR_MODEL})时,把持有类加入
 * {@link #BEWLR_MODEL_HOLDER_CLASSES},并按其 renderByItem 字节码确认内部
 * translate/scale,登记预变换类别({@link BewlrModel} 常量);
 * 字段名不走 {@code <物品ID大写>_MODEL} 通用约定的(如灾变缩写/无后缀),在
 * {@link #CATACLYSM_WEAPON_SPECS} 式映射表补精确条目。
 * <p>
 * 纯静态工具类(无实例状态);反射工具复用 {@link OutlineRenderer#findField}。
 */
final class BewlrModelResolver {

    /** 工具类:禁止实例化。 */
    private BewlrModelResolver() {
    }

    /** 描边时 BEWLR 模型对象 + 来源模组的预变换类别 + 本体纹理(用于颜色混合,可为 null)。 */
    record BewlrModel(Object model, int preTransform, ResourceLocation texture) {
        /** 无预变换(未知模组,仅缩放尝试)。 */
        static final int NONE = 0;
        /** 永恒星光:scale(1,-1,-1)。 */
        static final int ES_FLIP = 1;
        /** 灾变:translate(0.5,0.5,0.5) + scale(1,-1,-1)。 */
        static final int CM_CENTER_FLIP = 2;
        /** 灾变(纯翻转,无居中平移):scale(1,-1,-1)。珊瑚矛/珊瑚钺/黑钢圆盾/蔚蓝海石盾。 */
        static final int CM_FLIP = 3;
        /** 灾变(方块类物品,如 EMP/机械铁砧/祭坛/雕像/刷怪笼):translate(0.5,1.5,0.5) + scale(1,-1,-1)。 */
        static final int CM_CENTER_FLIP_TOP = 4;
    }

    /**
     * 已知持有 BEWLR 模型<b>静态字段</b>的类(模组适配表,按需扩展)。
     * 字段名解析与预变换类别<b>不按持有类统一</b>,而是按物品注册 ID 查精确映射表
     * {@link #CATACLYSM_WEAPON_SPECS}(灾变)或通用约定(见 {@link #readBewlrModelField}):
     * <ul>
     *   <li>永恒星光 {@code ESItemStackRenderer.CRESCENT_SPEAR_MODEL}(非 final,
     *       首次渲染时延迟初始化);</li>
     *   <li>灾变 {@code CMItemstackRenderer.THE_IMMOLATOR_MODEL} 等(final,
     *       类加载时初始化;字段名/预变换见 {@link #CATACLYSM_WEAPON_SPECS})。</li>
     * </ul>
     * <p>
     * ⚠️ <b>模组级预变换(2026-08-09 修复,描边错位/翻转根因)</b>:模组 BEWLR 渲染器在
     * display transform 之后、renderToBuffer 之前,还会对模型套一层<b>内部预变换</b>
     * (pushPose 内 translate/scale)。描边必须复刻同一预变换,否则描边模型与本体
     * 错位/镜像。实测:
     * <ul>
     *   <li>永恒星光(ESItemStackRenderer):所有物品分支 {@code scale(1,-1,-1)};</li>
     *   <li>灾变(CMItemstackRenderer):<b>大部分</b>武器 {@code translate(0.5,0.5,0.5) +
     *       scale(1,-1,-1)}(珊瑚矛/珊瑚钺/黑钢圆盾/蔚蓝海石盾<b>只有</b>
     *       {@code scale(1,-1,-1)};个别方块类物品 translate(0.5,1.5,0.5),不在
     *       武器描边范围)→ 预变换必须<b>按物品</b>映射({@link #CATACLYSM_WEAPON_SPECS}),
     *       不能按持有类一刀切,否则珊瑚矛等描边多 0.5 偏移错位。</li>
     * </ul>
     */
    private static final String[] BEWLR_MODEL_HOLDER_CLASSES = {
            "cn.leolezury.eternalstarlight.common.client.renderer.ESItemStackRenderer", // 永恒星光:scale(1,-1,-1)
            "com.github.L_Ender.cataclysm.client.render.CMItemstackRenderer",            // 灾变:预变换按物品映射表
            // ⚠️ Hazen'N Stuff(HSNEnchantedItemStackRenderer)与真菌感染(Spore)未列入
            // 持有类列表:
            //   - Hazen:Geo/BEWLR 物品描边走通用 Geo 贴图取样 + 捕获兜底(见
            //     {@code Config#ENABLE_HAZEN_COMPAT} 与 BEWLR 派发逻辑的 Hazen 分支),
            //     不依赖本静态字段适配;
            //   - Spore:SItemProperties 经 javap 证实只有 makeBow/makeSickle/makeShield
            //     等 ItemProperties 注册方法,<b>没有任何静态模型字段</b>,反射必然失败;
            //     Spore 枪械是 Blockbench elements 自绘(不走静态字段 BEWLR 适配)。
    };

    /**
     * ⚠️ 灾变(LionfishAPI)BEWLR 武器<b>精确适配表</b>(基于 L_Ender's Cataclysm 1.21.1-3.32
     * {@code CMItemstackRenderer.renderByItem} 实测字节码,2026-08-09):
     * 物品注册 ID → (模型字段名, 纹理字段名, 内部预变换类别)。
     * <p>
     * 灾变静态字段名<b>不遵循</b>统一的 {@code <物品ID大写>_MODEL} 约定,且不同武器
     * 分支的<b>内部预变换不同</b>。若不精确映射,后果(玩家实测):
     * <ul>
     *   <li><b>字段不匹配 → 描边完全缺失</b>(虚空突击肩炮/沙暴之怒/断魂战戟/歼灭战锤
     *       在整合包中"大部分武器没轮廓"的根因);</li>
     *   <li><b>预变换错误 → 描边偏移/翻转</b>(统一按居中翻转适配珊瑚矛等纯翻转分支,
     *       描边多出 translate(0.5,0.5,0.5) 偏移,轮廓错位到玩家身后)。</li>
     * </ul>
     * 实测字段名要点:
     * <ul>
     *   <li>{@code wither_assault_shoulder_weapon} 与 {@code void_assault_shoulder_weapon}
     *       <b>共用</b> {@code WASW_MODEL}(Wither_Assault_SHoulder_Weapon_Model);</li>
     *   <li>{@code wrath_of_the_desert} = {@code WRATH_OF_DESERT_MODEL}(比注册 ID 少
     *       {@code _THE_});</li>
     *   <li>{@code soul_render} / {@code the_annihilator} = {@code SOUL_RENDER} /
     *       {@code THE_ANNIHILATOR}(<b>无</b> {@code _MODEL} 后缀);</li>
     *   <li>{@code black_steel_targe} / {@code azure_sea_shield} / {@code coral_spear} /
     *       {@code coral_bardiche} 分支<b>只有</b> {@code scale(1,-1,-1)} →
     *       {@link BewlrModel#CM_FLIP}。</li>
     * </ul>
     * 未列入的物品(掣雷巨锤 {@code brontes}=BRONTES_MODEL、献祭者 {@code the_immolator}
     * 等)走通用 {@code <物品ID大写>_MODEL} 约定 + 居中翻转,兼容后续版本字段名调整。
     */
    private static final Map<String, CataclysmWeaponSpec> CATACLYSM_WEAPON_SPECS = buildCataclysmSpecs();

    /** 灾变武器适配条目:模型静态字段名 + 本体纹理静态字段名 + 内部预变换类别。 */
    private record CataclysmWeaponSpec(String modelField, String textureField, int preTransform) {
    }

    /** 构建 {@link #CATACLYSM_WEAPON_SPECS}(字段名/纹理名/预变换均来自 3.32 字节码实测)。 */
    private static Map<String, CataclysmWeaponSpec> buildCataclysmSpecs() {
        Map<String, CataclysmWeaponSpec> m = new HashMap<>();
        // 共用 WASW_MODEL(Wither_Assault_SHoulder_Weapon_Model,字段名缩写)
        m.put("wither_assault_shoulder_weapon",
                new CataclysmWeaponSpec("WASW_MODEL", "WASW_TEXTURE", BewlrModel.CM_CENTER_FLIP));
        m.put("void_assault_shoulder_weapon",
                new CataclysmWeaponSpec("WASW_MODEL", "VASW_TEXTURE", BewlrModel.CM_CENTER_FLIP));
        // 字段名比注册 ID 少 _THE_
        m.put("wrath_of_the_desert",
                new CataclysmWeaponSpec("WRATH_OF_DESERT_MODEL", "WRATH_OF_DESERT_TEXTURE", BewlrModel.CM_CENTER_FLIP));
        // 无 _MODEL 后缀
        m.put("soul_render",
                new CataclysmWeaponSpec("SOUL_RENDER", "SOUL_RENDER_TEXTURE", BewlrModel.CM_CENTER_FLIP));
        m.put("the_annihilator",
                new CataclysmWeaponSpec("THE_ANNIHILATOR", "THE_ANNIHILATOR_TEXTURE", BewlrModel.CM_CENTER_FLIP));
        // 分支只有 scale(1,-1,-1)、无 translate(0.5,0.5,0.5)
        m.put("black_steel_targe",
                new CataclysmWeaponSpec("BLACK_STEEL_TARGE_MODEL", "BLACK_STEEL_TARGE_TEXTURE", BewlrModel.CM_FLIP));
        m.put("azure_sea_shield",
                new CataclysmWeaponSpec("AZURE_SEA_SHIELD_MODEL", "AZURE_SEA_SHIELD_TEXTURE", BewlrModel.CM_FLIP));
        m.put("coral_spear",
                new CataclysmWeaponSpec("CORAL_SPEAR_MODEL", "CORAL_SPEAR_TEXTURE", BewlrModel.CM_FLIP));
        m.put("coral_bardiche",
                new CataclysmWeaponSpec("CORAL_BARDICHE_MODEL", "CORAL_BARDICHE_TEXTURE", BewlrModel.CM_FLIP));
        return Map.copyOf(m);
    }

    /** 反射读取 BEWLR 模型 + 来源模组的预变换类别。 */
    static BewlrModel find(ItemStack stack) {
        // ⚠️ 字段名映射表({@link #CATACLYSM_WEAPON_SPECS} 与通用约定)都以<b>纯路径</b>
        // (无命名空间)为 key —— 曾误用 toString()(带命名空间,如 cataclysm:coral_spear)
        // 导致灾变精确映射恒 miss、通用兜底字段名含冒号永远找不到 → 灾变/永恒星光
        // BEWLR 描边整体失效(2026-09-06 回归,git c108d19 引入)。命名空间层面的排除
        // (TACZ)已在 BEWLR 派发逻辑顶部完成,这里只取 path。
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        BewlrModel found = readBewlrModelField(path);
        if (found == null) {
            // 永恒星光等模组延迟初始化模型字段:触发一次 BEWLR 渲染使其创建
            ensureBewlrModelsLoaded(stack);
            found = readBewlrModelField(path);
        }
        return found;
    }

    /**
     * 遍历 {@link #BEWLR_MODEL_HOLDER_CLASSES},沿父类链反射读静态字段
     * (模型 + 预变换类别 + 本体纹理)。字段名解析(按物品注册 ID 的<b>纯路径</b>):
     * <ol>
     *   <li>先查灾变精确映射表 {@link #CATACLYSM_WEAPON_SPECS}(字段名缩写/缺后缀/
     *       预变换差异,见该表注释);</li>
     *   <li>未命中走通用约定 = 物品路径大写 + "_MODEL"(兼容未来版本字段名调整)。</li>
     * </ol>
     * 纹理字段同理(映射表显式指定;或通用 = 字段名把 "_MODEL" 换 "_TEXTURE" 查持有类,
     * 回退模型类 TEXTURE,如永恒星光 CrescentSpearModel.TEXTURE)。
     *
     * @param itemPath 物品注册 ID 的纯路径(无命名空间),如 coral_spear
     * @return 解析到的模型;未命中返回 null
     */
    private static BewlrModel readBewlrModelField(String itemPath) {
        // 先查灾变精确映射表(字段名/纹理名/预变换均与 renderByItem 字节码一致)。
        // TACZ 的命名空间排除已在 BEWLR 派发逻辑顶部完成,此处
        // 只处理会命中静态字段适配的模组。
        CataclysmWeaponSpec cataclysmSpec = CATACLYSM_WEAPON_SPECS.get(itemPath);
        String modelField;
        if (cataclysmSpec != null) {
            modelField = cataclysmSpec.modelField();
        } else {
            // 通用约定:物品路径大写 + "_MODEL"(输入已去命名空间,不会含冒号)
            modelField = itemPath.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_') + "_MODEL";
        }
        for (String cls : BEWLR_MODEL_HOLDER_CLASSES) {
            int preTransform = cataclysmSpec != null
                    ? cataclysmSpec.preTransform()
                    : holderPreTransform(cls);
            try {
                Class<?> c = Class.forName(cls);
                Field f = OutlineRenderer.findField(c, modelField);
                if (f == null) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    // ⚠️ 灾变方块类物品(BEWLR block 模型,如 EMP/机械铁砧/祭坛/雕像/刷怪笼)
                    // 分支预变换是 translate(0.5,1.5,0.5)+scale(1,-1,-1),与武器
                    // (0.5,0.5,0.5)不同 —— 按模型类包名自动修正,否则描边 Y 偏移 1 格
                    // (3.32 renderByItem 实测:全部 9 个 block 分支 translate Y=1.5)。
                    if (preTransform == BewlrModel.CM_CENTER_FLIP
                            && v.getClass().getName().contains(".model.block.")) {
                        preTransform = BewlrModel.CM_CENTER_FLIP_TOP;
                    }
                    ResourceLocation tex;
                    if (cataclysmSpec != null) {
                        // 灾变：映射表显式指定纹理字段 (SOUL_RENDER 等无 _MODEL 后缀，
                        // 通用 replace 换不出 _TEXTURE，必须显式)
                        tex = readTextureField(c, cataclysmSpec.textureField());
                    } else {
                        // 通用约定:持有类静态 <ID>_TEXTURE,回退模型类 TEXTURE
                        tex = readTextureField(c, modelField.replace("_MODEL", "_TEXTURE"));
                        if (tex == null) {
                            tex = readTextureField(v.getClass(), "TEXTURE");
                        }
                    }
                    return new BewlrModel(v, preTransform, tex);
                }
            } catch (Exception ignored) {
                // 类不存在/字段不符/访问失败:跳过该模组适配
            }
        }
        return null;
    }

    /** 按持有类名推断默认预变换类别(未列入灾变映射表时)。 */
    private static int holderPreTransform(String cls) {
        if (cls.startsWith("cn.leolezury.eternalstarlight")) {
            return BewlrModel.ES_FLIP;
        }
        if (cls.startsWith("com.github.L_Ender.cataclysm")) {
            return BewlrModel.CM_CENTER_FLIP;
        }
        return BewlrModel.NONE;
    }

    /** 读类的静态 ResourceLocation 字段(沿父类链),失败返回 null。 */
    private static ResourceLocation readTextureField(Class<?> clazz, String name) {
        try {
            Field f = OutlineRenderer.findField(clazz, name);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof ResourceLocation rl ? rl : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 触发 BEWLR 渲染一次以初始化其内部模型字段(渲染输出到临时 buffer,不 flush,
     * 无视觉副作用)。仅当模型字段为 null(延迟初始化模组)时调用。
     */
    private static void ensureBewlrModelsLoaded(ItemStack stack) {
        try {
            IClientItemExtensions ext = IClientItemExtensions.of(stack);
            BlockEntityWithoutLevelRenderer bewlr = ext.getCustomRenderer();
            if (bewlr == null) {
                return;
            }
            MultiBufferSource.BufferSource tmp = MultiBufferSource.immediate(new ByteBufferBuilder(1024));
            try {
                bewlr.renderByItem(stack, ItemDisplayContext.NONE, new PoseStack(), tmp,
                        0xF000F0, OverlayTexture.NO_OVERLAY);
                tmp.endBatch();
            } catch (Exception ignored) {
                // 某些 BEWLR 渲染依赖 world/player 上下文,失败不影响描边
            }
        } catch (Exception ignored) {
        }
    }

    /** 读取 Mojang {@link Model} 的 root ModelPart 字段(沿父类链)。 */
    static ModelPart modelRootOf(Model model) {
        try {
            Field f = OutlineRenderer.findField(model.getClass(), "root");
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            return (ModelPart) f.get(model);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 应用 BEWLR 渲染器内部的模组级预变换(见 {@link #BEWLR_MODEL_HOLDER_CLASSES} 注释)。
     * 顺序与本体 BEWLR 分支一致:先 translate 后 scale。
     *
     * @param pose         目标 PoseStack(已在 display transform 之后)
     * @param preTransform {@link BewlrModel} 预变换类别常量
     */
    static void applyBewlrPreTransform(PoseStack pose, int preTransform) {
        switch (preTransform) {
            case BewlrModel.ES_FLIP -> pose.scale(1.0f, -1.0f, -1.0f);
            case BewlrModel.CM_CENTER_FLIP -> {
                pose.translate(0.5f, 0.5f, 0.5f);
                pose.scale(1.0f, -1.0f, -1.0f);
            }
            case BewlrModel.CM_FLIP -> pose.scale(1.0f, -1.0f, -1.0f);
            case BewlrModel.CM_CENTER_FLIP_TOP -> {
                pose.translate(0.5f, 1.5f, 0.5f);
                pose.scale(1.0f, -1.0f, -1.0f);
            }
            default -> {
            }
        }
    }
}
