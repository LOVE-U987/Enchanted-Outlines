# Enchanted Outlines Changelog

---

## v0.2.1

### 修复:Hazen 武器描边渲染为实心平面 —— 形状纹理读取失败静默退白(2026-10-10)

- **现象(用户实机,光影开,全场景)**:Hazen 武器(GeckoLib GeoItem)描边在
  GUI/手持/掉落物全场景渲染为实心平面(方形背景)。
- **根因**:`readTextureFrame` 读取失败被 `catch(Exception ignored)` **静默吞掉**
  (零日志),`shapeTextureForLocation` 拿到 null 后**退 WHITE_TEXTURE**(alpha 全 1)
  → 放大壳整体不透明 = 实心平面;`animatedSampleTexture` 失败则退**原始 location**
  (整张序列图 sheet)→ 全帧并集。开光影(fallback 分支)+ 读取失败时三条采样分支
  全坏 → 全场景命中。白矩形兜底违反铁律 6.9"贴图提取失败禁止退 WHITE"精神,
  标准路径漏网。
- **修复**:
  - `shapeTextureForLocation` 读取失败**返回 null**(删除 WHITE 兜底),javadoc
    契约同步;调用方 `armorOutlineRenderType` 混色关分支 null 时**降级为 GPU 采样
    原纹理**(`worldEmissiveOutlineRenderType(animatedSampleTexture(texture))`):
    形状仍由真实贴图 alpha 决定,颜色退化为混色(内置 emissive shader 无法屏蔽
    texel.rgb,纯色语义仅在 CPU 形状纹理可用时保证);`geoOutlineRenderType` 防御
    null → 原样 WHITE(纯几何壳本就要 alpha 全 1,语义正确)。
  - `readTextureFrame` 三种失败原因(资源缺失 / ImageIO 不识别 / 异常)经
    `logTextureReadFailure` **节流留痕**(10s/原因#纹理,info 级常驻诊断,同
    `logCaptureReject` 模式)—— 下次复现附 latest.log 即可定位真实根因。
- **验证**:`verify.bat` 全绿(13 项);实机复现进行中。

### 修复:Hazen 2D 平面武器描边为方形 —— v2 平坦检测漏判(方案 C,2026-10-11)

- **现象(用户实机复现)**:首轮修复(兜底转向 + 失败留痕)落地后,方形平面仍在
  (GUI/手持/放置全场景),且 `readTextureFrame` 失败日志**零触发** —— 首轮修复未
  被触达,真根因在更下游。
- **根因**:v2 捕获的平坦检测用纯 AABB 跨度阈值(最薄轴 < 2% 最大跨度)。Hazen
  2D 平面武器模型带**手柄/双面微深度**(> 2%)→ 误判为"立体几何" → 走 3D 顶点
  法线外扩 + `geoOutlineRenderType`(**纯白纯几何,无贴图遮罩**)→ 矩形 quad 的
  描边 = 矩形本身 → 方形平面(铁律 6.5 "扁平 cube 纯几何外扩 = 平面方形"在 v2
  捕获路径的同源复发)。贴图 alpha 形状遮罩只存在于平坦路径,这些武器永远进不去。
- **修复(方案 C,法线平行度检测)**:`BewlrCapture.isFlatGeometry` —— 全部有效
  quad 的法线互相平行于同一坐标轴(绝对值比较,正反面相对两片也算;主轴偏角
  > ~18° 或跨轴即立体)→ 判为平面贴片走平坦路径(面内 8 方向 + 贴图 alpha 形状,
  描边跟随武器轮廓);立体模型不受影响(灾变/盔甲仍走顶点法线外扩)。语义修复
  而非调阈值,与原 2% 跨度检测并存(零厚度退化几何双保险)。铁律 6.9 战场合规
  (仅改 BewlrCapture)。
- **验证**:`verify.bat` 全绿(13 项);实机复现进行中。

### 修复:Hazen 混合几何武器方形环绕 —— 零厚度贴片 quad 双发射(方案 D,2026-10-11)

- **现象(方案 C 实机复现)**:方形平面仍在(GUI/手持/放置),flat# 日志仍零触发
  —— 平坦路径整体未被路由命中,真根因在**模型内部混合几何**。
- **根因(JAR 离线解剖钉死)**:Hazen 武器 geo 模型 = **混合几何** —— starfury:
  3D 手柄 cubes + 2 片 8×8×0 零厚度翅膀;volcano: 3D 手柄/护手 + 2 片 9×9×0 侧饰 +
  **17×32×0 零厚度主刃**;meowmere: 3D 刀刃 + 4 片零厚度猫头装饰。方案 C 的法线
  检测把混合模型整体判为立体(路由没错),但**零厚度装饰 quad 在 v2 立体路径里沿
  自身面法线外扩 → 与本体平行的纯白无遮罩矩形** → 刀刃/装饰周围一圈方形环绕
  (第一人称贴脸 = 巨型粉色方形发光;全场景 + 立体路径零日志全部吻合)。
- **修复(方案 D,混合几何双发射,仅改 BewlrCapture/铁律 6.9 战场)**:
  - `quadSignature`(4 角排序 + 1e-4 量化签名):正/反面**重合的重复 quad**
    (GeckoLib 零厚度 cube 正反两面位集相同)→ 识别为零厚度贴片;
  - **双发射**:有体积 quad → 顶点法线平均外扩(原逻辑,灾变/盔甲零影响);
    零厚度贴片 quad → **面内 8 方向偏移 + 贴图 alpha 形状**(与平坦路径同语义,
    描边跟随装饰轮廓;按各 quad 法线轴选面内两轴);贴图缺失跳过(禁退 WHITE);
  - 每段发射后即时 endBatch(铁律 6.7);新增 `flatmix#` 节流日志作实机验证信号;
    可发射几何为零时返回 null 交 v1(同平坦分支语义)。
- **验证**:`verify.bat` 全绿(13 项);实机复现进行中。

### 修复:附魔武器描边"紫色光斑不贴轮廓" —— glint 层污染捕获 + 叠绘误判(方案 D 修订,2026-10-11)

- **现象(方案 D 实机复现)**:方形消失,但描边不贴合轮廓 —— 附魔武器呈紫色
  光斑/辉光团,不跟随武器剪影。
- **根因(flatmix 节流日志钉死)**:附魔物品渲染两遍(本体 + 附魔 glint 层,
  `VertexMultiConsumer(glint, main)`),捕获把两层顶点**全部**录进同一流:
  1. **纹理污染**:`this.texture = extractTextureOf(首个可提取的 RenderType)` 选中了
     **glint 层纹理**(`minecraft:textures/misc/enchanted_glint_item.png`,半透明
     噪声图)→ 拿它当 alpha 遮罩 = 遮罩完全失效 → 光斑不贴轮廓;
  2. **叠绘误判**:同一几何双写 → 全部 quad 哈希恰好重复 2 次 → "重复 quad = 零
     厚度"判定全部误触发(flatmix 日志:solid=0 / zeroThick=ALL)→ 真 3D cube 也被
     发成面内 8 方向偏移(无体积包裹)。
- **修复(仅改 BewlrCapture)**:
  - 捕获记录 **RenderType 请求顺序**(rtOrder)与 **每 quad 来源层**(quadSources
    进 CapturedGeometry);采样纹理按序取**首个非 glint** 纹理(新增
    `isGlintTexture` 排除;基础层优先);
  - 方案 D 分类升级为**层感知**:`sigCounts`(哈希 → 层 → 次数)—— **跨层重复**
    = glint/glowmask 叠绘 → 非基础层副本跳过(保留基础层一份);**同层重复** =
    零厚度贴片正反面 → 贴图形状发射;签名改用 `quadHash`(long,XOR 顺序无关,
    免 String 分配);
  - flatmix 日志增加 `overlaySkip=` 计数。
- **验证**:`verify.bat` 全绿(13 项);✅ **用户实机确认修复完成**(2026-10-11:tex=本体贴图、overlaySkip 剔除正确、轮廓贴合)。

### 修复:捕获全局缓冲防护统一 —— 根治"捕获顶点泄漏进真实渲染管线"(2026-10-02)

- **审计发现**:铁律 6.7/6.8/6.9 静态审计全数通过后,发现一处结构性泄漏面 ——
  `capturePlain`(v2 identity 与 v1 real-pose 共用的普通捕获)不替换全局
  bufferSource,对直写全局型渲染器(TACZ `BedrockModel.render` 型):
  - **v2 identity 捕获把本地模型坐标顶点漏进真实渲染管线** → 批次 flush 时被解释为
    相机/GUI 像素空间 → 视野中央/原点错位图形(即"巨大/错位轮廓"的残留来源之一);
  - v1 real-pose 捕获泄漏最终坐标(与本体重合,良性)但每帧多跑一次完整渲染;
  - TACZ 每帧需 **3 次完整渲染调用**(v2 plain 泄漏 → v1 plain 泄漏 → v1 swap 才
    捕获成功),双重泄漏 + 性能浪费;`captureLocal` javadoc"空时自动重试旁路"为
    过时注释(与代码不符)。
- **修复**:全局缓冲替换提升为**所有捕获的统一防护** —— 逻辑并入 `capturePlain`
  (捕获窗口内恒替换 `RenderBuffers.bufferSource` → `CaptureBufferSource`,finally
  还原;门禁 `BEWLR_GLOBAL_BUFFER_SWAP` 关闭或字段不可访问时降级纯 `CaptureSource`);
  删除独立的 `captureWithGlobalBufferSwap`(逻辑并入)与 v1 内的二次 swap 重试段。
- **效果**:直写全局渲染器(TACZ)由 **v2 直接捕获成功** —— 每帧 1 次渲染调用
  (3 → 1),泄漏根治,TACZ 升级为 v2 路径(描边随动画 + 平坦贴图形状 GUI 描边 +
  像素语义外扩);其余模组行为不变(它们不碰全局,替换窗口透明)。守卫/回退边界
  (铁律 6.9)语义不变:identity 垃圾由守卫拦下,不重试;捕获为空仍回退 v1。

### 新功能:namespace → adapter 配置注册表(2026-10-02,② 通用化收官)

- **背景**:旧派发链是手写 if-else(TACZ/Hazen 门禁 + 专项优先链);新模组错位需改代码
  换路径,玩家不可控。本次重构将派发链改为配置驱动的命名空间适配器注册表。
- **新增配置**:
  - `bewlrNamespaceAdapters(namespace=kind)` 逗号分隔映射,kind 含:
    `capture`=捕获优先(默认,描边随动画);`legacy`=反射/Geo 直取优先(旧行为);
    `off`=该命名空间一律不描边。
  - 未配置的命名空间走默认 capture 优先链;TACZ/Hazen 的粗粒度 enable*Compat 开关
    叠加生效(总开关关闭时注册表不复活)。
- **实现**:
  - Config: BEWLR_NAMESPACE_ADAPTERS 配置项 + 解析缓存 + invalidateCache + parseAdapterMap
    + namespaceAdapter 查询;EnchantedConfigScreen 加配置行;zh_cn/en_us 语言键。
  - OutlineRenderer:提取 `renderResolverFallback(stack,pose,color,thickness)`(行为保持
    分支:静态字段反射 → Geo 贴图取样);派发链改按 preferred adapter 分支:
    off→跳过→legacy→resolver→capture;默认 capture→resolver。
  - 派发链新模组出问题改配置即可换路径/关闭,不改代码。与逐物品覆盖(①)组合后,通用性
    配置面全面覆盖。

### 新功能:逐物品覆盖 —— 逐物品厚度映射 + 逐物品强制纯色(2026-10-02)

