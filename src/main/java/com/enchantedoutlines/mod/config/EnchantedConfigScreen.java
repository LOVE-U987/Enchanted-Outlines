package com.enchantedoutlines.mod.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import net.neoforged.neoforge.common.ModConfigSpec;

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
    /** defaultColor 行的 hex 输入框(色块入口画在其左侧;null = 当前分类无该行)。 */
    private EditBox colorRowBox;
    /** defaultColor 行绑定的配置值(色块点击弹出色板时回写)。 */
    private ModConfigSpec.ConfigValue<String> colorRowValue;

    // 分类键:general → colors → items → shaders → compat → advanced
    // (2026-11-01 重排:实验性分类删除,RPG 虹灯转正入「颜色与取色」)
    private static final String[] CATEGORIES = {"general", "colors", "items", "shaders", "compat", "advanced"};

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
        colorRowBox = null;
        colorRowValue = null;
        scrollOffset = 0;

        int panelTop = HEADER_HEIGHT;
        int widgetX = this.width - PADDING - WIDGET_WIDTH;

        // 分类导航（侧边栏）- 按功能分组
        addCategoryButton(0, "general");
        addCategoryButton(1, "colors");
        addCategoryButton(2, "items");
        addCategoryButton(3, "shaders");
        addCategoryButton(4, "compat");
        addCategoryButton(5, "advanced");

        // 内容行(2026-11-01 重排:6 分类,全部配置项按语义归位;
        // 实验性分类已删除,RPG 虹灯转正入「颜色与取色」)
        int y = panelTop + 12;
        switch (selectedCategory) {
            case 0 -> { // general 通用
                y = addBooleanRow(y, "enable", Config.ENABLE, widgetX);
                y = addDoubleRow(y, "thickness", Config.THICKNESS, 0.0, 8.0, widgetX);
                y = addDoubleRow(y, "armorThickness", Config.ARMOR_THICKNESS, 0.0, 20.0, widgetX);
                y = addDoubleRow(y, "itemFrameThickness", Config.ITEM_FRAME_THICKNESS, -1.0, 8.0, widgetX);
                y = addDoubleRow(y, "groundThickness", Config.GROUND_THICKNESS, -1.0, 8.0, widgetX);
            }
            case 1 -> { // colors 颜色与取色
                y = addColorRow(y, "defaultColor", Config.DEFAULT_COLOR, widgetX);
                y = addMergeModeRow(y, widgetX);
                y = addLongTextRow(y, "enchantColors", Config.ENCHANT_COLORS, widgetX);
                y = addLongTextRow(y, "itemColors", Config.ITEM_COLORS, widgetX);
                y = addBooleanRow(y, "enchantRarityAutoColor", Config.ENCHANT_RARITY_AUTO_COLOR, widgetX);
                y = addLongTextRow(y, "rarityColors", Config.RARITY_COLORS, widgetX);
                y = addBooleanRow(y, "enableRPGRainbow", Config.ENABLE_RPG_RAINBOW, widgetX);
                y = addBooleanRow(y, "disableVanillaGlint", Config.DISABLE_VANILLA_GLINT, widgetX);
                y = addBooleanRow(y, "pulseEffect", Config.PULSE_EFFECT, widgetX);
                y = addDoubleRow(y, "pulseSpeed", Config.PULSE_SPEED, 0.2, 3.0, widgetX);
            }
            case 2 -> { // items 逐物品规则
                y = addLongTextRow(y, "enabledItems", Config.ENABLED_ITEMS, widgetX);
                y = addLongTextRow(y, "disabledItems", Config.DISABLED_ITEMS, widgetX);
                y = addLongTextRow(y, "itemThicknesses", Config.ITEM_THICKNESSES, widgetX);
                y = addLongTextRow(y, "solidColorItems", Config.SOLID_COLOR_ITEMS, widgetX);
            }
            case 3 -> { // shaders 光影兼容
                y = addBooleanRow(y, "itemPixelColorGlint", Config.ITEM_PIXEL_COLOR_GLINT, widgetX);
                y = addBooleanRow(y, "armorPixelColorGlint", Config.ARMOR_PIXEL_COLOR_GLINT, widgetX);
                y = addBooleanRow(y, "outlineExposureReduce", Config.OUTLINE_EXPOSURE_REDUCE, widgetX);
            }
            case 4 -> { // compat 模组兼容
                y = addBooleanRow(y, "taczCompat", Config.ENABLE_TACZ_COMPAT, widgetX);
                y = addDoubleRow(y, "taczFirstPersonThicknessScale", Config.TACZ_FIRST_PERSON_THICKNESS_SCALE, 0.5, 4.0, widgetX);
                y = addBooleanRow(y, "hazenCompat", Config.ENABLE_HAZEN_COMPAT, widgetX);
                y = addLongTextRow(y, "bewlrNamespaceAdapters", Config.BEWLR_NAMESPACE_ADAPTERS, widgetX);
                y = addBooleanRow(y, "sodiumAnimatedTextureCompat", Config.SODIUM_TEXTURE_COMPAT, widgetX);
            }
            default -> { // advanced 高级(BEWLR 算法与性能)
                y = addDoubleRow(y, "bewlr3dScale", Config.BEWLR_3D_SCALE, 0.05, 1.0, widgetX);
                y = addBooleanRow(y, "bewlr3dPerCube", Config.BEWLR_3D_PER_CUBE, widgetX);
                y = addBooleanRow(y, "bewlrCaptureV2", Config.BEWLR_CAPTURE_V2, widgetX);
                y = addBooleanRow(y, "bewlrGlobalBufferSwap", Config.BEWLR_GLOBAL_BUFFER_SWAP, widgetX);
                y = addIntRow(y, "shapeTextureCacheLimit", Config.SHAPE_TEXTURE_CACHE_LIMIT, 32, 2048, widgetX);
            }
        }

        // 底部完成按钮
        doneButton = Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 32, 100, 20)
                .build();
        this.addRenderableWidget(doneButton);
    }

    private void addCategoryButton(int index, String key) {
        int x = PADDING + 6;
        // 动态步距(2026-11-01,用户反馈:小 GUI 高度下第 6 个分类按钮越出面板底部):
        // 首按钮固定在面板顶部,步距按可用高度压缩 —— 下限 24(留 2px 间隙),上限 30
        // (与旧固定布局一致,大屏布局不变)。
        int top = HEADER_HEIGHT + 10;
        int bottom = this.height - FOOTER_HEIGHT - 10;
        int step = Math.max(24, Math.min(30, (bottom - top - 22) / (CATEGORIES.length - 1)));
        int y = top + index * step;
        int w = SIDEBAR_WIDTH - 12;
        CategoryButton button = new CategoryButton(selectedCategory == index, x, y, w, 22,
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

    private int addBooleanRow(int y, String key, ModConfigSpec.BooleanValue value, int widgetX) {
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

    private int addDoubleRow(int y, String key, ModConfigSpec.DoubleValue value, double min, double max, int widgetX) {
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

    /**
     * 整数输入行(EditBox,范围裁剪,立即写盘)。
     *
     * @param y       行顶 y
     * @param key     配置键(语言键前缀)
     * @param value   整型配置值
     * @param min     允许最小值(越界裁剪)
     * @param max     允许最大值(越界裁剪)
     * @param widgetX 控件 x
     * @return 下一行 y
     */
    private int addIntRow(int y, String key, ModConfigSpec.IntValue value, int min, int max, int widgetX) {
        EditBox box = new EditBox(this.font, widgetX, y + 4, WIDGET_WIDTH, 20, Component.empty());
        // 整数输入上限:范围 32-2048 最长 4 位,限制后可防止粘贴超长字符串
        box.setMaxLength(5);
        box.setResponder(s -> {
            try {
                int v = Integer.parseInt(s.trim());
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

    private int addColorRow(int y, String key, ModConfigSpec.ConfigValue<String> value, int widgetX) {
        // 输入框右移 32px,左侧留出色块入口(render/mouseClicked 按行位置同步绘制,2026-11-01)
        EditBox box = new EditBox(this.font, widgetX + 32, y + 4, WIDGET_WIDTH - 32, 20, Component.empty());
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
        this.colorRowBox = box;
        this.colorRowValue = value;
        registerRow(key, box);
        return y + ROW_HEIGHT;
    }

    private int addLongTextRow(int y, String key, ModConfigSpec.ConfigValue<String> value, int widgetX) {
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
    protected void renderBlurredBackground(float partialTick) {
        // 禁用模糊背景，避免文字糊化
    }

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
        // defaultColor 行色块入口(2026-11-01):画在行 EditBox 左侧 28px,点击弹出色板
        if (colorRowBox != null && colorRowBox.visible) {
            int sx = colorRowBox.getX() - 32;
            int sy = colorRowBox.getY();
            Integer rgb = Config.parseHex(colorRowValue.get());
            gui.fill(sx, sy, sx + 28, sy + 20, 0xFF000000 | (rgb != null ? rgb : 0x55FFFF));
            boolean hover = mouseX >= sx && mouseX < sx + 28 && mouseY >= sy && mouseY < sy + 20;
            gui.renderOutline(sx - 1, sy - 1, 30, 22, hover ? 0xFFFFFFFF : 0xFF2A2F38);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int panelTop = HEADER_HEIGHT;
        int panelBottom = this.height - FOOTER_HEIGHT;
        int visible = panelBottom - panelTop - 12;
        int maxScroll = Math.max(0, rows.size() * ROW_HEIGHT - visible);
        scrollOffset = (int) Mth.clamp(scrollOffset - scrollY * 12, 0, maxScroll);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // defaultColor 行色块入口:点击弹出色板选择器
        if (colorRowBox != null && colorRowBox.visible
                && mouseX >= colorRowBox.getX() - 32 && mouseX < colorRowBox.getX() - 4
                && mouseY >= colorRowBox.getY() && mouseY < colorRowBox.getY() + 20) {
            this.minecraft.setScreen(new ColorSwatchScreen(this, colorRowValue.get(), newValue -> {
                colorRowValue.set(newValue);
                markChanged();
            }));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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

        CategoryButton(boolean selected, int x, int y, int w, int h,
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
        private MultiLineEditBox box;

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
            // 多行可滚动编辑(2026-11-01,用户反馈:长配置单行编辑无法纵览、tooltip 溢出)。
            // MultiLineEditBox 自带纵向滚动条;无 maxLength 限制(原单行版需 16384 上限),直接 setValue。
            box = new MultiLineEditBox(this.font, this.width / 2 - 200, this.height / 2 - 12, 400, 88,
                    Component.empty(), Component.empty());
            box.setValue(initial);
            this.addRenderableWidget(box);

            this.addRenderableWidget(Button.builder(
                            Component.translatable(PREFIX + ".save"), b -> {
                                onSave.accept(box.getValue());
                                onClose();
                            })
                    .bounds(this.width / 2 - 100, this.height / 2 + 84, 90, 20)
                    .build());
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(this.width / 2 + 10, this.height / 2 + 84, 90, 20)
                    .build());
        }

        @Override
        protected void renderBlurredBackground(float partialTick) {
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            gui.fill(0, 0, this.width, this.height, 0xFF0A0D12);
            gui.fill(this.width / 2 - 220, this.height / 2 - 110, this.width / 2 + 220, this.height / 2 + 112, 0xFF10141A);
            gui.fill(this.width / 2 - 220, this.height / 2 - 110, this.width / 2 + 220, this.height / 2 - 109, ACCENT);
            gui.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 100, LABEL);
            // tooltip 分行绘制(2026-11-01,用户反馈:长 tooltip 单行居中会溢出面板被裁切)。
            // font.split 按面板内宽换行,最多 6 行,超出以省略号截断(完整说明看主界面悬停 tooltip)。
            List<FormattedCharSequence> tipLines =
                    this.font.split(Component.translatable(this.tooltipKey), 400);
            int ty = this.height / 2 - 86;
            for (int i = 0; i < tipLines.size() && i < 6; i++) {
                gui.drawCenteredString(this.font, tipLines.get(i), this.width / 2, ty, HINT);
                ty += 10;
            }
            if (tipLines.size() > 6) {
                gui.drawCenteredString(this.font, Component.literal("..."), this.width / 2, ty, HINT);
            }
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

    // ==================== 色板选择器(2026-11-01) ====================

    /**
     * defaultColor 行的色板选择器。
     * <p>
     * 预设 5x5 色块网格 + 当前色预览 + RRGGBB 输入框三向同步:点击色块或输入合法
     * hex 都会更新 current,保存时回调外层写盘。布局与 {@link LongTextEditScreen}
     * 同款二级屏幕风格;面板高度按屏幕自适应(小 GUI 缩放下压缩不越界)。
     */
    private static final class ColorSwatchScreen extends Screen {

        /** 网格单元尺寸(像素)。 */
        private static final int CELL = 20;
        /** 网格单元间距(像素)。 */
        private static final int GAP = 4;
        /** 网格列数。 */
        private static final int COLS = 5;

        /**
         * 预设色板(5x5,描边常用高对比色):灰阶 / 彩虹亮色 / 彩虹深色 / 蓝紫粉 / 金属大地。
         */
        private static final int[] SWATCH_COLORS = {
                0xFFFFFF, 0xBFBFBF, 0x808080, 0x404040, 0x14141A,
                0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x55FFFF,
                0xAA0000, 0xAA5500, 0xAAAA00, 0x00AA00, 0x00AAAA,
                0x5555FF, 0xAA00AA, 0xFF55FF, 0xF0F8FF, 0xFFC0CB,
                0xFFD700, 0xB87333, 0xEDC9AF, 0x87CEEB, 0x9ACD32,
        };

        private final Screen parent;
        private final Consumer<String> onSave;
        /** 当前选中色(RRGGBB,无 # 前缀,大写)。 */
        private String current;
        private EditBox hexBox;

        /**
         * @param parent  返回目标屏幕(外层配置界面)
         * @param initial 初始颜色(可为 #RRGGBB 或 RRGGBB;非法时回退默认粉)
         * @param onSave  保存回调(接收 RRGGBB,外层负责写盘与缓存失效)
         */
        ColorSwatchScreen(Screen parent, String initial, Consumer<String> onSave) {
            super(Component.translatable(PREFIX + ".colorSwatch.title"));
            this.parent = parent;
            this.onSave = onSave;
            String t = initial == null ? "" : initial.trim();
            if (t.startsWith("#")) {
                t = t.substring(1);
            }
            this.current = Config.parseHex(t) >= 0 ? t.toUpperCase(Locale.ROOT) : "FFC0CB";
        }

        /** 面板高度(小 GUI 缩放下压缩,下限 150)。 */
        private int panelHeight() {
            return Math.min(216, Math.max(150, this.height - 30));
        }

        /** 面板顶 y(垂直居中)。 */
        private int panelTop() {
            return (this.height - panelHeight()) / 2;
        }

        /** 色块网格左上角 x(面板内水平居中)。 */
        private int gridLeft() {
            return this.width / 2 - (COLS * CELL + (COLS - 1) * GAP) / 2;
        }

        @Override
        protected void init() {
            int bottom = panelTop() + panelHeight();
            hexBox = new EditBox(this.font, this.width / 2 - 60, bottom - 58, 120, 20, Component.empty());
            // 颜色最长 #RRGGBB 共 7 位,限制后多余的字符无法输入
            hexBox.setMaxLength(7);
            hexBox.setResponder(s -> {
                String t = s.trim();
                if (Config.parseHex(t) >= 0) {
                    current = (t.startsWith("#") ? t.substring(1) : t).toUpperCase(Locale.ROOT);
                }
            });
            hexBox.setValue("#" + current);
            this.addRenderableWidget(hexBox);

            this.addRenderableWidget(Button.builder(
                            Component.translatable(PREFIX + ".colorSwatch.save"), b -> save())
                    .bounds(this.width / 2 - 100, bottom - 32, 90, 20).build());
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(this.width / 2 + 10, bottom - 32, 90, 20).build());
        }

        /** 保存当前颜色并返回(触发外层 markChanged 写盘)。 */
        private void save() {
            onSave.accept(current);
            onClose();
        }

        @Override
        protected void renderBlurredBackground(float partialTick) {
        }

        @Override
        public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            gui.fill(0, 0, this.width, this.height, 0xFF0A0D12);
            int top = panelTop();
            int bottom = top + panelHeight();
            int left = this.width / 2 - 170;
            int right = this.width / 2 + 170;
            gui.fill(left, top, right, bottom, 0xFF10141A);
            gui.fill(left, top, right, top + 1, ACCENT);
            gui.drawCenteredString(this.font, this.title, this.width / 2, top + 8, LABEL);
            gui.drawCenteredString(this.font, Component.translatable(PREFIX + ".colorSwatch.hint"),
                    this.width / 2, top + 22, HINT);

            // 色块网格(选中色白框,悬停灰框)
            int gx = gridLeft();
            int gy = top + 36;
            for (int i = 0; i < SWATCH_COLORS.length; i++) {
                int cx = gx + (i % COLS) * (CELL + GAP);
                int cy = gy + (i / COLS) * (CELL + GAP);
                String hex = String.format(Locale.ROOT, "%06X", SWATCH_COLORS[i]);
                boolean selected = hex.equalsIgnoreCase(current);
                boolean hovered = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
                gui.fill(cx, cy, cx + CELL, cy + CELL, 0xFF000000 | SWATCH_COLORS[i]);
                if (selected || hovered) {
                    gui.renderOutline(cx - 1, cy - 1, CELL + 2, CELL + 2,
                            selected ? 0xFFFFFFFF : 0xFF8A8F98);
                }
            }

            // 当前色预览(hexBox 左侧)
            Integer preview = Config.parseHex(current);
            if (preview != null) {
                gui.fill(this.width / 2 - 100, bottom - 58, this.width / 2 - 66, bottom - 38,
                        0xFF000000 | preview);
                gui.renderOutline(this.width / 2 - 101, bottom - 59, 36, 22,
                        0xFF2A2F38);
            }
            super.render(gui, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int gx = gridLeft();
            int gy = panelTop() + 36;
            for (int i = 0; i < SWATCH_COLORS.length; i++) {
                int cx = gx + (i % COLS) * (CELL + GAP);
                int cy = gy + (i / COLS) * (CELL + GAP);
                if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                    current = String.format(Locale.ROOT, "%06X", SWATCH_COLORS[i]);
                    hexBox.setValue("#" + current);
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                save();
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
