# Enchanted Outlines Changelog

---

## v0.3.0

### 修复:模组盔甲描边比原版厚(2026-11-01,实机反馈)

- 根因:GeckoLib/AzureLib 盔甲走顶点法线外扩,外扩量直接用了全量 offset
  (`thickness×THICKNESS_SCALE×ARMOR_INFLATE`);原版盔甲路径是"绕部件中心放大壳
  + 均匀自适应",有效外扩还要乘参考半对角线 `ARMOR_REF_DIAG`(0.468)—— 模组
  盔甲的视觉环宽因此是原版的约 2.14 倍。
- 修复:Geo 盔甲路径外扩量乘 `ARMOR_REF_DIAG` 对齐原版视觉;`ARMOR_REF_DIAG`
  改包内可见(Geo 子模块对齐用)。法线外扩恒定即为均匀外扩语义,不依赖
  `armorUniformExpand` 开关。

### 修复:长文本编辑屏(2026-11-01,实机反馈)

- 长文本配置(命名空间适配器等)二级编辑屏的 tooltip 原为单行居中绘制,过长时
  溢出面板被裁切;改为 font.split 按面板宽度分行,最多 6 行,超出省略号截断。
- 编辑框由单行 EditBox 升级为 MultiLineEditBox(自带纵向滚动条、无 maxLength
  截断),长配置可纵向滚动查看与编辑。

### 修复:ThreadLocal 标记异常滞留(2026-11-01)

- `GuiGraphicsMixin`/`ItemInHandRendererMixin` 的 IN_GUI_RENDER / 手持标记原由
  RETURN 注入恢复:目标方法体异常时 RETURN 不执行,标记永久滞留(开光影用户的世界
  描边整体失效)。改用 MixinExtras `@WrapOperation` 包住本体渲染调用,finally 恢复
  对异常路径全覆盖;HEAD 注入补空物品早退(避免空物品时标记无人恢复)。

### 质量:缓存登记与节流上限(2026-11-01)

- 三叉戟 mixin 的 cachedTridentRoot 改自校验(记录 EntityModelSet 归属,资源重载自动重烘);
- SodiumCompat.MODEL_SPRITES 接入 invalidateCaches 失效链;
- 两个节流日志表(纹理读取失败/捕获守卫)加 256 条容量兜底。

### 质量:注释残留与死代码清理(2026-11-01)

- OutlineRenderer:3 处孤儿 javadoc 删除(BEWLR_3D_SCALE "默认 0.3" 矛盾注释修正为
  0.1 并归位方法、鞘翅系数历史注释、序列图旧版双 javadoc);4 个死 import;
- armorRenderTypes 双生产方(WHITE key 共写)补审计注记;
- GeoOutline 死 import;配置界面 CategoryButton 死参数。

### 新增:关闭原版紫光保留描边(2026-11-01)

- 新配置 `disableVanillaGlint`(默认 false):开启后拦截 ItemRenderer 三个 foil
  buffer 静态中枢(getFoilBuffer/getFoilBufferDirect/getArmorFoilBuffer,覆盖物品/
  盾牌/三叉戟/BEWLR/投掷物/鞘翅)+ HumanoidArmorLayer.renderGlint HEAD 取消
  (盔甲紫光不经中枢)。新 mixin `ItemRendererFoilMixin`。只影响视觉,附魔逻辑不变。

### 新增:稀有度自动取色(2026-11-01)

- 新配置 `enchantRarityAutoColor`(默认 false)+ `rarityColors`(common,uncommon,
  rare,epic 四色,默认沿用原版稀有度名色)。取色链:逐物品 > 逐附魔 > 稀有度 > 默认;
  附魔稀有度按 definition().weight() 映射(<=1 史诗 / 2 稀有 / 3-4 非凡 / >=5 常见)。

### 新增:展示框/掉落物独立厚度(2026-11-01)

- 新配置 `itemFrameThickness` / `groundThickness`(默认 -1 跟随全局,>=0 覆盖)。

### 新增:描边呼吸脉动(2026-11-01)

- 新配置 `pulseEffect`(默认 false)+ `pulseSpeed`(0.2-3.0)。走 OutlineAlphaBoost
  uniform 乘法调制(0.75..1.0 正弦);硬切不透明路径输出 alpha 恒 1,天然不呼吸。

