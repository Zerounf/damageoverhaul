package com.zerochen.damageoverhaul.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 内部属性面板（默认 H 键打开）。
 * 纯展示：9 项能力的等级、有效点数、本级经验进度条；
 * more_attributes 接管（休眠）或功能禁用时显示对应提示。
 * 数据全部来自 InternalAttributesClient 同步缓存。
 */
public class AttributesScreen extends Screen {

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 248;
    private static final int HEADER_H = 38;
    private static final int ROW_H = 22;
    private static final int ROWS = 9;

    private static final int COLOR_PANEL_BG = 0xF0101014;
    private static final int COLOR_PANEL_BORDER = 0xFF4A4A55;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_SUBTITLE = 0xFFB0B0C0;
    private static final int COLOR_NAME = 0xFFE8E8F0;
    private static final int COLOR_POINTS = 0xFF9AA0FF;
    private static final int COLOR_BAR_BG = 0xFF22222A;
    private static final int COLOR_BAR_FILL = 0xFF80FF20;
    private static final int COLOR_BAR_TEXT = 0xFF101010;
    private static final int COLOR_MAX = 0xFFFFD54A;

    private int left;
    private int top;

    public AttributesScreen() {
        super(Component.translatable("screen.damageoverhaul.attributes_title"));
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        // 面板背景 + 边框
        g.fill(left, top, left + PANEL_W, top + PANEL_H, COLOR_PANEL_BG);
        g.fill(left, top, left + PANEL_W, top + 1, COLOR_PANEL_BORDER);
        g.fill(left, top + PANEL_H - 1, left + PANEL_W, top + PANEL_H, COLOR_PANEL_BORDER);
        g.fill(left, top, left + 1, top + PANEL_H, COLOR_PANEL_BORDER);
        g.fill(left + PANEL_W - 1, top, left + PANEL_W, top + PANEL_H, COLOR_PANEL_BORDER);

        g.drawCenteredString(this.font, this.title, left + PANEL_W / 2, top + 12, COLOR_TITLE);

        int state = InternalAttributesClient.getState();
        if (state == InternalAttributesClient.STATE_SLEEPING) {
            drawStateMessage(g,
                    Component.translatable("screen.damageoverhaul.sleeping_1"),
                    Component.translatable("screen.damageoverhaul.sleeping_2"));
            return;
        }
        if (state == InternalAttributesClient.STATE_DISABLED) {
            drawStateMessage(g,
                    Component.translatable("screen.damageoverhaul.disabled_1"),
                    Component.translatable("screen.damageoverhaul.disabled_2"));
            return;
        }

        g.drawCenteredString(this.font, Component.translatable("screen.damageoverhaul.attributes_subtitle"),
                left + PANEL_W / 2, top + 26, COLOR_SUBTITLE);

        List<String> names = InternalAttributesClient.getNames();
        int rowsTop = top + HEADER_H;
        for (int i = 0; i < names.size() && i < ROWS; i++) {
            renderRow(g, names.get(i), rowsTop + i * ROW_H);
        }
    }

    private void renderRow(GuiGraphics g, String name, int rowTop) {
        int contentLeft = left + 12;
        int contentRight = left + PANEL_W - 12;

        // 第一行：能力名（左） + Lv / 有效点数（右）
        int level = InternalAttributesClient.getLevel(name);
        int base = InternalAttributesClient.getBaseLevel(name);
        int effective = Math.max(0, level - base);

        g.drawString(this.font, Component.translatable("attribute.damageoverhaul." + name),
                contentLeft, rowTop + 2, COLOR_NAME, false);

        Component levelComp = Component.translatable("screen.damageoverhaul.attributes_level", level);
        int levelW = this.font.width(levelComp);
        String pointsText = " (+" + effective + ")";
        int pointsW = this.font.width(pointsText);
        int rightX = contentRight;
        g.drawString(this.font, pointsText, rightX - pointsW, rowTop + 2, COLOR_POINTS, false);
        g.drawString(this.font, levelComp, rightX - pointsW - levelW, rowTop + 2, COLOR_NAME, false);

        // 第二行：经验条（全宽）
        int barTop = rowTop + 12;
        int barH = 9;
        g.fill(contentLeft, barTop, contentRight, barTop + barH, COLOR_BAR_BG);

        int max = InternalAttributesClient.getMaxLevel(name);
        if (level >= max) {
            g.fill(contentLeft, barTop, contentRight, barTop + barH, COLOR_MAX);
            g.drawCenteredString(this.font, Component.translatable("screen.damageoverhaul.attributes_max"),
                    (contentLeft + contentRight) / 2, barTop + 1, COLOR_BAR_TEXT);
        } else {
            double cur = InternalAttributesClient.getXp(name);
            double required = InternalAttributesClient.xpRequired(name, level);
            float ratio = required > 0 ? (float) Math.max(0.0, Math.min(1.0, cur / required)) : 0f;
            int fillW = Math.round((contentRight - contentLeft) * ratio);
            if (fillW > 0) g.fill(contentLeft, barTop, contentLeft + fillW, barTop + barH, COLOR_BAR_FILL);

            Component xpComp = Component.translatable("screen.damageoverhaul.attributes_xp",
                    (long) Math.floor(cur), (long) Math.ceil(required));
            g.drawCenteredString(this.font, xpComp, (contentLeft + contentRight) / 2, barTop + 1, 0xFFFFFFFF);
        }
    }

    private void drawStateMessage(GuiGraphics g, Component line1, Component line2) {
        int cx = left + PANEL_W / 2;
        int cy = top + PANEL_H / 2;
        g.drawCenteredString(this.font, line1, cx, cy - 14, COLOR_SUBTITLE);
        g.drawCenteredString(this.font, line2, cx, cy + 6, COLOR_SUBTITLE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
