# avaritia-glow-pack(测试资源包)

Enchanted Outlines「资源包自定义轮廓」功能的演示包:为四件下界合金盔甲定义无尽贪婪(Avaritia)风格的程序化流光 —— 五色 keyframe 颜色循环(紫/品红/金/青/荧光绿),5 秒周期,smoothstep 缓动,四件 600ms 错相,整套穿戴时呈现流动彩虹。

## 文件结构

```
pack.mcmeta
assets/enchanted_outlines/outlines/minecraft/
    netherite_helmet.json      (offset 0ms)
    netherite_chestplate.json  (offset 600ms)
    netherite_leggings.json    (offset 1200ms)
    netherite_boots.json       (offset 1800ms)
```

定义文件路径即物品 id:`outlines/<物品命名空间>/<物品路径>.json`。

## 安装

- 开发环境:将本目录整体拷贝到 `run/resourcepacks/`(gradle runClient 客户端),游戏内「选项 - 资源包」启用。
- 正常游戏:拷贝到 `.minecraft/resourcepacks/` 后启用。

## 验收要点

- 描边仍要求物品带附魔(功能语义:资源包定义只改变描边颜色/动画,不解除"有附魔才描边"门禁)——给盔甲打任意附魔后观察。
- 四种场景均应呈现循环流光:物品栏 GUI、盔甲穿戴(第三人称)、手持、掉落物。
- 同套装四件颜色错相 600ms,整体呈流动感;单独拿一件则颜色平滑循环。
- 开/关光影两种模式下均应生效(光影下混色模式对本动画物品自动按纯色处理)。
- 本测试包未写 `thickness` 字段:游戏内调整描边厚度配置(全局或逐物品)应正常生效;若包内某定义写了 `thickness`,该物品厚度会被钉死(优先级高于配置)。
- 修改 JSON 后按 F3+T 重载资源包即可生效,无需重启游戏。

## 字段说明

| 字段 | 说明 |
|------|------|
| `mode` | `static`(单色,用 `color` 字段)或 `cycle`(循环动画,用 `colors` 数组) |
| `colors` | 循环调色板,`#RRGGBB` 或 `#AARRGGBB`(透明度恒被强制为不透明) |
| `period_ms` | 循环周期,范围 [250, 600000],默认 5000 |
| `offset_ms` | 相位偏移,默认 0(多件错相可做流动感) |
| `easing` | `linear`(线性插值)或 `smooth`(smoothstep),默认 linear |
| `thickness` | 逐物品描边厚度(像素),可选;0 表示不描边。**未写时完全跟随游戏内配置**;写了则钉死该物品厚度(优先级:程序化注册 > 资源包定义 > 配置) |

未知字段会被忽略(为二期 UV 流光字段预留兼容)。