- **背景**: 学自同行 Enchantment Glint Outline 最受欢迎的功能("Different settings
  on a per-item basis");在现有逐物品颜色(itemColors)+ 黑白名单基础上补齐两块。
- **新增配置**:
  - `itemThicknesses`(逐物品厚度映射,`itemid=像素数` 逗号分隔,含模组物品)——
    解析优先级:程序化 `registerItemThickness` > 配置映射 > 全局 thickness;
  - `solidColorItems`(强制纯色列表,支持通配符)—— 列表内物品的描边恒为纯色,
    即使光影模式开着混色;不开光影时所有描边本就恒纯色,本列表只在光影模式生效。
- **实现**:
  - Config: 两个新配置项 + 解析缓存(invalidateCache 登记)+ `itemThickness`/
    `isItemSolid` 查询;ColorResolver.thickness/armorThickness 接线配置映射 +
    `isSolidOutline(itemId)` 统一判定;OutlineColorRegistry.registerItemSolid
    (程序化 API 对齐);
  - 深层混色决策点(扁平 mix / 盔甲光影纹理选择 / 3D fallback 纯白判定)拿不到
    stack → 新增 `CURRENT_OUTLINED_ITEM` ThreadLocal(7 个 mixin 描边入口 set /
    finally clear,与 IN_GUI_RENDER 同款模式),决策点经
    `ColorResolver.isSolidOutline(currentOutlinedItem())` 查询。
- **配置界面**: 颜色分类新增 2 行(逐物品厚度 / 强制纯色),zh_cn/en_us 语言键同步
  (引用铁律:配置界面新增行必须同步语言键)。
- **验证**: `python tools/verify.py` 全量门禁 0 错误 0 告警(语言键 22 行 × 2 语言
  齐备);⚠️ 逐物品厚度/纯色生效需实机确认。

### 修复:动态物品描边随动画 —— BEWLR 捕获优先(通用算法)(2026-10-02,用户决策)

- **问题**: 所有动态模型(BEWLR/GeckoLib/AzureLib 骨骼,如 Hazen 星怒)的描边冻结
  不随本体动画;且本体动画本身会因画描边而停(用户实测:未附魔正常动、附魔后停)。
- **根因**: 动画状态机(GeckoLib/AzureLib `AnimationProcessor.tickAnimation`)在<b>本体
  renderByItem 内部</b>才执行并应用当帧姿态到骨骼;描边反射读骨骼发生在渲染注入点
  (HEAD),<b>早于</b>本体渲染 → 读到"未动画"的静止姿态 → 轮廓冻结。
- **方案(用户选定)**: **捕获优先** —— BEWLR 物品在 HEAD <b>先</b>走渲染捕获
  (`BewlrCapture.renderCaptured`,调 `renderByItem`),让动画状态机先跑、读到当帧动画
  几何 → 描边与本体同步;本体随后渲染按<b>同帧幂等</b>重放动画,不双倍推进。
  (注: 原选"描边后置"发现深度坑 —— 描边正面外扩后比本体更近,LEQUAL 测试通过会
  把半透明描边洗色盖在整个物品正面;捕获优先在 HEAD 画描边留本后面,无此坑。)
- **改动**: `OutlineRenderer.renderBewlrEntityOutline` 调度器重构 —— 任意 BEWLR 物品
  <b>一律先捕获</b>;捕获为空/守卫拒绝(垃圾几何)时回退反射/Geo 路径(旧行为)。
  - 扁平变体物品(长枪)不受影响: mixin 先 `inventoryModelFor` 走平面描边,
    **不进入**本调度器(铁律 6.5 保护);
  - TACZ 第一人称仍跳过(渲染器读相机/内部动画姿态,HEAD 必错位);
  - 灾变/永恒星光等"反射可命中"物品也改走捕获优先(形状由顶点法线外扩替代逐 cube/
    贴图 alpha,动画同步优先),捕获失败仍回退原反射路径;
  - 移除旧分支里的重复捕获兜底调用(顶部已统一尝试过,避免额外 renderByItem)。
- **验证**: `python tools/verify.py` 全量门禁;动画同步需实机确认(灾变/月弧/星怒等
  动态武器轮廓跟随摆动,本体动画不再停)。

### 变更:不开光影强制纯色描边(代码级,覆盖全部算法)(2026-10-02,用户决策)

- **变更内容**: 不开启光影(自定义 shader 路径)时<b>强制纯色</b>,无视混色配置;
  开启光影(兼容模式)时混色依据配置(itemPixelColorGlint / armorPixelColorGlint,
  默认 true 不变)。
- **改动点(2 处代码,无新路径)**:
  - `renderFlatOutlineShape`: mix 计算加 `needVanillaShaderFallback()` 门禁 ——
    不开光影恒 false → 形状纹理恒烘纯描边色(两种模式各自烘焙,ShapeKey 已含
    mix 维度,切换光影自动换纹理);开光影时 GUI 外混色依据配置(GUI 内恒纯色);
  - `armorOutlineRenderType` 自定义 shader 分支: `setOutlineBlend` 恒 0(原为按
    配置)→ 盔甲/鞘翅/投掷物/BEWLR 采样贴图路径不开光影恒纯描边色;
  - 3D 物品/Geo 路径: 恒纯色(原本就是,无改动);光影 fallback 的扁平/3D 分支
    本就按配置分流,无改动。
- **光影模式不变**: 兼容模式下混色依据配置(默认开,原有行为);盔甲"单纹理采样
  无法分离 alpha 与 RGB"固有混合不变(内置 emissive shader 的数学限制)。
- **验证**: `python tools/verify.py` 全量门禁;⚠️ 不开光影全路径纯色视觉需实机
  确认(GUI/手持/盔甲/Geo 色相应为纯描边色,不受贴图染色)。

### 优化:统合同行技术 —— 扁平 4 对角环(绘制量减半) + 外扩 ε 推挤(2026-10-02)

- **背景**: 按用户要求调研同行轮廓模组的兼容性处理(Enchantment Glint Outline,
  Modrinth 106 万下载,赛道头部,CC0 公有许可)—— GitHub 直连超时,改从 Modrinth
  CDN 下载 JAR 反编译读全源码,提炼可统合技术;完整行业情报见知识库「项目经验」。
- **同行架构对比**: 他们 = 管线劫持流派(重定向 vanilla 光效 buffer 到自定义层;
  1.21.4+ ItemRenderState 统一管线是版本红利),且**明示放弃自绘 BEWLR**("对其他
  模组的物品有效,前提是不自绘渲染");我们 = 捕获重放流派(v2 规范化捕获 +
  v1-bypass)—— **1.21.1 上无同行做 BEWLR 捕获,本项目超越行业标准**。他们的 Iris
  兼容史与本项目同样曲折(3.0 "没有 Iris 跑不起来"灾难版),本项目 emissive 回退
  方案更早更干净解决。
- **统合 ①:扁平 4 对角环**(`DIAG_OFFSETS` 替代旧 8 方向 `OFFSETS`,
  `renderFlatOutlineShape` + `emitCapturedFlat` 共用):对角拷贝(位移 ±t)任一轴
  范围与基向拷贝一致 → t ≤ 面片半径时基向 4 拷贝纯冗余 —— 删掉后<b>环范围不变</b>、
  <b>绘制量减半</b>(GUI 每物品每帧 8→4 拷贝)、边中点"对角+基向双重绘制(alpha
  叠加变浓)"变单次覆盖 → alpha 更均匀。极端 t > 面片半径(超大厚度×极小物品)
  有细缝,接受(非错位;标准扁平路径 t ≤ 0.25 恒安全)。
- **统合 ②:外扩 ε 推挤**(`NORMAL_EPSILON` = 1e-4 模型单位,`renderVertexNormalExpand`
  + 两个捕获发射器):外扩顶点额外沿法线推一点,描边面与本体表面**永不共面** ——
  防 z-fighting 边界情况(本架构描边恒先画/本体后画覆盖,此处为保险),零视觉影响。
- **未统合(记录)**: 逐物品覆盖(per-item 厚度/纯色,他们最受欢迎的功能)——用户
  决策暂不做,后续可议;Sodium/Embeddium 兼容 mixin(他们有专属 sodium mixin)——
  列入观察,出现 collectQuads 失效再议。
- **验证**: `gradlew compileJava` BUILD SUCCESSFUL;⚠️ 扁平描边环视觉与 ε 推挤需
  实机确认(GUI/手持扁平物品 + 开关光影)。

### 修复:TACZ 描边恢复 —— v1-bypass(旁路 + 真实 pose + 原样发射)(2026-09-26)

- **背景**: TACZ 兼容此前挂起 —— 专项复刻路径(1.1.8 实测"复刻之外的变换来源"仍错位)
  与 v2.1 旁路捕获(identity+重放,实测第三人称/掉落"巨大模型错位"、GUI 方形背景)
  双双失败;派发层 TODO 挂起 TACZ 一律无描边。
- **根因(v2.1 回归机理)**: 旁路捕获固定用 identity pose —— 对"读 pose"型渲染器
  (TACZ 渲染器读传入矩阵做内部运算)产出<b>假定空间垃圾</b>,再经 v2 重放 = 巨大
  错位;GUI 的"方形背景" = 平坦发射在贴图提取失败时退 WHITE → 白矩形环。
- **修复(v1-bypass,从未试过的组合)**:
  - `captureWithGlobalBufferSwap` 增加 poseMatrix 参数:旁路由 <b>v1 以真实 pose</b>
    使用 —— 渲染器完整渲染链从构造上与本体一致(解决"直写全局缓冲"),捕获 =
    本体<b>最终绘制坐标</b>(解决"读 pose"),v1 原样发射<b>零重放</b>(跳过 v2 重放层);
  - v2 的旁路回退移除(v2 保持纯 identity,回归源头);旁路唯一使用方 = v1 回退路径;
  - 平坦几何贴图提取失败时<b>不再退 WHITE</b>(宁可无描边):回退 v1,平坦几何发射
    不可见 → 最终无描边,消灭"方形背景";
  - 派发层 TODO 挂起块删除,TACZ 恢复走派发链(第一人称仍跳过);
  - `bewlrGlobalBufferSwap` 默认 false → true(新组合从构造上正确;swap-stats 节流
    日志常驻,错位可关回无描边)。
- **验证**: `python tools/verify.py`(编译+铁律+语言键门禁);⚠️ TACZ 第三人称/掉落/
  展示框贴合 + GUI(贴图提取失败时无描边,不再有方形背景)+ 其他 BEWLR 模组回归,
  实跑待进行。

### 开发工具链:VERIFY 自动化验证系统落地(2026-09-26)

- **背景**: 上文铁律此前只存在于文档里,违反与否靠人肉 review;铁律 8 引用的
  `tools/*.py` 验证工具在实际仓库中<b>一个都不存在</b>(tools 下只有一个一次性调试
  java 文件)。本次把"验证纪律"由文档约定变成<b>可执行系统</b>。
- **新增 `verify.bat` + `tools/verify.py`(提交前一键门禁,退出码可用于 pre-commit/CI)**:
  - <b>编译门禁</b>:`gradlew compileJava`,只认 BUILD SUCCESSFUL(规避 PowerShell/cmd
    退出码误报),失败时打印 `文件:行: 错误` 关键行;
  - <b>铁律静态扫描 9 项</b>:铁律 1(`NativeImage.read` 禁用;`ImageIO.read` 必须收在
    `readTextureFrame` 单一入口)、铁律 1.5(该入口必须裁序列图首帧)、铁律 2
    (`averageLuma` 返回值必须 ÷255 归一化)、铁律 6(`System.out/err` 残留、临时诊断
    文件)、铁律 6.7(mixin 描边入口必须 `finally` + `flushOutlineBatch`,能识别
    "入口委托同文件辅助方法"的间接形态,且入口判定不依赖 flush 是否存在)、
    <b>缓存登记</b>(outline 包内 cache 字段必须进 invalidate 清理链,含
    `lionfishOutline/geoOutline` 子模块委托校验,已知豁免项显式打印理由)、
    铁律 7(`markChanged` 必须 `Config.invalidateCache()`)、mixin 注册与目录双向一致、
    铁律 8(文档引用的工具必须真实存在);
  - <b>配置/语言键</b>:配置界面每一行的键与 tooltip 在 zh_cn/en_us 必须齐备、
    两语言键集完全一致、动态键(分类 / 取色模式)存在性;
  - <b>分级</b>:确凿架构违规 error(退出码 1,可阻塞提交);启发式提示 warn(不阻塞,
    避免噪音淹没真问题)。
- **新增 4 个专项工具**(兑现铁律 8 的引用,基于 Pillow):`check_png_format.py`
  (存储形态:索引色+tRNS 高危、序列图帧数)、`check_texture_alpha.py`(alpha 组成 →
  形状纹理可用性,支持 `--region` 定位部件 / `--compare` 修复前后对比)、
  `check_png_trns.py`(tRNS 专项,`--scan <目录>` 批量审计高危贴图)、
  `analyze_screenshot.py`(描边色像素密度 → 实心/轮廓,`--expect` 可作回归断言)。
