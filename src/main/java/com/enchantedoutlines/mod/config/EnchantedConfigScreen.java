package com.enchantedoutlines.mod.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import net.minecraftforge.common.ForgeConfigSpec;

import org.lwjgl.glfw.GLFW;

/**
 * Enchanted Outlines 配置界面（原生 Screen）。
 * <p>
 * 深色、低饱和、单强调色（琥珀金）的「去 AI 化」主题：无紫色渐变、无过度光晕。
 * 左侧分类导航 + 右侧滚动内容区 + 底部操作栏，入场动画克制（淡入 + 轻微上移）。
 * 每次修改立即写盘（{@link Config#save()}）。
 */
public class EnchantedConfigScreen extends Screen {

    private static final String PREFIX = "enchanted_outlines.config";

    // ==================== 布局 ====================
    private static final int HEADER_HEIGHT = 46;
    private static final int FOOTER_HEIGHT = 50;
    private static final int PADDING = 18;
    private static final int SIDEBAR_WIDTH = 96;
    private static final int CONTENT_LEFT = SIDEBAR_WIDTH + PADDING + 10;
    private static final int ROW_HEIGHT = 36;
    private static final int WIDGET_WIDTH = 150;

    // ==================== 主题：深海暗色 + 琥珀金 ====================
    private static final int BG = 0xFF0A0D12;
    private static final int PANEL = 0xFF10141A;
    private static final int PANEL_TOP_LINE = 0xFF1C212B;
    private static final int DIVIDER = 0xFF1F252D;
    private static final int TITLE = 0xFFE8ECEF;
    private static final int LABEL = 0xFFC8CDD4;
    private static final int HINT = 0xFF6E7682;
    private static final int VALUE = 0xFF95A0AC;
    private static final int ACCENT = 0xFFE0A54F;
    private static final int ACCENT_DARK = 0xFFB07F2E;
    private static final int CATEGORY_SELECTED_BG = 0xFF1A1E26;
    private static final int CATEGORY_HOVER_BG = 0xFF141922;
    private static final int SUCCESS = 0xFF5ECF9A;
    private static final int SCROLLBAR_TRACK = 0x10FFFFFF;
    private static final int SCROLLBAR_THUMB = 0x55E0A54F;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final List<AbstractWidget> configWidgets = new ArrayList<>();
    private final List<CategoryButton> categoryButtons = new ArrayList<>();

    private int selectedCategory = 0;
    private int scrollOffset = 0;
    private Button doneButton;
    private float savedFlash = 0f;
    private long openTime;

    public EnchantedConfigScreen(Screen parent) {
        super(Component.translatable(PREFIX + ".title"));
        this.parent = parent;
        this.openTime = System.currentTimeMillis();
    }

    private record Row(String labelKey, AbstractWidget widget) {
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        rows.clear();
        configWidgets.clear();
        categoryButtons.clear();
        scrollOffset = 0;

        int panelTop = HEADER_HEIGHT;
        int widgetX = this.width - PADDING - WIDGET_WIDTH;

        // 分类导航（侧边栏）
        addCategoryButton(0, "general");
        addCategoryButton(1, "colors");

        // 内容行
        int y = panelTop + 12;
        if (selectedCategory == 0) {
            y = addBooleanRow(y, "enable", Config.ENABLE, widgetX);
            y = addDoubleRow(y, "thickness", Config.THICKNESS, 0.0, 8.0, widgetX);
            y = addDoubleRow(y, "armorThickness", Config.ARMOR_THICKNESS, 0.0, 8.0, widgetX);
            y = addBooleanRow(y, "itemPixelColorGlint", Config.ITEM_PIXEL_COLOR_GLINT, widgetX);
            y = addBooleanRow(y, "armorPixelColorGlint", Config.ARMOR_PIXEL_COLOR_GLINT, widgetX);
            y = addBooleanRow(y, "armorUniformExpand", Config.ARMOR_UNIFORM_EXPAND, widgetX);
            y = addBooleanRow(y, "outlineExposureReduce", Config.OUTLINE_EXPOSURE_REDUCE, widgetX);
            y = addDoubleRow(y, "bewlr3dScale", Config.BEWLR_3D_SCALE, 0.05, 1.0, widgetX);
            y = addBooleanRow(y, "bewlr3dPerCube", Config.BEWLR_3D_PER_CUBE, widgetX);
            y = addMergeModeRow(y, widgetX);
        } else {
            y = addColorRow(y, "defaultColor", Config.DEFAULT_COLOR, widgetX);
            y = addLongTextRow(y, "enchantColors", Config.ENCHANT_COLORS, widgetX);
            y = addLongTextRow(y, "itemColors", Config.ITEM_COLORS, widgetX);
            y = addLongTextRow(y, "disabledItems", Config.DISABLED_ITEMS, widgetX);
        }

