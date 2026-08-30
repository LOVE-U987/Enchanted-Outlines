# Enchanted Outlines API 使用文档

## 概述

本 API 库将原版渲染逻辑收编为结构化的模块，允许外部模组快速调用和管理描边渲染功能。

## 架构

```
com.enchantedoutlines.mod.api
├── core/                    # 核心层
│   ├── OutlineRenderingApi  # 主 API 接口
│   ├── ItemTypeInfo         # 物品类型信息
│   └── ItemModelRegistry    # 物品类型注册表
├── algo/                    # 算法层
│   ├── ShapeResult          # 形状检测结果
│   ├── OutlineShapeTextureProvider  # 形状纹理提供者
│   └── ShapeDetector        # 形状检测器
├── color/                   # 颜色处理层
│   └── ColorResolver        # 颜色解析器
├── pipeline/                # 渲染管线层
│   └── RenderPipeline       # 渲染管线
└── adapter/                 # 适配器层
    ├── ItemModelAdapter     # 物品模型适配器接口
    ├── BewlrAdapter         # BEWLR 适配器
    ├── GeoAdapter           # Geo 适配器
    ├── LionfishAdapter      # Lionfish 适配器
    └── TaczAdapter          # TACZ 适配器
```

## 快速开始

### 1. 解析物品类型

```java
import com.enchantedoutlines.mod.api.core.OutlineRenderingApi;
import com.enchantedoutlines.mod.api.core.ItemTypeInfo;
import net.minecraft.world.item.Item;

Item item = /* 物品 */;
ItemTypeInfo typeInfo = OutlineRenderingApi.INSTANCE.resolveItemType(item);

// 检查是否为 BEWLR 物品
if (typeInfo.isBewlr()) {
    // BEWLR 特定处理
}

// 检查是否为 Geo 盔甲
if (typeInfo.isGeo() && typeInfo.isArmor()) {
    // Geo 盔甲特定处理
}
```

### 2. 检测形状

```java
import com.enchantedoutlines.mod.api.algo.ShapeResult;
import com.enchantedoutlines.mod.api.core.OutlineRenderingApi;
import net.minecraft.client.renderer.block.model.BakedModel;
import net.minecraft.world.item.ItemStack;

ItemStack stack = /* 物品栈 */;
BakedModel model = /* 物品模型 */;

ShapeResult shape = OutlineRenderingApi.INSTANCE.detectShape(stack, model, null);

// 判断是否为扁平物品
if (shape.isFlat()) {
    // 使用屏幕偏移算法
} else {
    // 使用 3D 外扩算法
}
```

### 3. 解析颜色

```java
import com.enchantedoutlines.mod.api.color.ColorResolver;
import net.minecraft.world.item.ItemStack;

ItemStack stack = /* 物品栈 */;
int color = ColorResolver.INSTANCE.resolve(stack);

// 颜色优先级:
// 1. RPG 虹灯 (若启用)
// 2. 数据包颜色
// 3. 配置颜色 (物品级)
// 4. 附魔颜色
// 5. 默认颜色
```

### 4. 执行渲染

```java
import com.enchantedoutlines.mod.api.core.OutlineRenderingApi;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.BakedModel;

PoseStack pose = /* 渲染姿势 */;
BakedModel model = /* 物品模型 */;
int color = /* 描边颜色 */;
float thickness = /* 描边厚度 */;

// GUI/GROUND/FIXED
OutlineRenderingApi.INSTANCE.renderGuiOutline(pose, model, x, y, quadSize, color, thickness);

// 手持
OutlineRenderingApi.INSTANCE.renderHandOutline(model, pose, color, thickness);
```

## 配置选项

### 新增配置项

1. **RPG 虹灯** (`enableRPGRainbow` - false)
   - 动态彩虹描边，优先级最高
   - 覆盖所有其他颜色配置

2. **TACZ 兼容** (`enableTaczCompat` - true)
   - 启用 The Arsenal Collection Z 专项支持

3. **数据包颜色** (`enableDataPackColors` - true)
   - 从 `assets/enchanted_outlines/color/pack.json` 加载颜色
   - 优先级低于 RPG 虹灯，高于配置文件

4. **配置界面优化** (`optimizeConfigUi` - true)
   - 配置界面按功能分类显示

### 配置优先级

```
RPG 虹灯 > 数据包颜色 > 物品配置 > 附魔配置 > 默认颜色
```

## 注册自定义适配器

```java
import com.enchantedoutlines.mod.api.adapter.ItemModelAdapter;
import com.enchantedoutlines.mod.api.core.OutlineRenderingApi;
import net.minecraft.world.item.Item;

public class MyCustomAdapter implements ItemModelAdapter {
    @Override
    public boolean canHandle(Item item) {
        return item.getRegistryName().toString().equals("my_mod:my_item");
    }
    
    @Override
    public boolean renderGui(PoseStack pose, BakedModel model, int x, int y,
                              int quadSize, int color, float thickness) {
        // 自定义渲染逻辑
        return true;
    }
}

// 注册适配器
OutlineRenderingApi.INSTANCE.registerAdapter(new MyCustomAdapter());
```

## 形状算法说明

