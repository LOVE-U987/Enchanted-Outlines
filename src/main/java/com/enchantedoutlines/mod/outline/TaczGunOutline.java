package com.enchantedoutlines.mod.outline;

import java.util.List;

import com.enchantedoutlines.mod.config.Config;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.FunctionalBedrockPart;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockCubePerFace;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.GunDisplayInstance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.joml.Matrix4f;

/**
 * TACZ 枪械(自绘 Bedrock BEWLR)专项 3D 描边子模块 —— 2026-10-18 兼容算法重做。
 * <p>
 * <b>重做背景</b>(旧"通用捕获"对 TaCZ 无用的根因,经反编译 TaCZ 1.1.8 实证):
 * <ol>
 *   <li>第一人称被旁路:simplebedrockmodel IFP 系统取消原版 {@code RenderHandEvent}
 *       后直接调 {@code renderFirstPerson},原版注入点根本不执行;TaCZ 又以
 *       GameRenderer mixin 修改 bob/FOV(手持 FOV 与世界 FOV 分离),pass 外猜
 *       pose 必错位。</li>
 *   <li>第三人称几何被代码级修改:LOD 按距离换模、附件动态挂载、改枪实时变换,
 *       资源与实际渲染不同步;旧 v2 捕获以 identity pose 调 {@code renderByItem}
 *       → LOD 距离判定失真 → 捕获模型与本体不一致。</li>
 * </ol>
 * <b>新算法(模型走查)</b>:由 {@code TaczGunModelMixin} 在
 * {@code BedrockGunModel.render} 六参重载(第一人称与第三人称/GROUND/FIXED 两条
 * 渲染链的唯一汇聚点)TAIL 注入 —— 此刻传入的 PoseStack 即本体正在使用的<b>最终
 * 变换</b>(视角摆动/开镜/后座/动画/LOD 选模全部就位)。本类在该 pose 下遍历
 * {@code getShouldRender()} 模型树:
 * <ol>
 *   <li>每个部件 <b>直接调用公开方法</b> {@link BedrockPart#translateAndRotateAndScale}
 *       (零复刻误差,动画偏移/旋转/缩放与本体同帧同字段);</li>
 *   <li>每个 cube 绕自身包围盒中心做放大壳 T(c)·S·T(-c)(与
 *       {@code renderEntityModelOutline}/盔甲同一套几何语义,固定放大壳,不均匀
 *       自适应 —— 铁律 6.5:长枪类走固定壳),再调用公开方法
 *       {@code cube.compile(pose, consumer, ...)} 让 <b>TaCZ 自己的顶点代码</b>
 *       向描边 buffer 发射外扩几何 —— 顶点写法/UV/法线与本体 100% 同源;</li>
 *   <li>描边 RenderType 走 {@link OutlineRenderer#armorOutlineRenderType}
 *       (采样本体贴图 alpha 决定形状,铁律 3.6 统一约定)。</li>
 * </ol>
 * <b>已知限制(接受)</b>:瞄具/弹匣等<b>附件</b>由独立模型渲染(不在枪模型树内),
 * 第一版无附件描边(二期经 AttachmentRender 补挂);贴图取高模贴图(LOD 远距下
 * 形状遮罩近似,视觉差异可忽略)。对 Accelerated Rendering 加速渲染环境天然兼容
 * (只读模型数据,不依赖本体顶点输出)。功能件(第一/第二人称手臂、枪口火焰/弹壳
 * /激光、护木变体)是模型树内的 FunctionalBedrockPart 功能渲染器节点 —— 本体
 * <b>从不</b>编译其 cubes/children(只渲染功能件输出),走查同步跳过(实机反馈
 * 修复:枪模包预置的隐藏手臂方块曾被描出,2026-10-18)。
 * <p>
 * <b>类加载边界</b>:本类仅由 {@code TaczGunModelMixin}(TACZ 存在时才应用的
 * mixin)触发加载,经 {@code OutlineRenderer#taczOutline()} 懒构造 —— TACZ 缺席
 * 时本类不加载,任何 TACZ 类型引用都不会被解析。
 * <p>
 * 类本身 public(mixin 跨包调用静态入口),但构造器包私有 —— 仅宿主
 * {@link OutlineRenderer} 可构造实例。
 */
public final class TaczGunOutline {

    /** 宿主渲染器(描边缓冲/RenderType/uniform 的统一入口,构造只存引用)。 */
    private final OutlineRenderer renderer;

    /** 走查用复用矩阵:保存 cube 局部 pose(与 renderPartInflated 同款,免每 cube 分配)。 */
    private final Matrix4f original = new Matrix4f();

    /** 走查用复用矩阵:T(c)·S·T(-c) 外扩变换临时(纯读源写目标,可复用)。 */
    private final Matrix4f transform = new Matrix4f();