### 新增:形状纹理缓存 LRU 上限(2026-11-01)

- 新配置 `shapeTextureCacheLimit`(32-2048,默认 256):shapeTextures 改 accessOrder
  LinkedHashMap,超限驱逐最久未用条目并 TextureManager.release 注销,防显存泄漏。

### 新增:配置界面重排 + 颜色选择器(2026-11-01)

- 4 分类 → 6 分类(通用/颜色与取色/逐物品规则/光影兼容/模组兼容/高级),实验性
  分类删除,RPG 虹灯转正;多附魔取色移入颜色分类;
- defaultColor 行新增色块入口 + ColorSwatchScreen 色板选择器(25 预设色网格 +
  hex 输入 + 预览三向同步);新增整数输入行类型。

### 工具:VERIFY 新增铁律 6.9 检查(2026-11-01)

- 新检查「描边厚度语义一致」:thickness 与裸数字字面量相乘即 error(厚度系数必须
  走具名常量/配置;历史事故:鞘翅独立系数 0.09)。verify_selftest 已加注入用例。

---

## v0.2.2

### 重构:TACZ 兼容算法推倒重做 —— TaCZ 内部注入走查(2026-10-18)

- **背景(用户判定:旧算法无用,需推倒重做)**:第一人称从未有描边
  (simplebedrockmodel IFP 系统取消原版 RenderHandEvent 后自渲染,旧注入点根本
  不执行);第三人称/GROUND 捕获的几何与本体不同步(LOD 按距离换模/附件动态挂载/
  改枪实时变换 —— 资源与实际渲染脱节;v2 identity pose 调 renderByItem → LOD
  距离判定失真,捕获模型与本体不一致)。
- **反编译实证**(TaCZ 1.1.8-hotfix-r6 + jar-in-jar simplebedrockmodel 2.2.1):
  - 第一人称旁路链:`FirstPersonRenderHandler.onRenderHand` 直接
    `event.setCanceled(true)` 后调 `renderer.renderFirstPerson` —— 原版
    `ItemInHandRenderer.renderItem` 在第一人称不会执行;TaCZ 另以 GameRenderer
    mixin 改 bob/FOV(手持 FOV 与世界 FOV 分离,`cacheMuzzlePosition` 有
    tan(itemFov)/tan(worldFov) 补偿),pass 外猜 pose 必错位;
  - `BedrockGunModel.render` 六参重载是第一人称(`renderFirstPersonInner`)与
    第三人称/GROUND/FIXED(`renderByItem`)两条渲染链的**唯一汇聚点**,参数自带
    stack/context/贴图,此刻 PoseStack 即本体最终变换。
- **新算法(TaCZ 内部注入走查)**:
  - `TaczGunModelMixin`(新,`mixin/tacz/`):`BedrockGunModel.render(6参)` TAIL
    注入,经 `TaczMixinPlugin` 条件应用(TACZ 缺席时整个 mixin 配置不应用,
    零类加载);mixin 顶层 try/catch(Throwable)/finally flush(铁律 6.7)+
    `markOutlinedItem` 标记(逐物品决策)。
  - `TaczGunOutline`(新,`outline/`):TAIL 时刻本体最终 pose 下遍历
    `getShouldRender()`,逐部件**直调公开方法** `translateAndRotateAndScale`
    (零复刻误差,动画偏移/旋转/缩放与本体同帧同字段),逐 cube 绕包围盒中心
    放大壳 T(c)·S·T(-c) 后调公开方法 `cube.compile` 让 **TaCZ 自己的顶点代码**
    向描边 buffer 发射(顶点写法/UV/法线与本体 100% 同源);RenderType 走
    `armorOutlineRenderType`(采样本体贴图 alpha 决定形状,铁律 3.6);场景过滤 =
    六个世界 context,GUI 不经此路径。懒构造 + mixin 门控双保险:TaCZ 缺席时
    算法类不加载。
  - 对 Accelerated Rendering 加速渲染环境天然兼容(只读模型数据,不依赖本体顶点
    输出);枪口火焰/弹壳/激光等功能件不在模型树内,天然不被描边。
- **旧路径处置(用户决策)**:`renderBewlrEntityOutline` 对 TaCZ 非 GUI context
  直接返回 false(旧捕获路径对世界场景退役,避免双描边);**GUI 保留 v2.1 平坦
  捕获**(SlotModel 单面 quad 场景本就正常);旧"第一人称跳过"逻辑删除 ——
  重做后第一人称已可描边。
