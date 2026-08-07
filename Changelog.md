# Enchanted Outlines Changelog

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