- **新增 `tools/verify_selftest.py`(对检查本身的自检)**:向仓库临时注入已知违规 →
  断言每条检查真的能抓到 → 无条件还原并复查干净仓库 0 错误。防止检查逻辑重构后
  "静默失效"(永远通过的检查等于没有检查)。
- **验证记录**: 负向测试 11/11 通过(10 条静态规则 + 编译门禁各注入一次违规均被捕获);
  贴图工具用合成样本确定性验证(索引色+tRNS 判高危并退出码 1;64×320 序列图正确识别
  为 5 帧×64×64;全不透明贴图判"描边退化成矩形";实心方框填充率 100% → SOLID、
  轮廓环 10.4% → OUTLINE,`--expect` 相符/不符退出码正确);干净仓库门禁
  0 错误 1 告警,耗时 9.3 秒(增量编译)。
- **已清理(2026-09-26)**: 上述告警所指的 `tools/test_sprite_frame.java`(历史遗留
  一次性调试脚本,零引用)已按铁律 6 删除;同日复跑全量门禁 0 错误 0 告警、门禁
  自检 10/10 通过(见下方"清理"条目)。
- ⚠️ VERIFY 只做静态验证,**不替代实机跑游戏**:渲染视觉结论仍以实机为准
  (AGENTS.md「构建与测试」)。

### 修复:Hazen 序列图描边与本体动画同步(按活性分流采样,2026-09-26)

- **背景(用户实测)**: 骨骼缓存修复(每帧重新解析)后描边仍不随本体动画 —— 本轮
  实证"动画"实为<b>纹理序列动画</b>:`AzBoneAnimationUpdateUtil` 证实骨骼字段每帧
  被动画改写(骨骼层无罪),而描边采样的是 BUG1 修复烘焙的静态帧 0 → 本体每帧
  换帧、描边恒帧 0 → "轮廓没有同步"。
- **根因**: AzureLib 有活动画纹理体系 —— `AnimatableTexture`(物品/盔甲管线每帧
  `AnimatableTexture.setAndUpdate` 推进 GPU 帧内容)+ `TextureManagerMixin`(把
  vanilla 按需注册的 SimpleTexture 换成动画版)。上一版"序列图一律烘帧 0"过于
  一刀切,丢掉了活动画路径的实时同步能力。
- **修复(`animatedFrame0Texture` → `animatedSampleTexture`,按注册纹理活性分流)**:
  - 注册纹理是 AzureLib AnimatableTexture(活动画)→ <b>绑原 location</b> —— 描边
    采样当前帧,形状/颜色与本体动画<b>同步</b>;判定时的 getTexture 调用还会触发
    AzureLib mixin 建立动画纹理(顺带治愈本体注册链);
  - 普通 SimpleTexture(整张 sheet、无 mixin 推帧)→ 维持首帧烘焙(静态近似,
    形状正确;不绑原 location 防全帧并集巨块);
  - 非 mcmeta 纹理零开销原样;骨骼每帧重新解析(上一条修复)保留。
- **已知限制**: 混色关(armorPixelColorGlint=false)的 CPU 烘焙形状路径恒为帧 0
  静态(不随帧动画)—— 活动画实时同步仅 GPU 采样路径(emissive/自定义 shader)。
- **验证**: `verify.py --no-compile` 0 错误 0 告警;`compileJava --rerun-tasks`
  BUILD SUCCESSFUL;⚠️ 描边是否随本体动画需实机确认。

### 修复:Hazen/Geo 物品描边与本体动画脱节(骨骼缓存) + TACZ 兼容挂起 TODO(2026-09-26)

- **背景(用户实测+判断)**: Hazen 序列图武器轮廓对齐后不随本体动画;用户判断为
  缓存优化问题 —— 轮廓渲染时缓存了物品模型,之后缓存不刷新 → 轮廓与本体动画脱节。
- **根因(AzureLib 3.1.11 反编译实证)**: `AzItemRendererPipeline.preRender` 每帧把
  动画姿态/可见性/缩放应用到<b>渲染上下文</b>的烘焙模型(itemRenderTranslations /
  scaleModelForRender / leftArm·rightArm 强制隐藏 / preRenderEntry 动画状态机);
  `geoItemModelCache` 缓存解析时刻的 topLevelBones 引用 → 描边读陈旧/静止的旧实例
  → 轮廓与本体动画脱节。
- **修复(GeoOutline)**: `geoItemModel` <b>只缓存"种类"判定</b>(geoItemKindCache,
  便宜且与帧无关),骨骼列表/纹理<b>每帧重新解析</b>(成本 = 几次已缓存 Method
  invoke);`geoItemModelCache` 字段删除(invalidate 同步);cube 静态几何缓存
  (geoCubeCache,WeakHashMap)保留 —— 位置/UV/法线与帧无关,旧实例被 GC 后弱引用
  自动清除。
- **TACZ 挂起 TODO(用户决策)**: 派发层新增 TODO 块 —— 挂起期间 TACZ 一律无描边
  (宁可无描边,不错位;GUI 方形背景随之消失);恢复路径写在 TODO 注释里(开
  `bewlrGlobalBufferSwap` 采集 swap-stats 日志定位错位来源 → 修复后删除 TODO 块
  恢复派发链)。`enableTaczCompat` 配置保留(TODO 块前仍有读取,不是死配置)。
- **验证**: `python tools/verify.py --no-compile` 0 错误 0 告警;`compileJava
  --rerun-tasks` BUILD SUCCESSFUL;⚠️ 轮廓是否跟随本体动画需实机确认。

### 清理:删除历史遗留调试脚本 + VERIFY 门禁复验(2026-09-26)

- 删除 `tools/test_sprite_frame.java`(2026-08-30 遗留的一次性 API 探索草稿,零引用,
  铁律 6 告警源);
- `python tools/verify.py` 全量: <b>错误 0 / 告警 0</b>(编译 + 铁律 1/1.5/2/6/6.7/7/8、
  缓存登记、mixin 注册、语言键一致性 20 行 × 2 语言全齐);
- `python tools/verify_selftest.py`: <b>10/10 负向测试通过</b>(含本轮相关"铁律 1.5 未裁
  首帧"、"铁律 6.7 未闭合批次"注入均被捕获),还原后干净仓库 0 错误;
- 截图工具基线: 对 11.52.15 / 11.52.24 两张<b>修复前</b> bug 截图按描边色 FFC0CB 聚焦
  分析,粉色像素均呈线状(填充率 2.9% / 6.4%) —— 工具能判定"实心 vs 轮廓",但对
  "错位/散乱"类 bug 无判定能力(需实机人眼或新指标);
- ⚠️ 重申铁律 8:静态门禁只覆盖"不违反已知铁律 + 能编译",**不替代实机跑游戏**。

### 止血+诊断:v2.1 旁路捕获回归处置 —— 配置门禁 + 捕获统计/平坦发射诊断(2026-09-25)

- **背景(实测反馈)**: v2.1 旁路捕获在 TACZ 第三人称/掉落出现<b>巨大模型错位</b>(回归);
  GUI 方形背景问题依旧(贴图形状描边疑似未生效);Hazen 附魔后本体动画疑似停止、
  描边不同步。
- **止血**: 新增 `bewlrGlobalBufferSwap`(<b>默认 false</b>)—— 旁路捕获整体下线,
  TACZ 3D context 回到"无描边"(宁可无描边,不画错位巨壳);配置界面高级分类新增
  开关行,zh_cn/en_us 语言键同步。
- **诊断(常驻节流日志,info 级,10 秒/键)**:
  - <b>swap-stats</b>(旁路捕获几何统计): 开启旁路后输出捕获顶点数/AABB/中心距离
    —— 本地空间枪械正常约 ±2 单位、中心 <3;统计正常而描边错位 → 问题在重放/发射
    层;统计异常 → 捕获本身拿到垃圾几何;
  - <b>flat</b>(平坦几何贴图形状描边): 输出采样纹理与提取成败(extracted=false →
    GUI 方形背景的根因即 RenderType 纹理提取失败)。
- **加固**: RenderType 纹理提取链(state→textureState→texture,1.21.1 字段名已对照
  vanilla 源码逐一验证)新增兜底:稳定 mojmap 方法
  `EmptyTextureStateShard.cutoutTexture()`(TextureStateShard 覆写返回采样纹理)。
- **Hazen 动画(机制实证,方案下轮)**: 已确认 AzureLib 物品管线每帧调
  `AnimatableTexture.setAndUpdate` 推帧(volcano 等序列图的动画驱动源);"附魔后本体
  动画停止"的确切污染机制待实测区分;描边跟随动画(帧序列烘焙 + AzureLib 同款
  tick 公式轮换)排队下轮实现。
- **验证**: `gradlew compileJava --rerun-tasks` BUILD SUCCESSFUL;⚠️ 实跑验证清单见
  任务记录(swap-stats/flat 日志回收)。

### 重构+修复:TACZ 专项降级为纯捕获 —— v2.1 全局缓冲旁路捕获 + 贴图形状描边(2026-09-25)

- **背景(实测反馈)**: BUG2 修复(四层前置变换复刻)后 TACZ 第三人称/掉落/展示框
  描边仍"片面散乱不贴合";GUI 描边为方形背景(捕获到的 slot 贴片做纯几何外扩)。
- **根因(1.1.8 反编译二次实证)**: TACZ `BedrockModel.render` <b>不写传入的
  MultiBufferSource</b>,而是直接取 `Minecraft.renderBuffers().bufferSource()` 写
  全局共享缓冲并当场 endBatch —— ① 普通捕获拿不到 TACZ 几何;② renderBedrock
  复刻路径对 1.1.8 存在复刻之外的变换来源(逐层对照仍错位),复刻路线成本失控。
- **v2.1 方案(通用,复刻路径整条删除)**:
  - <b>全局缓冲旁路捕获</b>(`captureWithGlobalBufferSwap`):捕获期间把
    `RenderBuffers.bufferSource` 字段临时替换为 `CaptureBufferSource`(渲染线程
    单线程,同步换/finally 还原;窗口内其他取用只会拿到无害的捕获 consumer)——
    绕过传入 buffer 的渲染器(TACZ 型)把顶点写进捕获器,<b>完整渲染链(四层前置
    变换/动画/配件/LOD)从构造上与本体一致</b>,无需任何复刻;
  - <b>捕获 UV+纹理 + 贴图形状描边</b>:CaptureVertexConsumer 增记 UV,捕获时从
    渲染器请求的 RenderType 反射提取采样纹理(slot 贴图等);v2 对<b>平坦几何</b>
    (单薄一轴,GUI slot 贴片/扁平 2D 武器)改为<b>面内 8 方向偏移</b>(法线外扩
    对薄面片屏幕上不可见)+ 采样捕获纹理的 alpha 形状 → TACZ GUI 描边从"方形
    背景"变成<b>枪形轮廓</b>;立体几何仍走顶点法线外扩(WHITE 纯几何);
  - `TaczOutline.renderBedrock` 及全部复刻辅助(约 400 行)删除,类瘦身为纯
    命名空间判定;派发逻辑 TACZ 分支只留说明注释。
- **验证**: `gradlew compileJava --rerun-tasks` BUILD SUCCESSFUL;⚠️ 实跑验证待进行
  (TACZ 第三人称/掉落/展示框贴合 + GUI 枪形轮廓 + 其他 BEWLR 模组回归)。

### 修复:序列图(mcmeta 动画贴图)物品描边渲染整张序列图(BUG1,2026-09-25)

- **现象**: Hazen 序列图武器(火山拳 volcano 64×320=5 帧、terraprisma 128×2560=20 帧、
  soulcalling_scepter 128×896、galvanized_sheath 256×2560,均为 `.png.mcmeta` 逐帧
  动画贴图)描边变成"全部帧剪影的并集"巨块,且描边不跟随本体动画(静态轮廓)。
