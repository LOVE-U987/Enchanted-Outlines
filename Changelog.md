# Enchanted Outlines Changelog

---

## v0.1.2 (2026-08-08)

### 修复: 配置界面长文本编辑框打开时超长配置被截断(保存即破坏配置)

- 根因: `LongTextEditScreen.init()` 中先调用 `box.setValue(initial)` 再
  `box.setMaxLength(4096)`,`EditBox#setValue` 会按**当前** maxLength(默认 32)截断字符串,
  而 `enchantColors` 等默认配置远超 32 字符 → 打开编辑框内容即被截断为前 32 字符,保存后配置丢失大部分映射。
- 修复: 先 `setMaxLength` 再 `setValue`,并将长文本上限从 4096 提高到 16384
  (容纳数百个附魔/物品映射;`EnchantedConfigScreen.java`)。
- 顺带完善: 颜色输入框(默认色)限制为 7 位(`#RRGGBB` 最长)、数值输入框(厚度)限制为 10 位,
  避免粘贴超长字符串(`EnchantedConfigScreen.java` 的 `addColorRow` / `addDoubleRow`)。

## v0.1.2 (2026-08-08)

### 修复: 光影(Iris / Oculus)下世界渲染描边透明、颜色丢失、丢失发光、盾/三叉戟反射方块纹理

- 根因: Iris 默认配置 `allowUnknownShaders=false` 且光影激活时,自定义 core shader
  (`enchanted_outlines:outline`)被 Iris 的 `iris$shouldSkipThis` 判定为"未知 shader"
  → <b>绘制被完全跳过</b>,描边被渲染为透明。GUI 渲染不经光影,故背包内描边正常、世界内消失。
- 修复: `OutlineRenderer` 改为<b>运行时检测光影状态</b>(反射调用
  `IrisApi.getInstance().isShaderPackInUse()` 与 `IrisConfig.shouldAllowUnknownShaders()`,
  不依赖 Iris 编译期、也不按"是否安装"静态判断——装了 Iris 但没开光影包时行为完全不变):
  - 光影<b>未激活</b>或已开启 iris.properties 的 allowUnknownShaders → 继续用自定义描边
    shader(alpha boost / cutout / 发光全保留,与无光影一致);
  - 光影激活且不允许未知 shader(默认)→ 世界渲染路径改用内置 <b>`entity_translucent_emissive`</b>
    shader 构造的 RenderType(不乘 lightmap → 描边全亮发光,不再被光影重写光照而变暗):
    - 盾牌/三叉戟近似盒模型 → 采样 <b>纯白纹理</b>(新增
      `assets/enchanted_outlines/textures/white.png`):独立纹理(非方块图集)不触发
      Iris 的方块材质反查 → 消除"盾/三叉戟反射四周方块纹理",颜色纯正;
    - 扁平物品(剑/弓/工具)→ 保留 BLOCK_SHEET(物品贴图 alpha 遮罩决定形状),
      颜色 = 描边色 × 物品贴图像素色(混合,见下方新配置项);
    - 物品展示框在光影 fallback 下统一半透明发光(不再硬切不透明)。
  - 全部保留 `COLOR_WRITE`(不写深度)+ `LEQUAL` 深度测试 + `NO_CULL`,顶点几何不变。

### 新增: 配置项 `itemPixelColorGlint`(默认 true)

- 功能: "附魔光效根据物品每个像素的颜色确定" —— 光影兼容模式下,扁平物品描边色与
  物品贴图每个像素的颜色混合(如红色描边在钻石剑上呈黄绿色),混合出的颜色独特有趣。
- true(默认): 保留物品贴图形状(轮廓贴合物品),接受颜色混合;
- false: 描边为<b>纯描边色且保留物品形状</b>(见下方"关闭混色不再矩形"的说明)。
- 无光影 / 已开启 Iris 的 allowUnknownShaders 时,自定义 shader 可分离形状与颜色,
  描边始终为纯色,本配置不影响。
- 配置界面「通用」分类新增该开关,可即时保存。

### 扩展: `itemPixelColorGlint` 覆盖 3D 物品(手持/掉落物/展示框的方块等)

- 之前 3D 物品在光影 fallback 下恒采样纯白纹理(纯描边色,无混色选项);现在
  <b>3D 物品也受 `itemPixelColorGlint` 控制</b>:开混色 → 采样 BLOCK_SHEET
  (方块贴图颜色 × 描边色);关混色 → 纯白(纯描边色)。形状均由几何外扩决定,
  不随纹理变化 → 关闭混色形状不退化。
- 盾牌/三叉戟<b>近似盒模型</b>例外:其全部 quads 用 stone 精灵,采样 BLOCK_SHEET
  会被 Iris 误判为方块材质(反射方块纹理)且 stone 灰把描边色染暗 →
  <b>恒为纯白纯色</b>(新增 `usesOnlyStoneSprite` 判断)。