### 扁平物品 (`isFlat = true`)
- **算法**: 屏幕偏移
- **原理**: 8 方向平移 + 形状纹理
- **适用**: GUI 物品、手持扁平物品

### 3D 物品 (`is3d = true`)
- **算法**: 顶点法线外扩
- **原理**: 每个顶点沿平均法线方向外扩固定距离
- **适用**: 3D 物品、手持 3D 物品

### BEWLR 物品
- **算法**: 几何放大壳 / 逐 cube 外扩
- **配置**: `bewlr3dPerCube` 控制
- **适用**: BEWLR 自定义渲染物品

### Geo 骨骼物品
- **算法**: 逐 cube 顶点法线外扩
- **适用**: GeckoLib/AzureLib 盔甲/物品

### LionfishAPI 物品
- **算法**: 逐 cube 顶点法线外扩 (默认) / 整体放大壳 (回退)
- **适用**: 灾变等 Lionfish 武器

## 缓存机制

### 判断缓存 (首次判断，结果复用)
```java
// ItemTypeInfo 按 Item 缓存
ItemTypeInfo info = typeInfoCache.get(item);

// ShapeResult 按 BakedModel 缓存
ShapeResult shape = shapeCache.get(model);
```

### 几何缓存 (弱引用)
```java
// 模型几何缓存
WeakHashMap<BakedModel, ModelGeometry> modelGeometryCache;

// Geo cube 几何缓存
WeakHashMap<GeoCube, ExpandedGeoCube> geoCubeCache;

// Lionfish cube 几何缓存
WeakHashMap<LionfishCube, ExpandedLionfishCube> lionfishCubeCache;
```

### 缓存失效
```java
// 资源重载/进出世界时调用
OutlineRenderingApi.INSTANCE.invalidateAllCaches();
```

## 颜色混合模式

### 纯描边色 (推荐)
- **开关**: `itemPixelColorGlint = false`
- **效果**: 描边为纯色，不混合物品颜色
- **形状**: 扁平物品通过形状纹理保留轮廓

### 像素混合
- **开关**: `itemPixelColorGlint = true`
- **效果**: 描边与物品颜色混合
- **形状**: 完美贴合物品轮廓

### RPG 虹灯
- **开关**: `enableRPGRainbow = true`
- **效果**: 动态彩虹描边
- **优先级**: 最高，覆盖所有配置

## 兼容性说明

### BEWLR 物品
- 自动检测 IClientItemExtensions 扩展
- 支持 inventory 模型变体 (GUI/GROUND/FIXED)
- 手持物品通过反射获取自定义模型

### Geo 物品
- 支持 GeckoLib 和 AzureLib
- 自动检测盔甲/物品区别
- 精确复刻骨骼变换

### Lionfish 物品
- 灾变武器专项支持
- 逐 cube 外扩/整体放大壳双算法
- 回退机制保证兼容性

### TACZ 物品
- 特殊纹理格式支持
- 武器部件独立处理
- 可配置开关

## 数据包颜色格式

```json
{
  "minecraft:diamond_sword": "FFD700",
  "minecraft:netherite_chestplate": "FF6A00",
  "my_mod:custom_item": "87CEEB"
}
```

位置：`assets/enchanted_outlines/color/pack.json`

## 注意事项

1. **渲染线程**: 所有渲染 API 必须在渲染线程调用
2. **缓存清理**: 进出世界/资源重载时自动清理缓存
3. **性能**: 判断逻辑仅首次执行，后续使用缓存
4. **配置保存**: 配置修改自动保存，解析缓存即时失效

## 示例模组集成

```java
@Mod.EventBusSubscriber(modid = "my_mod", bus = Mod.EventBusSubscriber.Bus.MOD)
public class MyOutlineIntegration {
    
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 注册自定义适配器
        OutlineRenderingApi.INSTANCE.registerAdapter(new MyItemAdapter());
    }
    
    public static class MyItemAdapter implements ItemModelAdapter {
        @Override
        public boolean canHandle(Item item) {
            return item.getRegistryName().getNamespace().equals("my_mod");
        }
        
        @Override
        public boolean renderGui(PoseStack pose, BakedModel model,
                                  int x, int y, int quadSize,
                                  int color, float thickness) {
            // 使用 OutlineRenderer 原生方法
            return OutlineRenderer.INSTANCE.renderOutline(pose, model, x, y, quadSize, color, thickness);
        }
    }
}
```

## 故障排查

### 描边不显示
1. 检查 `Config.ENABLE` 开关
2. 检查物品是否在白名单
3. 检查是否有冲突的渲染模组

### 描边颜色错误
1. 检查配置文件中颜色格式 (RRGGBB)
2. 检查数据包 JSON 格式
3. 尝试关闭 RPG 虹灯测试

### 性能问题
1. 减少描边厚度
2. 关闭不必要的形状检测
3. 检查是否有大量自定义物品

## 更新日志

### v0.2.0 (当前)
- 新增 API 库架构
- 收编所有渲染算法
- 新增 RPG 虹灯功能
- 新增 TACZ 兼容
- 新增数据包颜色支持
- 优化配置界面分类

### v0.1.x
- 原版渲染逻辑

---

**维护者**: Enchanted Outlines Team  
**文档版本**: 0.2.0  
**最后更新**: 2026-08-30