- **根因(设计缺陷,贴图尺寸扫描 + jar 实证)**: mcmeta 动画贴图的 geo 模型 UV 空间只有
  <b>单帧</b>(geo 声明 texture_height=64 而 PNG 高 320),本体渲染时 GPU 采样逐帧轮换的
  活纹理;而描边的三种采样模式全部采到<b>整张 sheet</b> —— CPU 烘焙形状路径直接 ImageIO
  读原始 PNG 文件,GPU 路径(emissive/自定义 shader)绑定的 TextureManager 注册对象也是
  整张 sheet(AzureLib AzItemRenderer 自管逐帧动画)。铁律 3.6 的"UV↔贴图 1:1 轮廓"
  假设对序列图不成立。
- **修复(统一 mcmeta 感知)**:
  - 新增 `readTextureFrame`(铁律 1 的 ImageIO 读取 + `cropAnimationFrame0`:解析
    `.png.mcmeta` animation 声明,显式 width/height 或缺省方帧,帧序取 frames[0],
    解析失败保守原样),CPU 端读取一律只取首帧;
  - `shapeTextureForLocation`(烘焙形状)与 `isFullyTransparent`(全透明检测)改走
    readTextureFrame;
  - GPU 两种模式经新增 `animatedFrame0Texture` 统一替换:序列图 → 本模组烘焙的
    <b>首帧动态纹理</b>(原始 RGB 保留,三模式形状一致;缓存随 invalidateCaches 清理),
    非序列图零开销原样返回;
  - 同源加固:扁平物品 atlas 精灵版 `shapeTextureFor` 对动画精灵
    (originalImage = 整张 sheet)裁 `contents` 尺寸首帧,防同类问题在扁平物品复发。
- **已知限制**: 首帧为静态近似,描边不随本体帧动画(帧间轮廓相近的武器视觉可接受)。

### 修复:TACZ 第三人称/掉落/展示框描边错位(BUG2,2026-09-25)

- **根因(1.1.8-hotfix-r6 反编译实证)**: TACZ `renderByItem` 非 GUI 分支在
  `gunModel.render` 前有<b>四层前置变换</b>,`TaczOutline.renderBedrock` 旧实现一个
  都没复刻 —— ①`translate(0.5,2.0,0.5)+scale(-1,-1,1)`(全局居中+翻转);
  ②`applyPositioningTransform`(按 context 的<b>定位节点链</b>反向变换,枪挂到手
  骨骼定位节点的机制);③`applyScaleTransform`(display 缩放);④<b>LOD 模型选择</b>
  (远距离本体画 LOD 低模,描边拿高模)→ 描边偏移+镜像+形状错位。
- **修复**: `renderBedrock` 完整复刻四层 —— 签名加 `ItemDisplayContext`(按 context
  选节点链与缩放);LOD 判定直接反射调用 `RenderDistance.inRenderHighPolyModelDistance(pose)`
  (与本体同一决策,GUI 时间戳/pose 平移量判定全兼容);定位节点链反向变换逐行对照
  字节码(旋转 XN/YN/ZN 弧度、根节点 Y 分量 `(1.5-y/16)×scale` 特例、null 缩放跳过)。
- **GUI 明确交还捕获兜底**: TACZ GUI 本体是 2D slot 贴图平面(renderSlotTexture),
  3D 骨骼壳套平面必错位 → renderBedrock 对 GUI 返回 false,由派发逻辑走捕获兜底
  (对 slot quad 外扩,矩形轮廓 —— 完整贴图轮廓属 v2.1"捕获 UV+纹理"候选改进)。
- **验证**: 两条修复均 `gradlew compileJava --rerun-tasks` BUILD SUCCESSFUL;⚠️
  游戏内实跑验证待进行(Hazen 序列图武器全 context + TACZ 第三人称/掉落/展示框,
  开/关光影)。

### 新功能:BEWLR 通用捕获算法 v2 —— 规范化捕获(bewlrCaptureV2,默认开启,2026-09-25)

- **背景**: v1 捕获(复制当前 pose 调 renderByItem)拿到的顶点是<b>最终绘制坐标</b>
  (手持 = 相机空间、GUI = 像素空间),守卫只能按 context 打补丁(仅手持查中心距离),
  外扩量 ∝ 物体尺寸 —— 任何新模组的相机依赖行为都可能钻空子。
- **v2 算法**(`BewlrCapture.renderCapturedV2`):
  1. 用<b>空 PoseStack(identity)</b>调 renderByItem 捕获 → 顶点 = 渲染器<b>本地
     模型空间</b>坐标(与相机/GUI 像素空间无关);
  2. 本地空间守卫<b>全 context 统一</b>(span>64 或 |中心|>16 拒绝,GUI 也适用)——
     "形状不大、位置很远"的相机空间垃圾在本地空间必然离原点极远,彻底堵死;
  3. 顶点法线平均外扩,外扩量与<b>兄弟路径像素语义一致</b>:GUI = thickness×
     THICKNESS_SCALE/16(对齐 renderGui3dInflate),世界 = thickness×THICKNESS_SCALE×
     BEWLR_3D_SCALE(对齐 Lionfish 逐 cube)—— 不再随物体尺寸变化;
  4. 展开顶点按<b>真实 pose 矩阵</b>重放(transformPosition,与 renderVertexNormalExpand
     同构)→ 位置与本体 100% 一致。
- **回退策略**: 渲染器强依赖真实 pose(TACZ 第一人称型)→ v2 捕获为空自动回退
  v1(今日行为);v2 守卫拒绝(本地空间垃圾)→ 直接跳过不重试 v1(垃圾在 v1 也是垃圾)。
- **配置**: 新增 `bewlrCaptureV2`(默认 true),配置界面高级分类新增开关行,
  `zh_cn`/`en_us` 语言键同步(引用铁律:配置界面新增行必须同步语言键);守卫拒绝
  日志带 `v2:` 前缀,与 v1 拒绝可在 latest.log 中区分。
- **验证**: `gradlew compileJava --rerun-tasks` BUILD SUCCESSFUL;⚠️ 游戏内实跑验证
  待进行(v2 on/off 对比:手持/GUI/掉落/展示框 + 开关光影 + TACZ/Hazen/无适配模组)。

### 重构:OutlineRenderer 巨石拆分 —— BEWLR 家族子模块化(2026-09-25,行为保持)

- **背景**: `OutlineRenderer.java` 单文件膨胀到 4655 行,BEWLR 家族(捕获/Lionfish/
  TACZ/Geo/静态字段解析)与渲染管线核心纠缠,每次改动回归风险高(历史上多轮
  "正方形/巨壳/错位"事故)。为"通用算法 v2"(规范化捕获升级为默认路径)铺地基。
- **拆分(纯行为保持:渲染语义零变化,mixin 文件零改动)**:
  - `outline/BewlrCapture.java`(新增)—— 通用渲染捕获兜底(CaptureSource/
    CaptureVertexConsumer/captureBewlrGeometry/resolveBewlrRenderer/renderCaptured
    + 双守卫 + 节流诊断日志)—— <b>通用算法 v2 的落地战场,后续只改此类</b>;
  - `outline/LionfishOutline.java`(新增)—— 灾变(LionfishAPI)逐 cube 顶点法线外扩
    + 整体壳回退 + 静态几何缓存;
  - `outline/TaczOutline.java`(新增)—— TACZ Bedrock 专项 + TACZ/Hazen 命名空间判定;
  - `outline/GeoOutline.java`(新增)—— GeckoLib/AzureLib Geo 物品与盔甲全套;
    OutlineRenderer 保留 mixin 所需 4 个公有门面(isAzureLibArmor 等)委托;
  - `outline/BewlrModelResolver.java`(新增)—— 静态字段模型解析(持有类表/灾变
    精确映射表/预变换类别,纯静态工具类);
  - `OutlineRenderer.java` 4655 → 约 2440 行:保留核心管线(shader/uniform/
    RenderType/描边缓冲)、BEWLR 派发入口、盔甲/扁平/3D 路径;共享成员
    (setOutlineAlphaBoost/armorOutlineRenderType/outlineBuffers/findField 等)放宽为
    包私有,子模块经构造注入 renderer 访问;`invalidateCaches` 改为调用各子模块
    `invalidate()`(资源重载/进出世界清理纪律不变)。
- **新模组 BEWLR 适配指引**(同步 AGENTS.md"BEWLR 子模块结构"):静态字段模型 →
  `BewlrModelResolver`;GeckoLib/AzureLib 骨骼 → `GeoOutline` 自动覆盖;
  自绘模型(TACZ 型)→ `BewlrCapture` 零适配。
- **验证**: `gradlew compileJava` BUILD SUCCESSFUL(每拆一个类即时编译验证,共 5 轮);
  ⚠️ 行为保持需游戏内实跑确认(手持灾变/永恒星光/Hazen/TACZ + 盔甲 + GUI,开/关
  光影各一轮),与拆分前逐项对比。

> 注:上一版 v0.1.12 中"TACZ 兼容已停用 / Hazen 描边整体停用 / 删除 enableTaczCompat"
> 的结论在本版被<b>部分扭转</b>:TACZ 与 Hazen'N Stuff 均以可开关的兼容模式恢复,
> 历史章节仍保留原措辞,以本版为准。

### 修复:3D 模组物品"头顶/远处巨大模型"(批次泄漏 + 捕获守卫只查大小)(2026-09-25)

- **背景**: 该症状历史上已"修复"多轮仍反复出现,本轮系统性根因分析(含全文件
  批次泄漏审计)确认两个独立根因,一并处理。
- **根因 1(批次泄漏)**: 描边各路径 `getBuffer` 写入顶点后,中途异常被顶层
  `catch (Throwable ignored)` 吞掉且无人 `endBatch`,半写脏批滞留共享 builder
  (`MultiBufferSource.immediate` 的 vanilla 语义:下次 getBuffer 到<b>不同</b>
  RenderType 时滞留批被<b>当场以当时的 GL 状态/坐标系画出</b>,同 RenderType 则
  续写合并)—— 手持局部空间顶点在世界渲染阶段上屏 = "头顶巨大模型"。确凿现场:
  `renderLionfishInflated` 反射调用模组自己的 render,异常被吞无 flush;三个 mixin
  顶层 catch 是把一切中途异常变成脏批的总闸门。
- **根因 2(守卫缺口)**: `CAPTURE_MAX_SPAN` 只查 AABB 跨度不查位置 —— 自绘 BEWLR
  把顶点抬到相机/世界空间时"形状不大、位置很远"(中心离视角原点数十/数百单位),
  守卫放行 → 描边壳画在远处。
- **修复**:
  - 新增 `OutlineRenderer.flushOutlineBatch()` 安全 flush;<b>全部 7 个 mixin 描边
    注入方法(8 处描边调用)</b> 统一 `finally` 强制 flush(空批 no-op,正常路径
    零开销零视觉影响),描边批次绝不跨渲染阶段滞留;原无 catch 的入口(盔甲原版
    路径/AzureLib/Elytra/三叉戟投掷物/ItemRenderer 8 参)顺带补 `catch (Throwable)`,
    描边异常不再上抛崩溃;
  - `renderLionfishInflated`: 反射方法查找/可访问化提前到 `getBuffer` 之前,invoke
    失败分支就地 `endBatch`(批次闭合铁律),并清理未使用的 `packedColor` 死变量;
  - 新增<b>手持 context 中心距离守卫</b>(`CAPTURE_MAX_CENTER_DIST`=16,仅第一/第三
    人称生效;GUI 像素空间与 GROUND/FIXED 可合法远离原点,不适用),与跨度守卫
    配合,双守卫分别拦"错位"与"巨大";
  - 新增 `logCaptureReject` 节流诊断日志(10 秒/原因#物品#context,info 级,常驻
    非临时调试):守卫拒绝时打印物品 ID / context / 顶点数 / 跨度 / 中心距离 ——
    用户报障直接附 `latest.log` 即可定位问题模组与触发场景。
- **配置对齐**: `enableTaczCompat` 代码默认值 `false` → `true`(AGENTS.md 与上一条
  changelog 均记载"默认开启",属代码漂移;已有配置文件存储值优先不受影响,新装
  默认开启)。
- **文档**: AGENTS.md 新增铁律 6.7(描边批次必须在所有退出路径闭合)/ 6.8(捕获
  守卫必须校验位置 + 拒绝留痕);修正 BEWLR_3D_SCALE 默认值记载 0.3 → 0.1(以代码
  为准)。
- **验证**: `gradlew compileJava` BUILD SUCCESSFUL;⚠️ 游戏内实跑验证待进行(手持
  3D 模组武器 + 开/关光影 + 掉落物/展示框/GUI 全 context)。

