package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.ChatFilterConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Shared UI drawing utilities for all Columba screens.
 * Eliminates code duplication across 7+ screen classes.
 */
public final class UIHelper {

    private UIHelper() {}

    // ── Theme loading ────────────────────────────────────────────────────────

    public static ThemeColors loadColors() {
        String name = ChatFilterConfig.getThemeName();
        if ("CUSTOM".equals(name)) {
            return new ThemeColors(
                    ChatFilterConfig.getCustomAccentR(),
                    ChatFilterConfig.getCustomAccentG(),
                    ChatFilterConfig.getCustomAccentB());
        }
        try { return new ThemeColors(UITheme.valueOf(name)); }
        catch (Exception e) { return new ThemeColors(UITheme.OCEAN); }
    }

    // ── Drawing primitives ───────────────────────────────────────────────────

    /** Draw a 2px border around a rectangle. */
    public static void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 2, color);
        ctx.fill(x, y + h - 2, x + w, y + h, color);
        ctx.fill(x, y, x + 2, y + h, color);
        ctx.fill(x + w - 2, y, x + w, y + h, color);
    }

    /** Draw a 1px border (for smaller elements like previews). */
    public static void drawBorder1(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Draw the standard accent stripe at top of a panel (3px + glow). */
    public static void drawAccentStripe(DrawContext ctx, int px, int py, int panelW, ThemeColors t) {
        ctx.fill(px + 2, py + 2, px + panelW - 2, py + 4, t.accent);
        ctx.fill(px + 2, py + 4, px + panelW - 2, py + 5, t.accentGlow);
    }

    /** Draw a standard panel header area with title. */
    public static void drawPanelHeader(DrawContext ctx, TextRenderer tr, int px, int py, int panelW,
                                        int headerBottom, String title, ThemeColors t) {
        ctx.fill(px + 2, py + 5, px + panelW - 2, headerBottom, t.panelHead);
        ctx.fill(px + 2, headerBottom - 1, px + panelW - 2, headerBottom, t.accentGlow);
        ctx.drawCenteredTextWithShadow(tr, Text.literal(title),
                px + panelW / 2, py + 12, t.accent);
    }

    /** Draw a section header bar (colored label with subtle background). */
    public static void drawSectionHeader(DrawContext ctx, TextRenderer tr, int x, int y, int w,
                                          String label, int accentColor, ThemeColors t) {
        ctx.fill(x, y, x + w, y + 14, mixA(accentColor, 0x18000000));
        ctx.fill(x, y, x + 2, y + 14, accentColor);
        ctx.drawTextWithShadow(tr, Text.literal(label), x + 6, y + 3, t.subtext);
    }

    /** Draw feedback text with fade-out alpha. */
    public static void drawFeedback(DrawContext ctx, TextRenderer tr, String text, boolean isOk,
                                     int ticks, int cx, int y) {
        if (text == null || ticks <= 0) return;
        float alpha = Math.min(1f, ticks / 20f);
        int a = ((int)(alpha * 255) & 0xFF) << 24;
        int col = (isOk ? 0x55FF99 : 0xFF5555) | a;
        ctx.drawCenteredTextWithShadow(tr, Text.literal(text), cx, y, col);
    }

    /** Draw a standard scrollbar (4px wide). */
    public static void drawScrollbar(DrawContext ctx, int px, int panelW, int listTop, int listBottom,
                                      int scrollOffset, int totalItems, int visibleItems, int accentColor) {
        if (totalItems <= visibleItems) return;
        int barH = listBottom - listTop;
        int thumbH = Math.max(16, barH * visibleItems / totalItems);
        int thumbY = listTop + scrollOffset * (barH - thumbH) / Math.max(1, totalItems - visibleItems);
        int sbX = px + panelW - 6;
        ctx.fill(sbX, listTop, sbX + 4, listBottom, 0x22FFFFFF);
        ctx.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, accentColor);
    }

    // ── Color mixing ─────────────────────────────────────────────────────────

    /** Mix two colors with the overlay's alpha as blend factor. */
    public static int mixA(int base, int overlay) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or2 = (overlay >> 16) & 0xFF, og = (overlay >> 8) & 0xFF, ob = overlay & 0xFF;
        float a = ((overlay >> 24) & 0xFF) / 255f;
        return 0xFF000000
                | (Math.min(255, (int)(br + (or2 - br) * a)) << 16)
                | (Math.min(255, (int)(bg + (og - bg) * a)) << 8)
                |  Math.min(255, (int)(bb + (ob - bb) * a));
    }

    /** Mix preserving max alpha. */
    public static int mixColor(int base, int overlay) {
        int ba = (base >> 24) & 0xFF, br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int oa = (overlay >> 24) & 0xFF, or2 = (overlay >> 16) & 0xFF, og = (overlay >> 8) & 0xFF, ob = overlay & 0xFF;
        float a = oa / 255f;
        return (Math.max(ba, oa) << 24)
                | ((int)(br + (or2 - br) * a) << 16)
                | ((int)(bg + (og - bg) * a) << 8)
                |  (int)(bb + (ob - bb) * a);
    }
}