- **工程**:build.gradle 增 `compileOnly fileTree(compat-libs)`(TACZ 发行 jar,
  仅编译期、不入库、不分发;`.gitignore` + `compat-libs/README.md` 约定,版本
  升级只换文件);新增 `enchanted_outlines.tacz.mixins.json` 配置 +
  `neoforge.mods.toml` 模板第二 `[[mixins]]` 声明。
- **已知限制(接受)**:瞄具/弹匣等**附件**由独立模型渲染(不在枪模型树内),第一版
  无附件描边(二期经 AttachmentRender 补挂);LOD 远距描边贴图取高模贴图(形状
  遮罩近似,视觉差异可忽略)。
- **验证**:`verify.bat` 全绿(12 项);`tools/verify.py` mixin 注册/批次闭合检查
  升级为递归扫描 + plugin 豁免(多 mixin 配置支持),`verify_selftest.py` 10/10
  通过;实机首验整体通过,三处细节修复见下条。

### 修复:TACZ 走查实机反馈三连 —— 隐藏手臂方块/无光影覆盖壳/待机浮动深度竞争(2026-10-18)

- **实机反馈(用户首验,整体通过后的三个细节)**:
  1. 轮廓包含了手臂;
  2. 无光影下轮廓覆盖枪身;
  3. 光影下待机浮动时机身穿出轮廓/与轮廓重叠闪烁。
- **根因与修复**:
  1. **手臂描出**:TaCZ 枪模包在 `lefthand_pos`/`righthand_pos` 等功能节点预置
     手臂方块,运行时由 `FunctionalBedrockPart` 功能渲染器(LeftHandRender 等)
     **替换**为原版手臂 —— 本体<b>从不</b>编译这些节点的 cubes/children
     (FunctionalBedrockPart.render:有功能渲染器时只渲染功能件输出)。
     走查未镜像该语义 → 描出本体不可见的隐藏手臂方块。修复:walkPart 增加
     `isFunctionalNode` 判定(镜像 FunctionalBedrockPart.render 分流条件),
     功能节点整体跳过。
  2. **无光影覆盖枪身**:项目全部描边路径的统一绘制顺序 = <b>描边先画(壳不写
     深度)→ 本体后画盖住壳中心 → 只露外扩环</b>;走查 mixin 原本打在
     `BedrockGunModel.render` TAIL,顺序倒置 → 无深度遮蔽时壳整体糊在枪身上。
     修复:注入点 TAIL → HEAD(方法体内激光/瞄具挂载块 push/pop 平衡已实证,
     HEAD 时刻 PoseStack = super.render 收到时的矩阵,几何完全等价)。
  3. **待机浮动重叠闪烁**:与 2 同根 —— TAIL 顺序倒置使描边与本体深度竞争
     (z-fighting)。HEAD 修复后本体永远绘制在壳之上,结构上消除;若光影下仍有
     与待机浮动同幅度的小偏移,为光影包 GPU 侧手部动画(如 EuphoriaPatches
     breathing hand)只作用于本体着色程序所致,待复测确认。
- **验证**:`gradlew compileJava` 通过;`verify.bat` 全绿(12 项);实机复测待用户进行。

### 调整:TACZ 第一人称描边厚度倍率(2026-10-18,实机复测通过后的微调)

- **需求(用户)**:第一人称描边环宽偏细,加厚 2 倍。
- **实现**:新增配置键 `taczFirstPersonThicknessScale`(默认 **2.0**,范围 0.5-4.0)——
  仅 FIRST_PERSON_* 上下文将描边厚度乘以本倍率,第三人称/掉落物/展示框不变;
  禁止硬编码配置(铁律),配置界面"高级"页新增滑杆行 + zh_cn/en_us 语言键同步
  (语言键一致性检查 23→24 行全绿)。顺带把 `enableTaczCompat` 的配置注释与
  双语 tooltip 从过时的"捕获"语义更新为走查 mixin 语义。
- **验证**:`gradlew compileJava` 通过;`verify.bat` 全绿(12 项);实机确认待用户进行。

### 修复:动态物品附魔后动画冻结(Sodium 兼容)+ 描边随序列纹理动画(2026-10-25)