### 新功能:Hazen'N Stuff 全武器兼容(enableHazenCompat,默认开启)

- **背景**: 上版将整个 `hazennstuff:` 命名空间从 BEWLR/Geo 描边中一刀切排除(星怒等
  GeckoLib GeoItem 的 2D 贴片在几何放大壳下会膨胀成整块平面)。本版改为提供
  <b>全武器兼容模式</b>,并解决形状问题。
- **渲染策略**:`renderBewlrEntityOutline` 解除对 Hazen 命名空间的整体拦截,新增
  Hazen 专用分支 —— 所有 context<b>先走 Geo 贴图 alpha 采样轮廓</b>(形状由本体贴图
  决定,扁平 quad 不再膨胀成整块平面),<b>失败再回退渲染捕获兜底</b>(任意 BEWLR/
  自绘模型)→ 覆盖 Hazen 全部武器模型类型(GeckoLib/AzureLib/普通 BEWLR/自绘)。
  扁平物品(非 customRenderer)与 AzureLib 盔甲(独立 HumanoidArmorLayer 路径)不受影响。
- **配置**:新增 `enableHazenCompat`(默认 true),关闭即回归旧"一律不描边";
  配置界面高级分类新增开关行,`zh_cn` / `en_us` 同步语言键(键 `hazenCompat`,含
  tooltip)。引用铁律:配置界面新增行必须同步语言键。

### 新功能:TACZ 枪械兼容恢复(enableTaczCompat,默认开启)

- 恢复 `enableTaczCompat`:解除对 `tacz:` / `thearsenalcollection:` 的一刀切排除,
  改由开关控制。TACZ 枪械为自绘 Bedrock BEWLR(`GunItemRendererWrapper`),几何不在
  BakedModel——失去完整 BEWLR 路径后统一走 `renderBewlrCapturedOutline`(`renderByItem`
  捕获顶点 → 绕捕获几何外扩壳)。若某姿态/版本仍错位可关闭该开关回到无描边。

### 修复:Config 并发编辑残留的字段重复定义

- 并发重写导致 `ENABLE_HAZEN_COMPAT` 在 `Config.java` 中重复声明(两处相同定义),
  必造成编译失败。已删除冗余的重复声明,仅保留渲染器实际引用的一份,并确认
  `ENABLE_TACZ_COMPAT` 与 `hazenCompat`/`taczCompat` 语言键齐全。

### 验证

- `gradlew compileJava --rerun-tasks` 强制重编译 `BUILD SUCCESSFUL`(非缓存);
- 无人值守启动客户端,`Enchanted Outlines 0.1.13` 与 Hazen'N Stuff / TACZ / 孢 /
  Iris 等模组共存下干净进入单机世界,无来自本版改动的报错;新配置键(含
  `enableTaczCompat`/`enableHazenCompat`)自动写入配置。
- （注:具体武器外观的最终视觉轮廓仍需交互式游玩验证。）

### 影响文件

- `outline/OutlineRenderer.java`、`config/Config.java`、`config/EnchantedConfigScreen.java`、
  `assets/.../lang/zh_cn.json`、`en_us.json`、`gradle.properties`、`UNIVERSAL_MODEL_ADAPTER_PLAN.md`、`Changelog.md`

---


### 重构:删除零引用的 api/ 适配层(20+ 文件)

- **背景**: `com.enchantedoutlines.mod.api`(adapter/algo/color/core/pipeline 共 15 Java
  + ApiArchitecture.md)自 v0.1.9 起从未被接线——全项目无任何 mixin/渲染代码引用该包,
  渲染链路全部直连 `outline.OutlineRenderer` + `outline.ColorResolver`。经引用图全面核查
  确认外部零引用后整包删除(含两份同名 `ItemModelRegistry`、与活跃版重复的
  `api.color.ColorResolver`、无实现类的 `OutlineShapeTextureProvider`、无真实调用方的
  门面 `OutlineRenderingApi` 等)。
- **文档同步**: `API_README.md` 整篇重写,只保留真实对外扩展点
  (`outline.OutlineColorRegistry` + `outline.OutlineColorEvent`,含 API 说明/颜色优先级/
  兼容矩阵);`UNIVERSAL_MODEL_ADAPTER_PLAN.md` 顶部标注状态(捕获已落地、api 层已删、
  TACZ/Hazen 适配已停用,文中相关章节仅作历史参考)。
- **影响文件**: 删除 `api/` 整包;`API_README.md`;`UNIVERSAL_MODEL_ADAPTER_PLAN.md`

### 重构:移除无实际效果的死配置 ×3(含 UI 行与语言键)

- `enableTaczCompat` / `enableDataPackColors` / `optimizeConfigUi` 三个开关只存在于
  定义 + 配置界面 UI 行,业务代码<b>零读取</b>(TACZ 兼容已停用、数据包颜色资源
  `pack.json` 根本不存在、"优化界面"恒为分类布局)——删除 Config 常量、ConfigScreen 行、
  两语言文件 7 个键;另清理未引用的 `config.cancel` 语言键。
- **影响文件**: `config/Config.java`、`config/EnchantedConfigScreen.java`、
  `assets/.../lang/zh_cn.json`、`en_us.json`

### 修复:BEWLR 字段名映射 key 回归(灾变/永恒星光 BEWLR 3D 描边整体失效)

- **根因**: `findBewlrModel` 用 `BuiltInRegistries.ITEM.getKey(...).toString()`(带命名
  空间,如 `cataclysm:coral_spear`)作为查找键,而 `CATACLYSM_WEAPON_SPECS` 的 key 是
  <b>纯路径</b>(`coral_spear`)→ 精确映射恒 miss;通用兜底字段名把带命名空间的字符串
  大写 → 含冒号 `CATACLYSM:CORAL_SPEAR_MODEL`,Java 字段名不可能含 `:`,兜底也必 miss
  → 灾变精确适配与永恒星光通用适配全部失效。git 历史定位:此回归由 c108d19 把
  `getPath()` 改成 `toString()` 引入。
- **修复**: `findBewlrModel` 改回 `getPath()`(纯路径),映射表与兜底重新命中;
  命名空间级排除(TACZ/Hazen)仍在 `renderBewlrEntityOutline` 顶部完成,不受影响。
- **影响文件**: `outline/OutlineRenderer.java`

### 重构:清理失效的模组适配表与死分支(OutlineRenderer)

- 删除 `HAZEN_WEAPON_FIELD_MAP`(key 命名空间 `hazenn:` 与真实 `hazennstuff` 不符,
  恒 miss;且 Hazen Geo 描边已整体停用)与 `SPORE_WEAPON_FIELD_MAP`(持有类
  `SItemProperties` 早已从 `BEWLR_MODEL_HOLDER_CLASSES` 移除,javap 证实无静态模型
  字段,反射必然失败)。`readBewlrModelField` 同步移除 Spore/Hazen 分支;
  `holderPreTransform` 移除不可达的 Hazen/Spore 前缀分支。
- 从 `BEWLR_MODEL_HOLDER_CLASSES` 移除已停用的 Hazen 持有类
  (`HSNEnchantedItemStackRenderer`,整命名空间被拦截后永远不会被查询)。
