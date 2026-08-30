# Enchanted Outlines API 库架构设计

## 目标
将各渲染库与算法收编为结构化的 API 库，允许快速调用与管理。

## 架构层级

### 1. Core (核心层)
- `OutlineRenderingApi` - 主 API 接口，对外统一入口
- `RenderingPipeline` - 渲染流程管理器
- `RenderingContext` - 渲染上下文 (状态/配置/缓存)
- `OutlineColorSource` - 颜色来源接口 (配置/数据包/RPG)

### 2. Algorithms (算法层)
- `ShapeDetector` - 形状检测算法
  - `FlatShapeTexture` - 扁平物品形状纹理
  - `VertexNormalExpand` - 顶点法线外扩
  - `BoundingBoxInflate` - 包围盒放大
  - `PerCubeExpand` - 逐 cube 外扩
- `ColorMixer` - 颜色混合算法
  - `PureColor` - 纯描边色
  - `PixelColorMix` - 像素混合
  - `RPGRainbow` - RPG 虹灯

### 3. Renderers (渲染器层)
- `OutlineRendererBase` - 基础渲染器
- `GuiRenderer` - GUI 渲染器
- `WorldRenderer` - 世界渲染器
- `EntityRenderer` - 实体渲染器
- `ArmoredRenderer` - 盔甲渲染器

### 4. Adapters (适配器层)
- `ItemModelAdapter` - 物品模型适配
- `BewlrAdapter` - BEWLR 适配器
- `GeoAdapter` - Geo 骨骼适配器
  - `GeckoLibAdapter` - GeckoLib 具体实现
  - `AzureLibAdapter` - AzureLib 具体实现
- `LionfishAdapter` - LionfishAPI 适配器
- `TaczAdapter` - TACZ 专项适配器

### 5. Registry (注册表层)
- `ShapeAlgorithmRegistry` - 形状算法注册表
- `ColorAlgorithmRegistry` - 颜色算法注册表
- `ModelTypeRegistry` - 模型类型注册表
- `RenderPipelineRegistry` - 渲染管线注册表

## 模块职责

### 形状检测 (Shape Detection)
```
输入：模型/贴图
↓
判断流程 (仅首次，结果缓存):
1. 检查是否为 BEWLR 物品 → 直接使用 BEWLR 适配器 (跳过通用判断)
2. 检查是否为 Geo 骨骼 → 直接使用 Geo 适配器
3. 检查是否为 LionfishAPI → 直接使用 Lionfish 适配器
4. 通用判断:扁平 vs 3D → 选择对应算法
↓
输出：形状检测结果 + 选用的算法
```

### 颜色处理 (Color Processing)
```
输入：物品/附魔/NBT
↓
颜色来源优先级:
1. RPG 虹灯 (若启用，动态彩虹)
2. 数据包颜色 (若存在)
3. 配置文件
↓
颜色混合:
- 根据配置选择 PureColor / PixelColorMix / RPGRainbow
↓
输出：最终描边颜色
```

### 渲染管线 (Rendering Pipeline)
```
输入：渲染上下文 (物品/位置/姿势/颜色)
↓
1. ShapeDetector.detect() → 形状检测结果
2. ColorResolver.resolve() → 最终颜色
3. RenderPipeline 选择:
   - GUI → GuiRenderer
   - World → WorldRenderer
   - Entity → EntityRenderer
4. Adapter 选择渲染方式
5. 执行渲染
```

## 缓存策略

### 判断缓存 (首次判断缓存)
```java
Map<Item, ItemTypeInfo> itemTypeInfoCache;
Map<BakedModel, ShapeResult> shapeCache;
Map<ItemStack, AdapterType> adapterCache;
```

### 几何缓存 (与 OutlineRenderer 一致)
```java
WeakHashMap<BakedModel, ModelGeometry> modelGeometryCache;
WeakHashMap<GeoCube, ExpandedGeoCube> geoCubeCache;
WeakHashMap<LionfishCube, ExpandedLionfishCube> lionfishCubeCache;
```

## 新增功能

### 1. RPG 虹灯 (优先级最高)
```java
public class RPGRainbowAlgorithm implements ColorAlgorithm {
    private final long lastTick = System.currentTimeMillis();
    
    public int getColor(ItemStack stack, int baseColor) {
        // 基于时间 + 物品 hashCode 的动态彩虹
        long time = System.currentTimeMillis() % 5000;
        float hue = (time + stack.hashCode()) / 5000f;
        return HSBtoRGB(hue, 1.0f, 1.0f);
    }
}
```

### 2. 数据包颜色
```java
public class DataPackColorSource implements ColorSource {
    private final Map<ResourceLocation, Integer> colors;
    
    public void loadDataPackColors(ResourceLocation loc) {
        // 从 assets/enchanted_outlines/color/pack.json 加载
        // 格式：{"minecraft:diamond_sword": "FFD700", ...}
    }
}
```

### 3. TACZ 专项兼容
```java
public class TaczAdapter implements ItemModelAdapter {
    // TACZ 自定义模型处理
    // 特殊纹理格式/渲染方式
}
```

## 配置持久化
- 所有配置改动通过 `Config.markChanged()` 自动保存到磁盘
- 配置界面修改时立即触发保存
- 解析缓存随配置改动失效

## 使用示例
```java
// 获取渲染 API 实例
OutlineRenderingApi api = OutlineRenderingApi.INSTANCE;

// 解析物品类型
ItemTypeInfo info = api.resolveItemType(itemStack);

// 获取形状检测结果
ShapeResult shape = api.detectShape(info, model);

// 获取最终颜色
int color = api.resolveColor(itemStack, shape);

// 执行渲染
api.render(context, shape, color);
```