- 至此所有世界渲染路径统一为"外层几何算法不变 + 纹理/颜色来源切换":
  - 扁平物品:混色开=BLOCK_SHEET(形状+混色),混色关=形状纹理(形状+纯色);
  - 3D 物品:混色开=BLOCK_SHEET(几何形状+混色),混色关=纯白(几何形状+纯色);
  - 盾牌/三叉戟盒:恒纯白(几何形状+纯色);
  - 盔甲/鞘翅/投掷物:混色开=原纹理(镂空+混色),混色关=形状纹理(镂空+纯色)。

### 新增: 配置项 `armorPixelColorGlint`(默认 true)

- 功能: 盔甲/鞘翅/投掷物实体的描边是否与纹理颜色混合(光影兼容模式下):
  - true(默认): 采样原纹理(armor / elytra / trident 材质)的 alpha 遮罩,形状贴合
    单层纹理镂空,颜色与纹理混合;
  - false: 描边为纯描边色(形状由模型几何决定,不再贴合纹理镂空)。
- 配置界面「通用」分类新增该开关,可即时保存。

### 修复: 关闭混色后扁平物品不再变成矩形

- 根因: 内置 fsh 是 `texel × vertexColor`,单纹理采样无法分离"形状(alpha)"与
  "颜色(RGB)";关闭混色改采样纯白纹理 → 形状丢失变矩形。
- 修复: 新增<b>"描边色形状纹理"</b>:关闭混色时,CPU 读取物品贴图原图
  (`SpriteContents.originalImage`,内存像素非 GPU 回读)生成一张 RGB=描边色、
  A=原 alpha 的动态纹理(缓存到 TextureManager,资源重载自动销毁),顶点 UV 从
  atlas 绝对坐标重映射为 sprite 相对坐标 → 输出 = <b>纯描边色 × 物品形状</b>。
  - 首次渲染某 (物品, 颜色) 组合有一次性的像素处理开销(毫秒级),之后缓存复用;
  - 读取失败(反射异常等)自动回退纯白矩形,保证描边仍可见。

### 调整: 光影兼容下盔甲/鞘翅/投掷物描边恢复原算法(仅加发光)

- 上一版把盔甲/鞘翅/投掷物(独立纹理实心模型)在光影 fallback 下改为纯白纹理,
  导致轮廓形状不再贴合单层纹理的镂空。现改回:<b>算法与原来相同</b>(仍采样
  armor / elytra / trident 纹理的 alpha 遮罩贴合形状),仅把 translucent 换成
  emissive(不乘 lightmap → 加发光);是否混色由 `armorPixelColorGlint` 控制。
- 特殊附魔颜色(配置映射):与普通附魔统一,一律按混色开关走(开=混色,关=纯色形状),
  不再出现"特殊色与混色不一致"的现象。

### 重构: 盔甲/鞘翅/投掷物描边"外层算法统一"

- 之前盔甲关闭混色时采样纯白纹理 → 形状从"贴合纹理镂空"变成"实心盒子外扩",
  算法表现不一致。现统一:
  - <b>外层几何算法(renderPartInflated 逐 cube 放大壳)始终是同一个</b>,三种模式
    (自定义 shader / 光影混色开 / 光影混色关)只切换"采样纹理";
  - 光影混色关 → 复用<b>描边色形状纹理</b>方案(新增
    `shapeTextureForLocation`,从 ResourceManager 读取盔甲/鞘翅/三叉戟纹理 PNG,
    生成 RGB=描边色、A=原 alpha 的纹理)→ 形状与混色开时完全一致,仅颜色为纯描边色;
  - 扁平物品与盔甲的形状纹理生成逻辑抽为统一入口 `shapeTexture(source, src, color)`。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`,
  `src/main/java/com/enchantedoutlines/mod/config/Config.java`,
  `src/main/java/com/enchantedoutlines/mod/config/EnchantedConfigScreen.java`,
  `src/main/resources/assets/enchanted_outlines/lang/zh_cn.json`,
  `src/main/resources/assets/enchanted_outlines/lang/en_us.json`
- 测试环境: iris-neoforge 1.8.12 + sodium 0.6.13 (NeoForge 1.21.1)

---

## v0.1.1 (2026-08-08)

### 修复: 背包中附魔物品出现"格子大小"的原版附魔光效闪动

- 根因: GUI 扁平物品描边 RenderType(`enchanted_outlines_outline`)使用 `COLOR_DEPTH_WRITE`(写深度)。
  描边画的是整张 16×16 平面 quad,而 GL 深度写入与 alpha 无关 → 物品贴图透明(alpha=0)区域同样
  写入了深度;原版附魔光效(glint)用 `EQUAL_DEPTH_TEST` 只在物品本体写入深度的像素上显示,
  描边写掉的格子背景深度让 glint 在整个槽位通过深度测试 → 出现"格子大小的原版附魔光效闪动"。
- 修复: `OutlineRenderer.outlineRenderType()` 写掩码由 `COLOR_DEPTH_WRITE` 改为 `COLOR_WRITE`
  (只写颜色不写深度),与 GUI 3D 路径 `handOutlineRenderType` 一致;描边在物品本体之前绘制、
  本体随后覆盖中心,描边无需写深度,glint 恢复只沿物品形状显示。
- 影响文件: `src/main/java/com/enchantedoutlines/mod/outline/OutlineRenderer.java`