- 清理一次性诊断残留:`CAPTURE_DIAG`/`FLAT_SHAPE_DIAG`/`HAZEN_DIAG` 字段与全部
  `[EO-diag]` 日志(AGENTS.md #6);死字段 `AnimatedShapeKey`/`animatedShapeTextures`;
  死重载 5 参 `renderHandOutline`;死方法 `CaptureSource.endBatch` /
  `CaptureVertexConsumer.finish`;`renderBewlrCapturedOutline` 不可达的 `quads==0`
  分支。
- **影响文件**: `outline/OutlineRenderer.java`、`mixin/GuiGraphicsMixin.java`

### 修复:通用渲染捕获的图元模式假设(CaptureSource)

- 捕获流按 `renderType.mode()` 校验,只接受 QUADS(每 4 顶点一个 quad);检测到
  TRIANGLES 等非 QUADS 的 RenderType 时整体放弃捕获(返回 null → 回退 Geo/跳过),
  杜绝"按 12 float 一组重建 quad 导致三角形流畸形几何"。宁可无描边,不错位渲染
  (与 TACZ/Hazen 停用同原则)。
- **影响文件**: `outline/OutlineRenderer.java`

### 修复:颜色解析逻辑(ColorResolver)

- **RAINBOW_CACHE 写而不读**:旧代码把"最后计算时间"写入缓存但从不读取,100ms
  节流是空操作、HSBtoRGB 每帧照算、缓存永不清理(invalidateCache 无调用方)。
  彩虹本就该逐帧刷新,删除无效缓存与空壳方法,保留连续颜色。
- **disableItem 被 RPG 虹灯穿透**:程序化 `OutlineColorRegistry.disableItem` /
  配置 `disabledItems` 的禁用检查提前到彩虹分支之前——"禁用该物品描边(即使带附魔)"
  的 API 承诺现在对开启彩虹同样生效。
- 提取 `fixedItemColor`(程序化 > 配置的物品固定色)消除 resolve/resolveFoilOnly
  的重复块。
- **影响文件**: `outline/ColorResolver.java`

### 修复:健壮性小项

- `mixin/HumanoidArmorLayerMixin`:GeckoLib 槽位遍历分支补上与 12 参分支同款的
  `armorItem.getEquipmentSlot() == slot` 守卫(错槽时不按错误槽位画壳)。
- `mixin/GuiGraphicsMixin`:GUI 描边主体包 try-catch(描边异常不再打断本体渲染,
  且保证走 RETURN 恢复 `IN_GUI_RENDER` 标记,不残留"GUI 态"污染后续世界渲染);
  顺带把主体拆成 private static 方法。
- `OutlineRenderer.lionfishBounds`:cubeList/childModels 反射改沿父类链 `findField`
  (与 renderLionfishPart 一致),修复 BasicModelPart 子类(AdvancedModelBox)上
  getDeclaredField 抛 NoSuchFieldException → 整体壳回退路径子树静默跳过的问题。
- `OutlineRenderer.invalidateCaches`:补清 `lionfishCubeCache`(与 geoCubeCache 一致)。
- **影响文件**: `mixin/HumanoidArmorLayerMixin.java`、`mixin/GuiGraphicsMixin.java`、
  `outline/OutlineRenderer.java`

### 其他

- 移除不再使用的 `EnchantedOutlines` import(OutlineRenderer,诊断日志删除后)。
- 新增 `tmp/check_lang_keys.py`(一次性语言键校验脚本,用后已删)。

### 确认:Hazen 描边停用范围以代码为准(整命名空间 `hazennstuff`)

- v0.1.11 第六轮 Changelog 记为仅停用星怒(`isHazenStarfury`);实际后续代码演进为
  `isHazenNamespace`(整个 `hazennstuff` 命名空间的 Geo/BEWLR 物品,星怒/黑曜石巨剑/
  永夜刃等 GeckoLib GeoItem 同机制都会把 2D 贴片膨胀成整块平面)。本次确认以代码为准:
  `renderBewlrEntityOutline` 顶部 `isTaczNamespace(stack) || isHazenNamespace(stack)`
  → 直接 return false,两家全 context 不再进入 findBewlrModel / Geo / 捕获任何分支。
  Hazen 的普通扁平物品(非 customRenderer)与 AzureLib 盔甲不受影响。

---

### 移除:TACZ 与 Hazen 星怒的描边兼容(2026-09-06 第六轮,用户决策)

- **决策背景**: 通用渲染捕获虽能出轮廓,但 TACZ 自绘 Bedrock 渲染强依赖相机/玩家动画上下文 → 无上下文捕获几何错位持续存在;Hazen 星怒(`hazennstuff:starfury`,GeckoLib GeoItem)的 Geo/捕获路径对 GUI 产生<b>不透明错误轮廓</b>。用户明确要求移除这两家兼容,宁可无描边也不要错位/错误轮廓。
- **修复**:
  - `renderBewlrEntityOutline` 顶部新增拦截:`isTaczNamespace(stack) || isHazenStarfury(stack)` → 直接 return false。TACZ 全 context(含 GUI)、星怒全 context 不再进入 findBewlrModel / Geo / 捕获任何分支,彻底回到无描边。
  - 新增 `isHazenStarfury`:注册 ID 为 `hazennstuff:starfury`(含路径包含 starfury)。
  - 撤销 `inventoryModelFor` 的 `_gui` 变体候选(2026-09-06 四轮误加):Hazen 星怒有 `starfury_gui` 平面模型文件,但 GUI 本体实为 BEWLR 3D Geo(实测 customRenderer=true),平面描边套 3D 本体会错位 —— 该候选反而让星怒命中错误平面。
- **影响文件**: `OutlineRenderer.java`

### 实现:通用 BEWLR 模型获取算法"渲染捕获"正式接入(2026-09-06 第四轮)

- **背景**: 前三轮只在文档层修正了 `UNIVERSAL_MODEL_ADAPTER_PLAN.md`,渲染链路从未真正调用捕获 → 用户反馈"通用性算法没有起到效果"(正确:确实没接入)。本轮把捕获器与兜底完整实现并接入。
- **通用渲染捕获核心**(OutlineRenderer):
  - `resolveBewlrRenderer(stack)`:按 MC 两条标准注册通路拿 BEWLR 渲染器,不反射模组内部字段——① `IClientItemExtensions.of(stack).getCustomRenderer()`(TACZ GunItemRendererWrapper / 灾变 CMItemstackRenderer 等);② GeckoLib `GeoRenderProvider.of(item).getGeoItemRenderer()`(Hazen 星怒 StarfuryItem implements GeoItem;已 javap 证实 GeoItemRenderer extends BlockEntityWithoutLevelRenderer)。
  - `captureBewlrGeometry`:复制当前描边 pose,调 `renderByItem`,用 `CaptureSource`/`CaptureVertexConsumer` 拦截 addVertex 顶点流(只收 xyz,不真正 endBatch/上屏)。
  - `renderBewlrCapturedOutline`:捕获顶点已是**最终绘制坐标**(含本体 display/预变换/动画姿态)→ 绕全局 AABB 中心放大壳画描边(scale=1+thickness×0.5×BEWLR_3D_SCALE),本体随后覆盖中心。
  - `renderBewlrEntityOutline` 兜底顺序重构:GUI/FIXED/GROUND 下本体就是 `renderByItem` 按该 context 画的 → **捕获优先**(同姿态最贴合),失败再回退 Geo 骨骼;手持先 Geo 快路径、失败捕获。消除旧 GUI 下 Geo 骨骼路径套用手持 `translate(0.5,0.51,0.5)` 导致错位出图标的问题。
  - 调用点传真实 `ItemDisplayContext`(GuiGraphicsMixin=GUI / ItemRendererMixin=context / ItemInHandRendererMixin=context)。
- **诊断日志**(临时,修复后清理):一次性(按物品)打印捕获成功顶点数 / 无几何原因 / renderByItem 异常栈 / 渲染器类名。本次实机(04:24)已确认 TACZ FIXED `ext=AbstractGunItem$1 customRenderer=GunItemRendererWrapper` 捕获 0 顶点(展示框无 player 上下文,渲染器不绘制),证明链路已通;GUI 星怒走 `customRenderer=true + inventoryModel=null`(已确认本体是 BEWLR 3D 非平面,尽管 jar 有 `starfury_gui` 平面文件但未在 GUI 使用)。
- **影响文件**: `OutlineRenderer.java` + 三个 BEWLR 调用点 mixin

### 修复:星怒 GUI 捕获崩溃 "Duplicate delegates" + TACZ 错位渲染(2026-09-06 第五轮)

- **问题**: 星怒(`hazennstuff:starfury`)GUI 捕获抛 `java.lang.IllegalArgumentException: Duplicate delegates`;TACZ 出现错位渲染。
- **Duplicate delegates 根因**: `CaptureSource.getBuffer()` 对所有 RenderType 返回**同一个** `CaptureVertexConsumer` 实例,而真实 `BufferSource` 语义是每 RenderType 独立 consumer。星怒的 GeckoLib 渲染内部用 `VertexMultiConsumer` 组合主渲染 buffer + 附魔层 buffer,两个都是同一实例 → 组合器检测到重复 delegate 抛异常。
- **修复**: `CaptureSource` 改为 `IdentityHashMap<RenderType, CaptureVertexConsumer>`——每个 RenderType 独立实例(同一 RT 复用),`finish()` 汇总各 consumer 顶点。渲染器把同一条顶点写入主+附魔两个 buffer 会重复计数,但描边只做 AABB/壳,重复顶点放大后重叠无视觉影响。
- **TACZ 错位渲染根因与修复**: TACZ 手持/展示框本体由其第一人称专用渲染器(带相机/玩家动画上下文)绘制,脱离上下文调 `renderByItem` 捕获的几何必然与本体错位(此前用户已明确"错位=bug,宁可无描边")。修复:`renderBewlrEntityOutline` 对 TACZ 命名空间**仅 GUI 允许捕获**(本体=2D slot 平面确定性几何),手持/FIXED/GROUND 直接跳过(无描边,但绝不错位)。
- **影响文件**: `OutlineRenderer.java`

### 修复:开启光影后背包/容器 UI 里非方块物品描边显示淡灰色(方块正常)

- **问题**: 开启光影(Iris)后,背包/容器/快捷栏 UI 中所有**非方块物品**(剑/工具等扁平物品,以及 TACZ、真菌感染等模组的扁平/Geo/BEWLR GUI 图标)的描边颜色变成不正确的淡灰色;方块(GUI 3D)正常。反复修 TACZ/Spore 无效。
- **根因**: `GuiGraphics`(背包等)是 **2D 屏幕空间渲染,不经 Iris 光影**,自定义 outline shader 在 GUI 中始终可用。但 GUI 扁平物品描边路径 `OutlineRenderer.renderFlatOutlineShape` 内部**无条件调用 `needVanillaShaderFallback()`**——开光影时错误地把 RenderType 切到 `worldEmissiveOutlineRenderType`(带 LIGHTMAP/OVERLAY 状态、为世界渲染设计的内置 emissive),在 GUI 屏幕空间渲染出淡灰。方块 GUI 3D 走 `handOutlineRenderType()`(恒自定义 shader,不查 fallback),所以"物品灰、方块正常"。该问题与具体模组无关,任何非方块物品都中招,因此针对 TACZ/Spore 的适配修复自然"反复无效"。
- **修复**: 统一用 **GUI 渲染线程标志**区分渲染上下文:
  - `OutlineRenderer` 新增 `IN_GUI_RENDER` ThreadLocal 与 `markGuiRender()` / `isInGuiRender()`(放普通类,mixin 只留 private 方法,遵守 2026-08-13 的 mixin 工具方法铁律);
  - `needVanillaShaderFallback()` 在 GUI 渲染期间恒返回 `false`(自定义 shader),**且不更新** 500ms 光影状态缓存(避免污染随后世界渲染的判断);
  - `GuiGraphicsMixin` 在 `GuiGraphics.renderItem`(7 参,所有公开 renderItem 重载的唯一漏斗)HEAD 设 true、RETURN 恢复 false——背包/容器/快捷栏全部 GUI 图标渲染都被覆盖,扁平/Geo/BEWLR 各路径(renderFlatOutlineShape、armorOutlineRenderType 等)不再错误 fallback。
  - 世界渲染路径(手持/掉落物/展示框/盔甲/鞘翅/投掷物)不设 GUI 标记,光影 fallback 逻辑完全保留,行为不变。
- **影响文件**: `OutlineRenderer.java`、`mixin/GuiGraphicsMixin.java`

### 修复:澄清 TACZ/真菌感染"反复修无效"的逻辑根因(文档与代码现状)

- 经 jar 反编译核实(2026-09-06):
  - v0.1.9 起的整个 `src/main/java/com/enchantedoutlines/mod/api/` 适配层(`ItemModelRegistry`/`TaczAdapter`/`RenderPipeline`/`ShapeDetector` 等 20+ 文件)是**未接线的死代码**——所有渲染 mixin 直连 `OutlineRenderer.INSTANCE`,没有任何类引用 `com.enchantedoutlines.mod.api`。此前在 api 层对 TACZ/Spore 的修复从未进入渲染链路。
  - `SPORE_WEAPON_FIELD_MAP` 假设 `com.Harbinger.Spore.Client.SItemProperties` 持有 `SporeSword_MODEL` 等静态模型字段,javap 证实该类只有 `makeBow/makeSickle/makeShield` 等 ItemProperties 注册方法、**无任何模型字段** → 反射必然失败。
  - 本次 GUI 淡灰修复已覆盖"非方块物品开光影变灰"的全部表现(含 TACZ/Spore 物品图标);TACZ 枪(自研 Bedrock simplebedrockmodel 渲染)与 Spore 枪(Blockbench elements + 仅 arm pose 的 IClientItemExtensions)的**独立手持/世界 3D 适配**留待后续按真实渲染结构设计,不再依赖失效的 api 层与字段映射。

### 修复:TACZ 手持/世界"头顶巨大额外模型"(2026-09-06 第二轮)

- **问题**: TACZ 枪在手持/世界渲染时,本体正常但在模型头顶出现**巨大额外模型**;同时 TACZ 枪在 GUI 无描边。
- **根因**: TACZ 枪(注册 ID 如 `tacz:modern_kinetic_gun`)是**自绘 BEWLR**(渲染器 `GunItemRendererWrapper extends AnimateGeoItemRenderer`,simplebedrockmodel 的 Bedrock 骨骼;GUI 本体=2D slot 纹理平面,手持本体=3D Bedrock 模型)——既无静态模型字段,也不属于 GeckoLib/AzureLib/Lionfish 骨骼体系。描边对 builtin/entity 物品走到 `renderBewlrEntityOutline` → 反射拿不到模型 → 继续 `renderGeoItemOutline` 把无关对象当 Geo 模型放大 → 头顶巨大错乱模型。
- **修复**: `renderBewlrEntityOutline` 顶部对 TACZ 命名空间(`tacz:`/`thearsenalcollection:`)直接返回 false——不再产生任何错误放大几何(巨大模型消失);GUI 下该分支同样不再画错乱放大壳。同时修正 `readBewlrModelField` 前缀判断 bug(旧 `:tac/` 从不匹配 `tacz:...`),从 `BEWLR_MODEL_HOLDER_CLASSES` 移除无模型字段的 Spore `SItemProperties`(javap 证实),杜绝无效反射与误导性适配表。
- **遗留**: TACZ 枪在 GUI 的 2D 槽位描边(本体=slot 纹理平面)需要按真实 GUI 几何接入(见注释 `renderTaczGuiOutline` 预留),手持 3D Bedrock 描边待专项;两者都需要游戏内截图确认几何,不贸然错位渲染。
- **影响文件**: `OutlineRenderer.java`

### 修复:真菌感染(Spore)盔甲"无描边"(2026-09-06 第二轮)

- **问题**: 玩家穿 Spore 盔甲(感染胸甲/头盔/护腿/靴等)时完全没有描边。
- **根因**: 玩家身上的 Spore 盔甲<b>本体不是由原版 HumanoidArmorLayer 绘制</b>:Spore 通过 `EntityRenderersEvent.AddLayers` 向玩家渲染器注册自研 `CustomArmorLayer`(ArmorModelList.ARMOR_RENDERING_BITS 部件渲染),而原版 ArmorLayer 用的 `SporeBaseArmor.getArmorTexture` 恒返回 `spore:textures/entity/empty.png`(全透明 32×32)。我们的盔甲描边 hook 在原版 layer 采样该纹理 alpha → 描边壳整体透明 → 不可见。
- **修复**: `OutlineRenderer.armorOutlineRenderType` 增加<b>全透明纹理检测</b>(`isFullyTransparent`,ImageIO 读 PNG 统计 alpha,结果按纹理缓存并随 `invalidateCaches` 清理):采样源全透明时回退 `WHITE_TEXTURE`——外层几何(renderPartInflated 逐 cube 放大壳)不受影响,描边变为<b>纯几何实心壳</b>绕本体可见,不再被透明纹理吞掉。
- **影响文件**: `OutlineRenderer.java`

### 设计:通用模型适配算法"渲染捕获"修正(2026-09-06 第三轮)

- **背景**: 你同步的 `UNIVERSAL_MODEL_ADAPTER_PLAN.md` + `OutlineRenderer.getUniversalBewlrModel()`(stub)方向正确,但文档 §3.2.1 用 `ItemRenderer.getItemModelShaper().getModel(stack)` 取模型——对 **BEWLR 物品(builtin/entity)无效**:占位 BakedModel 无 quads,几何只存在于模组渲染器 `renderByItem` 画出的顶点里。这解释了为何"每个模组最终都被 MC 渲染"却始终取不到几何。
- **实证(javap)**: TACZ 枪 `GunItemRendererWrapper extends AnimateGeoItemRenderer`(自绘 Bedrock);Hazen 星怒 = `hazennstuff:starfury`(StarfuryItem implements GeckoLib **GeoItem**,StarfuryRenderer extends GeoItemRenderer,GUI 另有 `starfury_gui` 平面),与代码中 `HAZEN_WEAPON_FIELD_MAP` 假设的 `hazenn:wrath`→`WRATH_MODEL` 静态字段**完全不符**;Spore 盔甲本体由自研 `CustomArmorLayer` 部件渲染。
- **文档修正**: `UNIVERSAL_MODEL_ADAPTER_PLAN.md` 更新为 **渲染捕获(Render Capture)** 设计:调用模组自己的 `renderByItem` 并把 `MultiBufferSource` 换成捕获器,拦截 addVertex 顶点流还原 quads → 复用现有法线外扩/AABB 壳画描边。附兼容矩阵(TACZ 手持 3D/Hazen Geo/灾变 Lionfish/永恒星光),列出风险与缓存纪律。
- **遗留(待实测)**:
  - TACZ: 手持 3D Bedrock 需按捕获几何接入(取消命名空间一刀切 return false);GUI 2D slot 需按 `renderSlotTexture` 几何或 slot 纹理形状路径接入;
  - Hazen: 星怒等 GeckoLib GeoItem 走 `renderGeoItemOutline` 快路径应命中,若仍失败需游戏内排查 GeoRenderProvider 初始化时序,再以捕获兜底;
  - Spore: 盔甲描边需 hook 自研 `CustomArmorLayer`(部件渲染)才能贴合本体;武器以扁平/Blockbench 3D 为主,按真实几何排查。
- **影响文件**: `UNIVERSAL_MODEL_ADAPTER_PLAN.md`(设计修正)

---


### 修复：TACZ 轮廓错位和巨大模型

- **问题**: TACZ 物品轮廓错位，空中显示巨大模型。
- **原因**: TACZ 使用 GeoLib 渲染而非 BEWLR，但 `ItemModelRegistry` 将其标记为 BEWLR → 描边进入 BEWLR 路径后无法正确 fallback → 几何错位。
- **修复**: 在 `ItemModelRegistry` 中为 TACZ 物品显式设置 `isBewlr=false`，强制走 Geo 路径。

### 修复：真菌感染 (Spore) 武器和盔甲无法渲染轮廓

- **问题**: Spore 模组的所有武器和盔甲描边完全缺失。
- **原因**: Spore 的模型字段名不遵循通用约定 (`<ID>_MODEL`)，`readBewlrModelField` 无法通过反射找到模型。
- **修复**: 在 `OutlineRenderer` 中添加 `SPORE_WEAPON_FIELD_MAP` 映射表，显式映射 Spore 武器字段名 (如 `spore:spore_sword` → `SporeSword_MODEL`)，并在 `readBewlrModelField` 中检查该映射表。

### 修复：Hazen'N Stuff 星怒武器无法渲染轮廓

- **问题**: Hazen'N Stuff 的"星怒" (`hazenn:wrath` / `hazenn:wrath_weapon`) 描边缺失。
- **原因**: Hazen 武器字段名不遵循通用约定，需要精确映射。
- **修复**: HazenAdapter 和 OutlineRenderer 的 `HAZEN_WEAPON_FIELD_MAP` 已包含星怒武器的精确映射 (`hazenn:wrath` / `hazenn:wrath_weapon` → `WRATH_MODEL`)，修复完成。

### 影响文件

- `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java` (TACZ isBewlr=false)
- `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (SPORE_WEAPON_FIELD_MAP)
- `src/main/java/com/enchantedoutlines/mod/api/adapter/HazenAdapter.java` (已包含星怒映射)

---


### 新增：结构化 API 库架构

- **背景**: 原 `OutlineRenderer` 类达 3700+ 行，包含所有渲染逻辑、适配器、算法，难以维护和扩展。为支持外部模组集成和持续开发，将渲染逻辑收编为结构化的 API 库。
- **新模块结构**:
  ```
  src/main/java/com/enchantedoutlines/mod/api/
  ├── core/                    # 核心层
  │   ├── OutlineRenderingApi  # 主 API 接口 (统一入口)
  │   ├── ItemTypeInfo         # 物品类型信息 (BEWLR/Geo/Lionfish/...)
  │   └── ItemModelRegistry    # 类型注册与判断缓存 (首次判断，结果复用)
  ├── algo/                    # 算法层
  │   ├── ShapeResult          # 形状检测结果 (扁平/3D/算法类型)
  │   ├── ShapeDetector        # 形状检测器 (按 BakedModel 缓存)
  │   └── OutlineShapeTextureProvider  # 形状纹理提供者接口
  ├── color/                   # 颜色处理层
  │   └── ColorResolver        # 颜色解析器 (RPG 虹灯→数据包→配置)
  ├── pipeline/                # 渲染管线层
  │   └── RenderPipeline       # 渲染调度器 (协调各适配器)
  └── adapter/                 # 适配器层
      ├── ItemModelAdapter     # 适配器接口 (统一方法签名)
      ├── BewlrAdapter         # BEWLR 物品适配器 (永恒星光/灾变)
      ├── GeoAdapter           # GeoLib/AzureLib 适配器
      ├── LionfishAdapter      # LionfishAPI 适配器 (灾变武器)
      └── TaczAdapter          # TACZ 专项适配器
  ```
- **判断缓存机制**: 物品类型判断 (BEWLR/Geo/Lionfish/TACZ) 仅首次执行，结果按 `Item` 缓存，后续渲染直接复用 → 避免每帧重复反射。
- **几何缓存**: 模型 quads/法线/外扩顶点按 `BakedModel` 弱引用缓存，帧间复用。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/**/*` (新增 20+ 文件)
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (保留原生实现，作为 API 内部委托目标)
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java` (新增 API 相关配置)
  - `API_README.md` (API 使用文档)
  - `api/ApiArchitecture.md` (内部架构设计文档)

### 新增：RPG 虹灯 (动态彩虹描边)

- **功能**: 启用后描边颜色随时间动态变化，形成彩虹效果。
- **优先级**: 最高，覆盖所有其他颜色配置 (数据包/配置文件/附魔)。
- **配置项**: `enableRPGRainbow` (默认 `false`)
- **原理**: 基于时间 + 物品 hashCode 的 HSB 转 RGB 动态渐变，5 秒周期。
- **实现**:
  - 集成到 `outline/ColorResolver.resolve()` 首行判断，优先级最高；
  - 缓存每物品独立时间戳 (`RAINBOW_CACHE`)，每 100ms 更新一次，避免每帧计算；
  - 配置改动时 `invalidateCache()` 自动清空彩虹缓存。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/ColorResolver.java` (新增 `resolveRainbowColor()` + `invalidateCache()`)
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (experimental 分类新增)
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (`invalidateCaches()` 调用 `ColorResolver.invalidateCache()`)

### 新增：TACZ 专项兼容

- **功能**: 为 The Arsenal Collection Z 模组提供特殊渲染支持。
- **配置项**: `enableTaczCompat` (默认 `true`)
- **适配内容**: TACZ Lionfish 武器动态模型获取 (通过 `IClientItemExtensions.getCustomRenderer()` 反射渲染器内部模型对象)。
- **修复**: TACZ 武器附魔后模型错位/描边缺失问题。
- **实现**:
  - `TaczAdapter.resolve()` 返回 `ItemTypeInfo(isLionfish=true, isTacz=true)`；
  - `ItemModelRegistry` 按适配器优先级判断 (TACZ→Lionfish)；
  - `OutlineRenderer.findTaczLionfishModel()` 动态获取模型 + 纹理，无预变换。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (新增 `findTaczLionfishModel()`)
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (advanced 分类)

### 新增：数据包颜色支持

- **功能**: 从 `assets/enchanted_outlines/color/pack.json` 加载颜色映射，覆盖配置文件颜色。
- **优先级**: 低于 RPG 虹灯，高于配置文件 (物品/附魔颜色)。
- **配置项**: `enableDataPackColors` (默认 `true`)
- **JSON 格式**:
  ```json
  {
    "minecraft:diamond_sword": "FFD700",
    "minecraft:netherite_chestplate": "FF6A00",
    "my_mod:custom_item": "87CEEB"
  }
  ```
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/api/color/ColorResolver.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (experimental 分类)

### 优化：配置界面分类与持久化

- **分类导航**: 配置界面按功能分为 4 个分类：
  - **general**: 总开关、描边厚度、混色选项
  - **colors**: 默认颜色、附魔颜色、物品颜色、白名单/黑名单
  - **advanced**: BEWLR 3D 配置、TACZ 兼容、多附魔取色模式
  - **experimental**: RPG 虹灯、数据包颜色、配置界面优化
- **配置持久化**: 所有配置修改立即写盘 (`Config.save()`),解析缓存即时失效 (`Config.invalidateCache()`)。
- **语言文件同步**: `zh_cn.json` / `en_us.json` 新增所有配置项的中文/英文翻译。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java` (4 分类导航、新增实验性功能)
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java` (新增配置项)
  - `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json` / `en_us.json` (新增语言键)

### 优化：判断与检查机制 (首次判断缓存)

- **设计**: 物品类型判断 (BEWLR/Geo/Lionfish/TACZ) 仅首次执行，结果按 `Item` 缓存。
- **缓存键**:
  - `ItemTypeInfo`: 按 `Item` 缓存
  - `ShapeResult`: 按 `BakedModel` 缓存 (弱引用，资源重载自动回收)
  - `adapterCache`: 按 `Item` 缓存适配器类型
- **失效机制**:
  - 进出世界：`OutlineRenderer.invalidateCaches()`
  - 资源重载：`OutlineRenderer.invalidateCaches()`
  - 配置改动：`Config.invalidateCache()`
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java`
  - `src/main/java/com/enchantedoutlines/mod/api/algo/ShapeDetector.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (`invalidateCaches` 扩展)

### 修复：API 库编译错误 (NeoForge 20.x 兼容)

- **问题**: 所有适配器未实现 `ItemModelAdapter.resolve()` 方法、`VertexConsumer` 匿名内部类缺少 `setNormal()` 方法、`MultiBufferSource.immediate()` 参数类型不兼容、`ItemEnchantments` API 变更。
- **修复**:
  - 所有适配器 (`BewlrAdapter`/`GeoAdapter`/`LionfishAdapter`/`TaczAdapter`) 实现 `resolve(Item)` 方法，返回 `ItemTypeInfo` 或 `null`
  - 所有适配器添加 `INSTANCE` 单例字段
  - 删除 `BewlrAdapter.getCustomModel()` 中的 `VertexConsumer` 匿名内部类 (改由 `OutlineRenderer` 直接处理)
  - `ColorResolver.getEnchantments()` 改用 `HolderList` 迭代器，移除 `forEach` + 内部变量
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/BewlrAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/GeoAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/LionfishAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/color/ColorResolver.java`

### 修复：RPG 虹灯未集成到渲染管线

- **问题**: `api/color/ColorResolver` 实现了 `resolveRainbowColor()` 方法，但 `outline/ColorResolver.resolve()` 未调用，导致启用 `enableRPGRainbow` 配置后无实际效果。
- **修复**:
  - 在 `outline/ColorResolver.resolve()` 首行判断 `Config.ENABLE_RPG_RAINBOW.get()`，直接调用 `resolveRainbowColor()`；
  - 新增 `resolveRainbowColor()` 静态方法，基于时间 + 物品 hashCode 计算 HSB hue，5 秒周期渐变；
  - 每物品独立缓存 (`RAINBOW_CACHE`)，每 100ms 更新一次，避免每帧计算开销；
  - `OutlineRenderer.invalidateCaches()` 新增调用 `ColorResolver.invalidateCache()`，进出世界/配置改动时清空彩虹缓存。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/ColorResolver.java` (重写 `resolve()` + 新增 `resolveRainbowColor()` + `invalidateCache()`)
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (`invalidateCaches()` 扩展)

### 其他

- **文档**:
  - `API_README.md` - API 使用文档 (快速开始、示例、注意事项)
  - `api/ApiArchitecture.md` - 内部架构设计文档
  - `Changelog.md` - 更新日志 v0.1.9
- **兼容性**: API 库不破坏原版逻辑，`OutlineRenderer` 保留原生实现，API 内部委托给原生方法 → 现有功能完全兼容。
- **扩展性**: 外部模组可通过 `OutlineRenderingApi.registerAdapter()` 注册自定义适配器，无需修改本体代码。

### 修复：GUI 与手持描边颜色不一致 (光影下显示淡灰色)

- **问题**: 开启 Iris 光影时，背包 GUI 物品描边为正确颜色，手持物品描边显示淡灰色/偏色。
- **根因**: 光影 fallback 下，手持扁平物品错误地走 `worldEmissiveOutlineRenderType()` (BLOCK_SHEET 路径)，物品贴图 RGB 混入描边颜色；而 GUI 描边走 `guiOutlineRenderType(tex)` (形状纹理路径)，颜色正确。
- **修复**: 移除 `renderHandOutline` 中扁平物品的 `ITEM_PIXEL_COLOR_GLINT` 条件限制，所有扁平物品在光影 fallback 下统一走 `renderFlatOutlineShape` 形状纹理路径，内部自动按 fallback 选择 `worldEmissiveOutlineRenderType(tex)`，保证与 GUI 描边颜色一致。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 799 行)

