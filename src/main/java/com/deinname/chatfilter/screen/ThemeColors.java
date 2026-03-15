package com.deinname.chatfilter.screen;

/**
 * Color container for UI theming.
 * Can be constructed from a preset {@link UITheme} or derived from a custom RGB accent.
 */
public final class ThemeColors {
    public final String displayName;
    public final int bg, panel, panelHead, accent, accentGlow;
    public final int rowEven, rowHover, rowAccent, border, text, subtext;

    /** Copy all colors from a preset theme. */
    public ThemeColors(UITheme preset) {
        this.displayName = preset.displayName;
        this.bg = preset.bg;
        this.panel = preset.panel;
        this.panelHead = preset.panelHead;
        this.accent = preset.accent;
        this.accentGlow = preset.accentGlow;
        this.rowEven = preset.rowEven;
        this.rowHover = preset.rowHover;
        this.rowAccent = preset.rowAccent;
        this.border = preset.border;
        this.text = preset.text;
        this.subtext = preset.subtext;
    }

    /** Derive a full color palette from a custom accent color. */
    public ThemeColors(int accentR, int accentG, int accentB) {
        this.displayName = "Custom";
        int r = clamp(accentR), g = clamp(accentG), b = clamp(accentB);
        this.accent     = argb(0xFF, r, g, b);
        this.accentGlow = argb(0x55, r, g, b);
        this.bg         = argb(0xF0, r / 25, g / 25, b / 25);
        this.panel      = argb(0xF2, r / 16 + 3, g / 16 + 3, b / 16 + 3);
        this.panelHead  = argb(0xF5, r / 12 + 4, g / 12 + 4, b / 12 + 4);
        this.rowEven    = argb(0xEE, r / 20 + 2, g / 20 + 2, b / 20 + 2);
        this.rowHover   = argb(0xEE, r / 10 + 5, g / 10 + 5, b / 10 + 5);
        this.rowAccent  = argb(0xFF, r / 15 + 3, g / 15 + 3, b / 15 + 3);
        this.border     = argb(0xFF, r / 8 + 6, g / 8 + 6, b / 8 + 6);
        this.text       = argb(0xFF, clamp(200 + r / 5), clamp(200 + g / 5), clamp(200 + b / 5));
        this.subtext    = argb(0xFF, clamp(110 + r / 3), clamp(110 + g / 3), clamp(110 + b / 3));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static int argb(int a, int r, int g, int b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }
}