    /**
     * @param renderer 宿主渲染器(OutlineRenderer 单例;构造只存引用,无回调)
     */
    TaczGunOutline(OutlineRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * mixin TAIL 静态入口:{@code TaczGunModelMixin} 在
     * {@code BedrockGunModel.render} 六参重载返回后调用。
     * <p>
     * 经宿主单例懒取子模块实例(首次调用即 TaCZ 存在场景,此后复用)。
     *
     * @param model         正在渲染的枪械模型(mixin 的 {@code this})
     * @param stack         正在渲染的枪械物品
     * @param transformType 显示上下文(第一/第三人称/GROUND/FIXED)
     * @param renderType    本体 RenderType(未直接使用,贴图经 API 解析;保留参数便于
     *                      未来精确取 LOD 贴图)
     * @param pose          本体最终 PoseStack(TAIL 时刻,含全部 TaCZ 变换)
     */
    public static void onGunModelRenderTail(BedrockGunModel model, ItemStack stack, ItemDisplayContext transformType,
                                            RenderType renderType, PoseStack pose) {
        OutlineRenderer.INSTANCE.taczOutline().renderOutline(model, stack, transformType, pose);
    }

    /**
     * 走查描边主入口:门禁判定 → 解析描边色/厚度/贴图 → 遍历模型树发射外扩壳。
     * <p>
     * 任一门禁不过即静默返回(宁可无描边,不画错描边);几何发射与本体同 pose,
     * 无需捕获守卫(走查读的是模型树结构,不存在"垃圾几何"来源)。
     *
     * @param model         枪械模型
     * @param stack         枪械物品
     * @param transformType 显示上下文
     * @param pose          本体最终 PoseStack
     */
    void renderOutline(BedrockGunModel model, ItemStack stack, ItemDisplayContext transformType, PoseStack pose) {
        // 总开关(ENABLE_TACZ_COMPAT):关闭 = TaCZ 枪械无描边(用户可关回"无描边"兜底)
        if (!Config.ENABLE_TACZ_COMPAT.get()) {
            return;
        }
        // 场景过滤:仅六个世界 context;GUI(背包 slot 2D 平面)不经 BedrockGunModel.render,
        // 由既有平坦捕获处理;其余未知 context(NONE 等)保守跳过
        if (!isOutlinedContext(transformType)) {
            return;
        }
        // 描边着色器未就绪(资源加载中)时与其它 3D 路径一致直接跳过
        if (renderer.outlineShader == null) {
            return;
        }
        int color = ColorResolver.resolve(stack);
        if (color == -1) {
            return;
        }
        float thickness = ColorResolver.thickness(stack);
        if (thickness <= 0f) {
            return;
        }
        // 第一人称厚度倍率(实机反馈第一人称环宽偏细,默认 2.0;仅 FIRST_PERSON_* 生效,
        // 第三人称/掉落物/展示框不受影响)
        if (transformType.firstPerson()) {
            thickness *= (float) (double) Config.TACZ_FIRST_PERSON_THICKNESS_SCALE.get();
        }
        ResourceLocation texture = resolveTexture(stack);
        if (texture == null) {
            return;
        }
        // 外扩系数:与 renderEntityModelOutline 同源(1 + thickness×THICKNESS_SCALE×BEWLR_3D_SCALE)
        float scale = 1f + thickness * OutlineRenderer.THICKNESS_SCALE * OutlineRenderer.bewlrInflatePerThickness();
        renderer.setOutlineAlphaBoost(2.0f);
        VertexConsumer consumer = renderer.outlineBuffers.getBuffer(renderer.armorOutlineRenderType(texture, color));
        int packedColor = 0xFF000000 | (color & 0xFFFFFF);
        // 从本体刚渲染完的同一批根部件走查(与本体同树同帧)
        List<BedrockPart> roots = model.getShouldRender();
        if (roots != null) {
            for (BedrockPart root : roots) {
                walkPart(root, pose, consumer, packedColor, scale);
            }
        }
        renderer.outlineBuffers.endBatch();
    }

    /**
     * 场景过滤:仅描边六个世界 context。
     *
     * @param ctx 显示上下文
     * @return true = 应当描边
     */
    private static boolean isOutlinedContext(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || ctx == ItemDisplayContext.GROUND
                || ctx == ItemDisplayContext.FIXED;
    }

    /**
     * 解析枪械贴图(高模贴图;LOD 远距下形状遮罩为近似,见类注释"已知限制")。
     *
     * @param stack 枪械物品
     * @return 贴图;解析不到返回 null(调用方跳过描边)
     */
    private static ResourceLocation resolveTexture(ItemStack stack) {
        return TimelessAPI.getGunDisplay(stack)
                .map(GunDisplayInstance::getModelTexture)
                .orElse(null);
    }

    /**
     * 递归走查部件树并发射外扩壳。
     * <p>
     * 复刻本体 {@code BedrockPart.render} 的执行结构:功能件节点判定 →
     * visible/空树判定 → pushPose → <b>直接调用公开方法</b>
     * {@code translateAndRotateAndScale}(动画偏移/旋转/缩放与本体逐位一致)→
     * 本部件逐 cube 发射 → children 递归 → popPose。任一环节异常由 mixin 顶层
     * catch 兜底(铁律 6.7)。
     *
     * @param part        部件(可为根或子节点)
     * @param pose        本体最终 PoseStack(调用方持有,本方法自行 push/pop)
     * @param consumer    描边 buffer(armorOutlineRenderType 已取)
     * @param packedColor 描边 ARGB(不透明)
     * @param scale       外扩放大系数
     */
    private void walkPart(BedrockPart part, PoseStack pose, VertexConsumer consumer, int packedColor, float scale) {
        if (part == null) {
            return;
        }
        // 功能件节点(lefthand_pos/righthand_pos 手臂、枪口火焰、护木变体等):
        // FunctionalBedrockPart.render 语义 = 有功能渲染器时<b>只</b>渲染功能件输出,
        // 本节点 cubes/children 从不编译 —— 走查同步跳过,否则会描边本体不可见的
        // 隐藏几何(实机反馈:枪模包预置的手臂方块被描出,2026-10-18)
        if (isFunctionalNode(part)) {
            return;
        }
        if (!part.visible) {
            return;
        }
        if (part.cubes.isEmpty() && part.children.isEmpty()) {
            return;
        }
        pose.pushPose();
        try {
            // 与本体逐位一致:直接调用 BedrockPart 公开变换方法(动画字段同帧读取)
            part.translateAndRotateAndScale(pose);
            for (BedrockCube cube : part.cubes) {
                emitCubeOutline(cube, pose, consumer, packedColor, scale);
            }
            for (BedrockPart child : part.children) {
                walkPart(child, pose, consumer, packedColor, scale);
            }
        } finally {
            pose.popPose();
        }
    }

    /**
     * 判定是否为功能件节点(镜像 {@code FunctionalBedrockPart.render} 的分流条件):
     * 节点持有功能渲染器且渲染器实例非 null 时,本体走功能件输出路径
     * (cubes/children 不编译)。
     *
     * @param part 部件
     * @return true = 功能件节点,走查应跳过其 cubes/children
     */
    private static boolean isFunctionalNode(BedrockPart part) {
        if (part instanceof FunctionalBedrockPart functional && functional.functionalRenderer != null) {
            return functional.functionalRenderer.apply(part) != null;
        }
        return false;
    }

    /**
     * 发射单个 cube 的外扩壳:绕 cube 包围盒中心放大 T(c)·S·T(-c) 后,交由
     * <b>TaCZ 自己的</b> {@code cube.compile} 向描边 buffer 写顶点 —— 顶点写法
     * (÷16/UV/法线)与本体 100% 同源,零手写几何。
     * <p>
     * 单位约定(铁律 6.5 同款):cube 公开 min/max 为像素单位,compile 内部 ÷16,
     * 故中心 ÷16 转模型单位。未知 BedrockCube 实现(第三方扩展)保守跳过该 cube。
     *
     * @param cube        部件内的 cube
     * @param pose        已含部件链变换的 PoseStack(本方法原位乘外扩矩阵后还原)
     * @param consumer    描边 buffer
     * @param packedColor 描边 ARGB
     * @param scale       外扩放大系数
     */
    private void emitCubeOutline(BedrockCube cube, PoseStack pose, VertexConsumer consumer, int packedColor, float scale) {
        float minX, minY, minZ, maxX, maxY, maxZ;
        if (cube instanceof BedrockCubeBox box) {
            minX = box.minX;
            minY = box.minY;
            minZ = box.minZ;
            maxX = box.maxX;
            maxY = box.maxY;
            maxZ = box.maxZ;
        } else if (cube instanceof BedrockCubePerFace face) {
            minX = face.minX;
            minY = face.minY;
            minZ = face.minZ;
            maxX = face.maxX;
            maxY = face.maxY;
            maxZ = face.maxZ;
        } else {
            // 未知 cube 实现:保守跳过(宁可缺一角,不画错形状)
            return;
        }
        float cx = (minX + maxX) / 2.0f / 16.0f;
        float cy = (minY + maxY) / 2.0f / 16.0f;
        float cz = (minZ + maxZ) / 2.0f / 16.0f;
        PoseStack.Pose p = pose.last();
        original.set(p.pose());
        // T(c)·S·T(-c):绕 cube 自身中心放大(与 renderPartInflated 同构)
        transform.identity()
                .translate(cx, cy, cz)
                .scale(scale)
                .translate(-cx, -cy, -cz);
        p.pose().mul(transform);
        try {
            float r = ((packedColor >>> 16) & 0xFF) / 255.0f;
            float g = ((packedColor >>> 8) & 0xFF) / 255.0f;
            float b = (packedColor & 0xFF) / 255.0f;
            float a = ((packedColor >>> 24) & 0xFF) / 255.0f;
            cube.compile(p, consumer, OutlineRenderer.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, r, g, b, a);
        } finally {
            p.pose().set(original);
        }
    }
}