### 修复：Hazen 'N Stuff 武器附魔后错位/无描边

- **问题**: Hazen 'N Stuff 的"黑曜石巨剑"和"星怒"等 BEWLR 武器，附魔后描边错位或完全无描边。
- **根因**: Hazen 'N Stuff 使用 `HSNEnchantedItemStackRenderer` 作为 BEWLR 渲染器，但未登记到 `BEWLR_MODEL_HOLDER_CLASSES` 适配表 → 反射失败、模型获取为空、描边缺失。
- **修复**: 新增 `com.coderplus.hazennstuff.client.renderer.HNSEnchantedItemStackRenderer` 到 `BEWLR_MODEL_HOLDER_CLASSES` 数组，采用与永恒星光相同的 `scale(1,-1,-1)` 预变换规则。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 1695 行)

---

### 修复：TACZ 兼容错误 (轮廓错位 + 天上巨大模型)

- **问题**: TACZ (The Arsenal Collection Z) 武器附魔后描边轮廓错位、在天空中出现巨大几何模型。
- **根因**: `TaczAdapter.resolve()` 返回 `isLionfish=true`，误用 LionfishAPI 逐 cube 顶点法线外扩算法;但 TACZ 武器实际用 GeoLib/AnimateGeoItemRenderer 渲染，应走 Geo 骨骼贴图轮廓采样路径。Lionfish 算法用于 Geo → 顶点计算错乱 + 整体放大壳偏出本体 → 轮廓错位 + 空中巨大壳。
- **修复**: `TaczAdapter.resolve()` 改为返回 `isGeo=true, isLionfish=false`，使用 `GeoAdapter.INSTANCE` 处理，贴合 Geo 骨骼贴图轮廓。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java` (第 118-128 行 `resolve()` 方法)

### 修复：真菌感染 (Spore) 武器和盔甲无法渲染轮廓

- **问题**: 真菌感染 (Spore) 模组的 BEWLR 武器和盔甲附魔后无描边。
- **根因**: 真菌感染使用 `com.Harbinger.Spore.Client.SItemProperties` 作为 BEWLR 渲染器，但未登记到 `BEWLR_MODEL_HOLDER_CLASSES` 适配表 → 模型反射失败、描边缺失。
- **修复**: 新增 `com.Harbinger.Spore.Client.SItemProperties` 到 `BEWLR_MODEL_HOLDER_CLASSES` 数组，采用与永恒星光相同的 `scale(1,-1,-1)` 预变换规则。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 1701 行 `BEWLR_MODEL_HOLDER_CLASSES`)

### 修复：Hazen 'N Stuff 怒的武器无法渲染轮廓

- **问题**: Hazen 'N Stuff 的"怒的武器"系列 (新武器) 附魔后无描边。
- **根因**: 怒的武器使用 `cn.leolezury.hazennstuff.client.renderer.HSNEnchantedItemStackRenderer` 作为 BEWLR 渲染器，但该类名已存在于 `BEWLR_MODEL_HOLDER_CLASSES` 中;需确认该类名与怒的武器实际渲染器匹配。
- **修复**: 确认 `cn.leolezury.hazennstuff.client.renderer.HSNEnchantedItemStackRenderer` 已登记到 `BEWLR_MODEL_HOLDER_CLASSES`,怒的武器采用与黑曜石巨剑相同的 `scale(1,-1,-1)` 预变换规则。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java` (第 1700 行 `BEWLR_MODEL_HOLDER_CLASSES`)

