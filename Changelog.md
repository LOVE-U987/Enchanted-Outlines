# Enchanted Outlines Changelog

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
