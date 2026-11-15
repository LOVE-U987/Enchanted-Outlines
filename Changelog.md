# Enchanted Outlines Changelog

---
## v0.3.0

## 未发布

### 修复:平台渲染期模型替换物品的手持描边错位(2026-11-08,issue #4)

- 现象:Vanilla Backport 等模组的矛(注册了"渲染期模型替换"的物品)手持描边
  形状/朝向/比例与本体完全错位,被误认为"高分辨率贴图物品描边缩放错误"。
- 根因:Blackgear Platform 在 `ItemRenderer.render` 的 `@ModifyVariable` 里对手持
  context 提交专用模型(如 `<material>_spear_in_hand`,32x32 贴图 + 专属 display
  变换),而 `ItemRenderer.getModel` 的 `@ModifyVariable` 只咨询 `renderThirdPerson`
  (矛恒 pass)→ 描边侧经 `getModel` 只拿到 GUI 平面模型(16x16),本体却渲染
  in_hand 模型 → 描边 = GUI 剪影套手持本体。
- 修复:新增 `outline/PlatformCompat`(反射零硬依赖,句柄静态缓存,平台缺席零成本),
  在三个描边入口(GUI / 掉落物与展示框 / 第一与第三人称手持)镜像本体 render 期的
  模型解析(同一注册表、同一 `renderFirstPerson`、同一 `ItemModelShaper`,幂等);
  平台全自定义渲染(`DynamicItemRenderer`)物品跳过平面描边(宁可无描边,不画错位)。
- 边界:注入点不变(issue #2 铁律);平台未注册的物品与未安装平台的用户行为零变化。

### 修复:开启 RPG 虹灯时扁平描边每帧重烘形状纹理(2026-11-08)

- 现象:开启 `enableRPGRainbow` 后,GUI/手持/掉落物中的附魔扁平物品每帧对整张
  精灵贴图逐像素重烘一次形状纹理并注册/驱逐 GPU 纹理(虹灯色含时间分量,每帧
  不同 → `ShapeKey` 的 color 维度每帧 miss),满背包附魔物品时开销显著。
- 修复:虹灯路径复用资源包 cycle 动画同款"白色烘焙 + 顶点色"策略 —— 形状纹理
  恒烘 rgb=白(缓存键恒定,命中现有 `ShapeKey`),当前虹灯色经顶点色传入
  (shader 输出 `vertexColor×白texel` 与烘焙色数学等价,alpha 遮罩不变,铁律 3
  语义不受影响)。虹灯开启时 `ColorResolver.resolve` 对全部描边物品恒返回虹灯色
  (优先级仅次禁用),故在 `renderFlatOutlineShape` 按配置全局判定。
- 附带语义:虹灯物品与动画定义物品一致,混色烘焙模式下视为纯色(混色烘焙同样
  会按帧爆纹理,动画色再乘物体色无意义)。

### 修复:光影下平面/十字模型物品描边变成实心方块(2026-11-08,实机反馈)

- 现象:开启光影(Iris)且「光影像素混色」关闭时,杜鹃花等平面/十字模型原版物品的
  描边在下部稀疏贴图区透出整块实心矩形("方形轮廓")。无光影下不可见(采样
  `BLOCK_SHEET`,自带物品 alpha 遮罩)。
- 根因(两层):① 这类模型全部由零厚度贴片构成,正反面法线相反、按位置聚合后为零
  → `prepareExpand` 不外扩 → 描边 quad 与本体 quad 完全同位;② 光影 fallback 的
  纯白分支绑定 1×1 不透明 `WHITE_TEXTURE`,形状只靠几何外扩 → 本体贴图透明处把
  实心矩形透出来。杜鹃花贴图上部 10/16 行致密叶团遮住描边,下部 6/16 行稀疏茎干区
  暴露,故表现为"下部方形"。
- 修复:fallback 纯白路径改为逐 sprite 绑定"烘白形状纹理(源贴图 alpha)+ 顶点色"
  (复用虹灯/资源包动画定义的既有机制),颜色经顶点色传入(`白texel × vertexColor`
  = 纯描边色);几何算法不变(仍是顶点法线外扩,铁律 3)—— 实心物品贴图 alpha 恒满
  → 输出与旧行为完全一致,仅消除"透明区被实心描边填满"的伪影,观感与无光影路径
  对齐。盾牌/三叉戟盒模型(stone 精灵)保持纯白纹理不变。
- 附带:两个"形状纹理不可用 → 回退纯白矩形"的静默分支补节流诊断日志
  (10s/原因#纹理,info 级),"方形"类报障可直接从 latest.log 定位纹理与原因。
- 已知残留:零厚度贴片模型在纯白路径下本无有效外扩环(几何退化,不外扩),本次只
  保证"不再画错";这类物品的描边宽度仍接近 0,与无光影路径同款限制。若需要给平面
  模型补外扩环,须专门设计面内偏移方案(不在本次范围)。


- 现象:Hazen'N Stuff 的 GeckoLib 武器(`hazennstuff:soul_reaper` / `skyscorcher`)放在
  展示框/基座上时,描边渲染成整块实心矩形(实机截图确认)。
- 根因(与同日"平面/十字模型方形"是**两条独立链路**):这些武器走 `BewlrCapture` 旁路
  捕获,捕获几何 `solid=0 / zeroThick=180`(全部零厚度贴片)。GeckoLib 零厚度 cube
  (某轴 size=0)的盒式 UV 在零宽面上必然退化(`u0==u1` 或 `v0==v1`);零厚度路径以
  捕获 UV 采样武器贴图 alpha 作遮罩,退化轴上的采样被拉成**均匀色带** → 整个 quad
  不透明 → 实心矩形。
- 修复(方案 A,铁律 6.9"宁可无描边,不画错位"):零厚度贴片路径与全平坦路径统一加
  `isDegenerateUv()` 守卫,UV 在任一轴跨度 < `UV_DEGENERATE_EPS`(1e-4)的 quad 不发射
  描边;武器有立体/正常 UV 的部分照常描边。
- 诊断:现有的 `mix 节流` / `flat 节流` 日志新增 `degUV=<n>`,可直接看出每个 BEWLR
  物品被跳过的退化贴片数量,便于后续定位同类模组。

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