### 支持的 BEWLR/Lionfish 模组列表

- **BEWLR 模组**:
  - 永恒星光 (Eternal Starlight): `cn.leolezury.eternalstarlight.common.client.renderer.ESItemStackRenderer`
  - 灾变 (Cataclysm): `com.github.L_Ender.cataclysm.client.render.CMItemstackRenderer` (LionfishAPI)
  - Hazen 'N Stuff: `cn.leolezury.hazennstuff.client.renderer.HSNEnchantedItemStackRenderer`
  - 真菌感染 (Spore): `com.Harbinger.Spore.Client.SItemProperties`
- **GeoLib/AzureLib 模组**:
  - TACZ (The Arsenal Collection Z): `TaczAdapter` → `GeoAdapter`
  - Hazen 'N Stuff 盔甲: `GeoRenderProvider` / `AzArmorRendererRegistry`
- **LionfishAPI 模组**:
  - 灾变武器 (部分 Lionfish 武器): `CMItemstackRenderer` → `LionfishAdapter`

### 优化：API 算法同步更新

- **内容**: 更新 TACZ 适配逻辑，确保与 API 库算法一致;TACZ 物品统一走 Geo 路径，不再误判为 Lionfish。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/api/adapter/TaczAdapter.java`
  - `src/main/java/com/enchantedoutlines/mod/api/core/ItemModelRegistry.java` (适配器优先级判断)

---

## v0.1.8 (2026-08-16)

### 新增：GeckoLib 4 + AzureLib 物品与盔甲描边支持

- **背景**: Hazen 'N Stuff 等模组用 GeckoLib 4 / AzureLib(其 fork) 渲染武器/法杖/盔甲，
  骨骼层 (`GeoBone`/`AzBone` → `GeoCube` → `GeoQuad`/`GeoVertex`) 几乎同构 (仅包名不同)。
  旧 BEWLR 适配 (永恒星光/灾变静态字段) 拿不到它们的模型 → 这些模组的物品/盔甲
  <b>完全无描边</b>(盔甲会被误画成原版盔甲轮廓)。
- **物品 (武器/法杖/工具)**:
  - GeckoLib: `GeoRenderProvider.of(item).getGeoItemRenderer()` → `getGeoModel()` →
    `getBakedModel` → `BakedGeoModel.topLevelBones()`。⚠️ GeckoLib 物品的 `GeoItemRenderer`
    不通过 `IClientItemExtensions.getCustomRenderer()` 暴露，必须走 `GeoRenderProvider`;
  - AzureLib: `AzItemRendererRegistry.getOrNull` → `provider().provideBakedModel` →
    `AzBakedModel.topLevelBones()`;
  - 物品预变换 `translate(0.5, 0.51, 0.5)`(库内置，非模组自定义，无 scale 翻转)。
- **盔甲**:
  - AzureLib: `AzArmorRendererRegistry.getOrNull(stack)` → 反射调用原模组骨骼准备
    `grabRelevantBones` / `applyBaseTransformations` / `applyBoneVisibilityBySlot`
    (精确复刻本体，含 Hazen 自定义 `AzArmorLeggingTorsoLayerPipeline`)+ 全局变换
    `translate(0, 1.5, 0) + scale(-1,-1,1)`(缺了盔甲上下颠倒);
  - GeckoLib: `GeoRenderProvider.getGeoArmorRenderer` → 同上。GeckoLib 的
    `HumanoidArmorLayerMixin` 用 `@WrapWithCondition` 拦截 `renderArmorPiece` 并 cancel
    → 我们的 12 参 `renderArmorPiece` HEAD 不触发，改在 `render` HEAD 遍历 4 槽位处理;
  - 盔甲骨骼名约定:`armorHead`/`armorBody`/`armorRightArm`/.../`armorLeftBoot`
    (+ Hazen 的 `armorLeggingTorsoLayer`)。
- ⚠️ <b>JPMS 模块化</b>: 跨模块反射必须 `setAccessible(true)` —— 匿名类 (如 GeckoLib
  `GeoRenderProvider` 的匿名实现) 非 public，不 setAccessible 会抛
  `IllegalAccessException`(TrueNightsEdge 武器实测)。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
  - `src/main/java/com/enchantedoutlines/mod/mixin/HumanoidArmorLayerMixin.java`

### 修复：扁平物品描边渲染成平面正方形 (不遵循轮廓)

- **根因**: 扁平描边旧实现 `emitQuad` 直接把<b>模型 quad 的 UV</b> 交给着色器采样
  BLOCK_SHEET。原版扁平物品 (剑/工具) 烘焙后的 UV 是精灵在 atlas 的绝对 UV(采样正确);
  但<b>模组扁平模型</b>的 UV 常是 [0,1] 全图 → 采样越出精灵区域 → 整块 quad 全不透明
  → 描边变成平面正方形。
- **修复**: 新增 `renderFlatOutlineShape` —— CPU 读贴图 alpha 生成<b>形状纹理</b>
  (`shapeTextureFor`)+ 精灵相对 UV(`emitQuadShape`) 渲染，形状由贴图 alpha 决定、
  与模型 UV 无关;GUI/手持扁平描边统一走它。删除死代码 `emitQuad` /
  `renderFlatPureColorShape` / `outlineRenderType()`。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 修复：Geo 骨骼描边遵循贴图轮廓 (纯采样贴图)

- **设计**: 3D 骨骼盔甲/物品统一采样本体贴图 alpha 做形状 (纹理轮廓):<b>贴图有内容的部件
  描边</b>(剑形武器、sigil 符号、盔甲主体),<b>贴图全透明的部件无描边</b>
  (如柠檬神光环 spin，贴图区域 0/1024 像素不透明——贴图本身透明，没有轮廓可依)。
- **方案演进**: 纯几何 (扁平 cube 画成平面方形) → 采样贴图 (透明部件无描边) → 混合双渲染 (几何层破坏轮廓) → 几何边框线 (不贴合纹理轮廓) →
  <b>最终纯采样贴图</b>。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`

### 其他
- 知识库：AGENTS.md 新增铁律 3.5(扁平物品描边必须用形状纹理，禁止模型 UV 采样
  BLOCK_SHEET)/ 3.6(Geo 骨骼描边采样本体贴图轮廓，贴图透明部件无描边);同步
  Cherry Studio 知识库 (合并为单一 AGENTS.md 文档)。

---

## v0.1.8 (2026-08-16, 重复条目已合并)

### 新增：物品黑白名单支持 `minecraft:*` 命名空间通配符

- **功能**: 白名单 `enabledItems` / 黑名单 `disabledItems` 支持通配符。
- **示例**: `minecraft:*` 匹配整个命名空间、`minecraft:di*` 匹配路径前缀。
- **过滤顺序**: 白名单在前，黑名单在后。
- **影响文件**:
  - `src/main/java/com/enchantedoutlines/mod/config/Config.java`
  - `src/main/java/com/enchantedoutlines/mod/outline/ColorResolver.java`
  - `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`
  - `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json` / `en_us.json`

---

## v0.1.0 - v0.1.7 (历史版本)

- 基础描边功能实现
- BEWLR 物品支持 (永恒星光/灾变武器)
- LionfishAPI 逐 cube 外扩算法
- 光影兼容 (Iris/Oculus)
- 配置系统与颜色解析
- 性能优化 (几何缓存/反射缓存)