- **根因(用户实测确认)**:Sodium 0.8 `animate_only_visible_textures`(仅动画可见纹理)
  对附魔物品渲染路径的可见性标记失效 → atlas 序列精灵被误判不可见而停止 tick,
  附魔物品动画冻结;同款无附魔物品渲染会重新标记精灵,冻结随之解除。关闭描边
  模组仍复现(描边无罪);完整根因链见知识库「项目经验」2026-10-25 条目。
- **SodiumCompat(新增)**:反射调用 Sodium 公开 API `SpriteUtil.markSpriteActive`,
  在三个物品渲染入口(GuiGraphicsMixin/ItemRendererMixin/ItemInHandRendererMixin)
  HEAD 对附魔物品模型引用的精灵逐帧标记活跃 —— 保留 Sodium 优化,仅豁免附魔
  物品。无 Sodium 硬依赖(未装零开销);配置 `sodiumAnimatedTextureCompat`
  (默认 true),独立于描边总开关。精灵列表按 BakedModel 弱引用缓存。
- **帧感知描边形状**:`SpriteContentsAnimatedTextureMixin` 在原版
  `AnimatedTexture.uploadFrame` HEAD 捕获当前帧格索引(标准 HEAD 参数捕获;
  首版按 @Shadow 合成外类字段挂 Ticker,运行期 remap 后字段名失配导致启动崩溃,
  已改为零 Shadow + 运行期按字段类型解析外类引用,记录到
  `OutlineRenderer.spriteFrameTracker`),`shapeTextureFor` 形状缓存键加入帧号、
  `cropToFrame0` 扩展为 `cropToFrame`(帧格裁剪)→ 扁平物品描边随序列纹理动画
  (星尘/突变心脏类),不再恒为帧 0。
- **R2 隐患顺手修复**:`animatedSampleTexture` 的"死注册 → 首帧烘焙"判定不再
  永久缓存 —— 缓存命中时重校验注册纹理活性,升级为 AnimatableTexture 后自动
  改绑原 location(实时帧)。
- **文档**:AGENTS.md 铁律 1.5 更新为帧感知语义 + 新增 Sodium 兼容注记。
- **验证**:`verify.bat` 结果见提交说明;实机验收清单(动画恢复/描边随帧/开关
  回归/无 Sodium 环境)待用户进行。
### 新增:资源包自定义轮廓 —— 逐物品颜色/循环动画/厚度(2026-10-31)

- **功能**:资源包可通过
  `assets/enchanted_outlines/outlines/<物品命名空间>/<物品路径>.json`
  为任意物品定义描边:静态色(`mode=static`)或 keyframe 颜色循环(`mode=cycle`,
  调色板/period_ms/offset_ms/easing 线性或 smoothstep 插值),可选逐物品
  `thickness`。免附魔语义不变:定义仍只对已附魔、未禁用、在白名单的物品生效。
- **优先级**:颜色 = 禁用 > RPG 虹灯 > 白名单 > 资源包定义 > 程序化固定色 >
  配置固定色 > 附魔取色 > 默认;厚度 = 程序化 > 资源包 > 配置映射 > 全局。
  跨资源包覆盖走原版语义(排前的包胜出),零自定义优先级逻辑。
- **实现**:`OutlineDef`(不可变定义 + JSON 校验 + 逐帧插值,未知字段忽略,为
  二期 UV 流光预留)+ `OutlineResources`(`SimpleJsonResourceReloadListener`,
  挂 `RegisterClientReloadListenersEvent`,加载时校验物品 id 存在,解析失败
  warn 跳过)。动画逐帧求值,与 RPG 虹灯同款零缓存。
- **扁平路径适配(动画物品)**:形状纹理缓存键含烘焙色,逐帧连续变化的动画色
  会按帧爆纹理 → 动画物品改烘 rgb=白形状纹理(缓存键恒定)+ 顶点色逐帧染色
  (`emitQuadShape` 新增 argb 参数),shader 输出数学等价;动画物品混色决策
  视为纯色。盔甲/3D/BEWLR 路径颜色本就是逐帧顶点色,零改动自动生效。
- **测试资源包**:`test-resources/avaritia-glow-pack/`(无尽贪婪风格五色流光,
  下界合金四件套 600ms 错相);安装与验收说明见其 README。