        // 底部完成按钮
        doneButton = Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 32, 100, 20)
                .build();
        this.addRenderableWidget(doneButton);
    }

    private void addCategoryButton(int index, String key) {
        int x = PADDING + 6;
        int y = HEADER_HEIGHT + 10 + index * 30;
        int w = SIDEBAR_WIDTH - 12;
        CategoryButton button = new CategoryButton(index, selectedCategory == index, x, y, w, 22,
                Component.translatable(PREFIX + ".category." + key), b -> {
                    if (index != selectedCategory) {
                        selectedCategory = index;
                        openTime = System.currentTimeMillis();
                        init();
                    }
                });
        categoryButtons.add(button);
        this.addRenderableWidget(button);
    }

    private int addBooleanRow(int y, String key, ForgeConfigSpec.BooleanValue value, int widgetX) {
        CycleButton<Boolean> button = CycleButton.booleanBuilder(
                Component.translatable(PREFIX + ".value.on"),
                Component.translatable(PREFIX + ".value.off"))
                .displayOnlyValue()
                .withInitialValue(value.get())
                .create(widgetX, y + 4, WIDGET_WIDTH, 20, Component.empty(), (b, v) -> {
                    value.set(v);
                    markChanged();
                });
        registerRow(key, button);
        return y + ROW_HEIGHT;
    }

    private int addDoubleRow(int y, String key, ForgeConfigSpec.DoubleValue value, double min, double max, int widgetX) {
        EditBox box = new EditBox(this.font, widgetX, y + 4, WIDGET_WIDTH, 20, Component.empty());
        // 数值输入上限:范围 0-8 的小数最长约 10 位,限制后可防止粘贴超长字符串
        box.setMaxLength(10);
        box.setResponder(s -> {
            try {
                double v = Double.parseDouble(s.trim());
                v = Math.max(min, Math.min(max, v));
                if (v != value.get()) {
                    value.set(v);
                    markChanged();
                }
            } catch (NumberFormatException ignored) {
            }
        });
        box.setValue(String.valueOf(value.get()));
        registerRow(key, box);
        return y + ROW_HEIGHT;
    }

    private int addMergeModeRow(int y, int widgetX) {
        CycleButton<String> button = CycleButton.<String>builder(v -> Component.translatable(PREFIX + ".mergeMode." + v))
                .withValues(List.of("highest", "first"))
                .displayOnlyValue()
                .withInitialValue(Config.MERGE_MODE.get())
                .create(widgetX, y + 4, WIDGET_WIDTH, 20, Component.empty(), (b, v) -> {
                    Config.MERGE_MODE.set(v);
                    markChanged();
                });
        registerRow("mergeMode", button);
        return y + ROW_HEIGHT;
    }

    private int addColorRow(int y, String key, ForgeConfigSpec.ConfigValue<String> value, int widgetX) {
        EditBox box = new EditBox(this.font, widgetX, y + 4, WIDGET_WIDTH, 20, Component.empty());
        // 颜色最长 #RRGGBB 共 7 位,限制后多余的字符无法输入
        box.setMaxLength(7);
        box.setResponder(s -> {
            String t = s.trim();
            if (Config.parseHex(t) >= 0 && !t.equalsIgnoreCase(value.get())) {
                value.set(t);
                markChanged();
            }
        });
        box.setValue(value.get());
        registerRow(key, box);
        return y + ROW_HEIGHT;
    }

    private int addLongTextRow(int y, String key, ForgeConfigSpec.ConfigValue<String> value, int widgetX) {
        Button button = Button.builder(Component.literal(truncatePreview(value.get())), b -> {
            this.minecraft.setScreen(new LongTextEditScreen(this,
                    Component.translatable(PREFIX + "." + key),
                    PREFIX + "." + key + ".tooltip", value.get(), newValue -> {
                        value.set(newValue);
                        markChanged();
                        b.setMessage(Component.literal(truncatePreview(newValue)));
                    }));
        }).bounds(widgetX, y + 4, WIDGET_WIDTH, 20).build();
        registerRow(key, button);
        return y + ROW_HEIGHT;
    }

    private void registerRow(String key, AbstractWidget widget) {
        widget.setTooltip(Tooltip.create(Component.translatable(PREFIX + "." + key + ".tooltip")));
        configWidgets.add(widget);
        rows.add(new Row(PREFIX + "." + key, widget));
        this.addRenderableWidget(widget);
    }

    private static String truncatePreview(String text) {
        if (text == null || text.isEmpty()) {
            return "...";
        }
        return text.length() > 24 ? text.substring(0, 24) + "..." : text;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        float progress = openProgress();
        int alpha = (int) (progress * 255);
        int animY = (int) ((1f - progress) * 10f);

        int panelTop = HEADER_HEIGHT;
        int panelBottom = this.height - FOOTER_HEIGHT;

        // 背景
        gui.fill(0, 0, this.width, this.height, BG);

        // 顶部：强调线 + 标题 + 副标题
        gui.fill(PADDING, panelTop - 2, this.width - PADDING, panelTop - 1, ACCENT);
        gui.drawString(this.font, this.title, PADDING + 2, 14 + animY, (alpha << 24) | (TITLE & 0xFFFFFF));
        gui.drawString(this.font, Component.translatable(PREFIX + ".subtitle"),
                PADDING + 2, 27 + animY, (alpha << 24) | (HINT & 0xFFFFFF));

        // 面板
        gui.fill(PADDING, panelTop, this.width - PADDING, panelBottom, PANEL);
        gui.fill(PADDING, panelTop, this.width - PADDING, panelTop + 1, PANEL_TOP_LINE);
        // 侧边栏分隔线
        gui.fill(CONTENT_LEFT - 8, panelTop, CONTENT_LEFT - 6, panelBottom, DIVIDER);

        // 侧边栏分类按钮（scissor 外）
        for (CategoryButton b : categoryButtons) {
            b.render(gui, mouseX, mouseY, partialTick);
        }

        // 内容区（裁剪 + 滚动）
        gui.enableScissor(CONTENT_LEFT, panelTop + 6, this.width - PADDING, panelBottom - 6);
        updateWidgetPositions(scrollOffset, animY);

        int cy = panelTop + 12 - scrollOffset + animY;
        for (Row row : rows) {
            gui.drawString(this.font, Component.translatable(row.labelKey()),
                    CONTENT_LEFT, cy + 8, (alpha << 24) | (LABEL & 0xFFFFFF));
            cy += ROW_HEIGHT;
        }
        for (AbstractWidget w : configWidgets) {
            if (w.visible) {
                w.render(gui, mouseX, mouseY, partialTick);
            }
        }
        gui.disableScissor();

        renderScrollbar(gui, panelTop, panelBottom);

        // 底部反馈
        if (savedFlash > 0.01f) {
            int flash = (int) (savedFlash * 255);
            gui.drawCenteredString(this.font, Component.translatable(PREFIX + ".saved"),
                    this.width / 2, this.height - 24, (flash << 24) | (SUCCESS & 0xFFFFFF));
        } else {
            gui.drawCenteredString(this.font, Component.translatable(PREFIX + ".scroll_hint"),
                    this.width / 2, this.height - 24, (alpha << 24) | (HINT & 0xFFFFFF));
        }

        doneButton.render(gui, mouseX, mouseY, partialTick);
    }

    private void updateWidgetPositions(int scroll, int animY) {
        int panelTop = HEADER_HEIGHT;
        int panelBottom = this.height - FOOTER_HEIGHT;
        int y = panelTop + 12 - scroll + animY;
        for (int i = 0; i < configWidgets.size(); i++) {
            AbstractWidget w = configWidgets.get(i);
            w.setY(y + 6);
            w.visible = y + ROW_HEIGHT > panelTop && y < panelBottom;
            y += ROW_HEIGHT;
        }
    }

    private void renderScrollbar(GuiGraphics gui, int panelTop, int panelBottom) {
        int contentH = rows.size() * ROW_HEIGHT;
        int visible = panelBottom - panelTop - 12;
        if (contentH <= visible) {
            return;
        }
        int trackX = this.width - PADDING - 4;
        int trackTop = panelTop + 8;
        int trackH = panelBottom - 8 - trackTop;
        int thumbH = Math.max(18, trackH * visible / contentH);
        int maxScroll = contentH - visible;
        int thumbY = trackTop + (trackH - thumbH) * scrollOffset / Math.max(1, maxScroll);
        gui.fill(trackX, trackTop, trackX + 2, trackTop + trackH, SCROLLBAR_TRACK);
        gui.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, SCROLLBAR_THUMB);
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int panelTop = HEADER_HEIGHT;
        int panelBottom = this.height - FOOTER_HEIGHT;
        int visible = panelBottom - panelTop - 12;
        int maxScroll = Math.max(0, rows.size() * ROW_HEIGHT - visible);
        // 1.20.1 的 mouseScrolled 只有 3 参(无水平滚动参数,那是 1.20.2+)
        scrollOffset = (int) Mth.clamp(scrollOffset - amount * 12, 0, maxScroll);
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        savedFlash = Math.max(0f, savedFlash - 0.06f);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private void markChanged() {
        Config.save();
        // 解析缓存(默认色/逐附魔色/逐物品色/禁用列表)只在 ModConfigEvent 时失效,
        // 配置界面修改不会触发该事件 → 必须手动失效,否则改动不生效直到重启/F3+T。
        Config.invalidateCache();
        savedFlash = 1f;
    }

    private float openProgress() {
        long elapsed = System.currentTimeMillis() - openTime;
        float t = Math.min(1f, elapsed / 240f);
        return 1f - (1f - t) * (1f - t) * (1f - t); // easeOutCubic
    }

    // ==================== 分类按钮（自绘） ====================

    private final class CategoryButton extends Button {
        private final boolean selected;

        CategoryButton(int index, boolean selected, int x, int y, int w, int h,
                       Component message, OnPress onPress) {
            super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
            this.selected = selected;
        }

        @Override
        protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            int bg;
            if (selected) {
                bg = CATEGORY_SELECTED_BG;
            } else if (this.isHovered()) {
                bg = CATEGORY_HOVER_BG;
            } else {
                bg = 0xFF10141A;
            }
            gui.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            if (selected) {
                gui.fill(getX(), getY() + 3, getX() + 2, getY() + getHeight() - 3, ACCENT);
            }
            int color = selected ? ACCENT : (this.isHovered() ? TITLE : VALUE);
            gui.drawCenteredString(font, getMessage(), getX() + getWidth() / 2,
                    getY() + (getHeight() - 8) / 2, color);
        }
    }

    // ==================== 长文本二级编辑 ====================

    private static final class LongTextEditScreen extends Screen {

        private final Screen parent;
        private final Component title;
        private final String tooltipKey;
        private final String initial;
        private final Consumer<String> onSave;
        private EditBox box;

        LongTextEditScreen(Screen parent, Component title, String tooltipKey, String initial, Consumer<String> onSave) {
            super(title);
            this.parent = parent;
            this.title = title;
            this.tooltipKey = tooltipKey;
            this.initial = initial;
            this.onSave = onSave;
        }

        @Override
        protected void init() {
            box = new EditBox(this.font, this.width / 2 - 200, this.height / 2 - 34, 400, 20, Component.empty());
            // 必须先设上限再赋值:EditBox#setValue 会按当前 maxLength(默认 32)截断字符串,
            // 若顺序颠倒,超长配置(如默认 enchantColors 远超 32 字符)在打开编辑框时就会被截断,保存即破坏配置。
            box.setMaxLength(16384);
            box.setValue(initial);
            this.addRenderableWidget(box);

            this.addRenderableWidget(Button.builder(
                            Component.translatable(PREFIX + ".save"), b -> {
                                onSave.accept(box.getValue());
                                onClose();
                            })
                    .bounds(this.width / 2 - 100, this.height / 2 + 26, 90, 20)
                    .build());
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(this.width / 2 + 10, this.height / 2 + 26, 90, 20)
                    .build());
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            gui.fill(0, 0, this.width, this.height, 0xFF0A0D12);
            gui.fill(this.width / 2 - 220, this.height / 2 - 74, this.width / 2 + 220, this.height / 2 + 74, 0xFF10141A);
            gui.fill(this.width / 2 - 220, this.height / 2 - 74, this.width / 2 + 220, this.height / 2 - 73, ACCENT);
            gui.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 64, LABEL);
            gui.drawCenteredString(this.font, Component.translatable(this.tooltipKey),
                    this.width / 2, this.height / 2 - 10, HINT);
            super.render(gui, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                onSave.accept(box.getValue());
                onClose();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void onClose() {
            this.minecraft.setScreen(parent);
        }
    }
}
