package com.deinname.chatfilter.screen;

/**
 * Selectable color themes for the Columba UI.
 */
public enum UITheme {
    OCEAN("Ocean",
        0xF00A0E1A, 0xF2101828, 0xF5141E35, 0xFF3B82F6, 0x553B82F6,
        0xEE0D1225, 0xEE152040, 0xFF1A2545, 0xFF1E2D50, 0xFFD4DEFF, 0xFF7B8DB8),
    EMERALD("Emerald",
        0xF0081410, 0xF20E1F1A, 0xF5122A22, 0xFF10B981, 0x5510B981,
        0xEE0B1812, 0xEE14261E, 0xFF152E24, 0xFF183828, 0xFFD1FAE5, 0xFF6EE7A8),
    SUNSET("Sunset",
        0xF01A0E08, 0xF22A1610, 0xF5351E15, 0xFFF59E0B, 0x55F59E0B,
        0xEE1F120A, 0xEE2D1C14, 0xFF2E1D14, 0xFF3A2418, 0xFFFEF3C7, 0xFFFBBF24),
    ROSE("Ros\u00e9",
        0xF01A0A14, 0xF22A1020, 0xF535152A, 0xFFF43F5E, 0x55F43F5E,
        0xEE1E0C16, 0xEE2E1624, 0xFF2D1522, 0xFF3A1A2C, 0xFFFFE4E6, 0xFFFDA4AF),
    PURPLE("Purple",
        0xF0100A1A, 0xF21A1028, 0xF5221535, 0xFF8B5CF6, 0x558B5CF6,
        0xEE120D20, 0xEE1E1638, 0xFF1C1538, 0xFF241C45, 0xFFEDE9FE, 0xFFA78BFA),
    MONO("Mono",
        0xF0101010, 0xF21A1A1A, 0xF5222222, 0xFF888888, 0x55888888,
        0xEE141414, 0xEE222222, 0xFF1C1C1C, 0xFF262626, 0xFFE0E0E0, 0xFF999999);

    public final String displayName;
    public final int bg, panel, panelHead, accent, accentGlow;
    public final int rowEven, rowHover, rowAccent, border, text, subtext;

    UITheme(String displayName,
            int bg, int panel, int panelHead, int accent, int accentGlow,
            int rowEven, int rowHover, int rowAccent, int border, int text, int subtext) {
        this.displayName = displayName;
        this.bg = bg;
        this.panel = panel;
        this.panelHead = panelHead;
        this.accent = accent;
        this.accentGlow = accentGlow;
        this.rowEven = rowEven;
        this.rowHover = rowHover;
        this.rowAccent = rowAccent;
        this.border = border;
        this.text = text;
        this.subtext = subtext;
    }

    public UITheme next() {
        UITheme[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
