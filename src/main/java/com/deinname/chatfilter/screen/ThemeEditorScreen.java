package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.ChatFilterConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

import static com.deinname.chatfilter.screen.UIHelper.*;

/**
 * Screen for choosing a preset theme or creating a custom accent color via RGB sliders.
 */
@Environment(EnvType.CLIENT)
public final class ThemeEditorScreen extends Screen {

    private static final int PANEL_W_MAX = 400;
    private static final int PANEL_H_MAX = 360;
    private static final int PAD = 14;

    private int panelW;
    private int panelH;
    private static final int SLIDER_H = 16;
    private static final int SLIDER_GAP = 26;

    private final Screen parent;
    private final Consumer<ThemeColors> onApply;

    private UITheme selectedPreset;
    private int accentR, accentG, accentB;
    private boolean isCustom;

    // Slider geometry (calculated in render)
    private int sliderX, sliderW;
    private int sliderRY, sliderGY, sliderBY;
    private int dragChannel = -1; // 0=R, 1=G, 2=B

    public ThemeEditorScreen(Screen parent, Consumer<ThemeColors> onApply) {
        super(Text.literal("Theme Editor"));
        this.parent = parent;
        this.onApply = onApply;

        String name = ChatFilterConfig.getThemeName();
        if ("CUSTOM".equals(name)) {
            isCustom = true;
            selectedPreset = null;
            accentR = ChatFilterConfig.getCustomAccentR();
            accentG = ChatFilterConfig.getCustomAccentG();
            accentB = ChatFilterConfig.getCustomAccentB();
        } else {
            isCustom = false;
            try { selectedPreset = UITheme.valueOf(name); }
            catch (Exception e) { selectedPreset = UITheme.OCEAN; }
            accentR = (selectedPreset.accent >> 16) & 0xFF;
            accentG = (selectedPreset.accent >> 8) & 0xFF;
            accentB = selectedPreset.accent & 0xFF;
        }
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 20, PANEL_W_MAX);
        panelH = Math.min(height - 20, PANEL_H_MAX);
        int px = (width - panelW) / 2;
        int py = (height - panelH) / 2;
        int bx = px + PAD;
        int bw = (panelW - PAD * 2 - 10) / 3;

        // Preset buttons row 1
        UITheme[] presets = UITheme.values();
        int row1y = py + 50;
        for (int i = 0; i < Math.min(3, presets.length); i++) {
            UITheme p = presets[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(p.displayName),
                    b -> selectPreset(p))
                    .dimensions(bx + i * (bw + 5), row1y, bw, 18).build());
        }
        // Preset buttons row 2
        int row2y = row1y + 22;
        for (int i = 3; i < Math.min(6, presets.length); i++) {
            UITheme p = presets[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(p.displayName),
                    b -> selectPreset(p))
                    .dimensions(bx + (i - 3) * (bw + 5), row2y, bw, 18).build());
        }

        // Action buttons
        int halfW = (panelW - PAD * 2 - 12) / 2;
        int btnY = py + panelH - 36;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a\u2714 \u00dcbernehmen"), b -> applyAndClose())
                .dimensions(bx, btnY, halfW, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7c\u2716 Abbrechen"), b -> close())
                .dimensions(bx + halfW + 12, btnY, halfW, 22).build());
    }

    private void selectPreset(UITheme preset) {
        selectedPreset = preset;
        isCustom = false;
        accentR = (preset.accent >> 16) & 0xFF;
        accentG = (preset.accent >> 8) & 0xFF;
        accentB = preset.accent & 0xFF;
    }

    private void applyAndClose() {
        if (isCustom) {
            ChatFilterConfig.setThemeName("CUSTOM");
            ChatFilterConfig.setCustomAccent(accentR, accentG, accentB);
            onApply.accept(new ThemeColors(accentR, accentG, accentB));
        } else if (selectedPreset != null) {
            ChatFilterConfig.setThemeName(selectedPreset.name());
            onApply.accept(new ThemeColors(selectedPreset));
        }
        close();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeColors t = currentColors();
        int px = (width - panelW) / 2;
        int py = (height - panelH) / 2;

        // Background & panel
        ctx.fill(0, 0, width, height, t.bg);
        ctx.fill(px, py, px + panelW, py + panelH, t.panel);
        drawBorder(ctx, px, py, panelW, panelH, t.border);

        // Accent stripe
        ctx.fill(px + 2, py + 2, px + panelW - 2, py + 4, t.accent);
        ctx.fill(px + 2, py + 4, px + panelW - 2, py + 5, t.accentGlow);

        // Header
        ctx.fill(px + 2, py + 5, px + panelW - 2, py + 42, t.panelHead);
        ctx.fill(px + 2, py + 41, px + panelW - 2, py + 42, t.accentGlow);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7l\u2726 Theme Editor"),
                px + panelW / 2, py + 14, t.accent);
        String sub = isCustom ? "Custom (" + accentR + ", " + accentG + ", " + accentB + ")"
                : (selectedPreset != null ? selectedPreset.displayName : "?");
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(sub),
                px + panelW / 2, py + 28, t.subtext);

        // Section: custom accent
        int secY = py + 102;
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a7l\u2014 Eigene Akzentfarbe \u2014"),
                px + PAD, secY, t.subtext);

        // Sliders
        sliderX = px + PAD;
        sliderW = panelW - PAD * 2 - 54;
        sliderRY = secY + 18;
        sliderGY = sliderRY + SLIDER_GAP;
        sliderBY = sliderGY + SLIDER_GAP;

        drawSlider(ctx, sliderX, sliderRY, sliderW, accentR, 0xFFFF4444, "R: " + accentR, mx, my);
        drawSlider(ctx, sliderX, sliderGY, sliderW, accentG, 0xFF44FF44, "G: " + accentG, mx, my);
        drawSlider(ctx, sliderX, sliderBY, sliderW, accentB, 0xFF6688FF, "B: " + accentB, mx, my);

        // Color preview
        int prevY = sliderBY + SLIDER_GAP + 10;
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a7lVorschau:"), px + PAD, prevY, t.subtext);
        int boxY = prevY + 14;
        int previewCol = 0xFF000000 | (accentR << 16) | (accentG << 8) | accentB;
        ctx.fill(px + PAD, boxY, px + PAD + 50, boxY + 22, previewCol);
        drawBorder1(ctx, px + PAD, boxY, 50, 22, t.border);

        // Mini row preview
        int miniX = px + PAD + 60;
        int miniW = panelW - PAD * 2 - 60;
        ctx.fill(miniX, boxY, miniX + miniW, boxY + 22, t.rowEven);
        drawBorder1(ctx, miniX, boxY, miniW, 22, t.border);
        ctx.fill(miniX + 2, boxY + 1, miniX + 5, boxY + 21, t.accent);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a7a\u25cf \u00a7fbeispiel \u00a76*** \u00a7e\u26a0"),
                miniX + 8, boxY + 7, t.text);

        // Drag tracking via GLFW (avoids mouseDragged signature issues)
        if (dragChannel >= 0 && client != null) {
            long win = client.getWindow().getHandle();
            if (GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
                updateSliderValue(mx);
            } else {
                dragChannel = -1;
            }
        }

        super.render(ctx, mx, my, delta);
    }

    private void drawSlider(DrawContext ctx, int x, int y, int w, int value, int fillColor, String label, int mx, int my) {
        ctx.fill(x, y, x + w, y + SLIDER_H, 0xFF1A1A2A);
        int fillW = (int) (w * value / 255.0);
        ctx.fill(x, y, x + fillW, y + SLIDER_H, fillColor);
        // Thumb indicator
        int tx = x + fillW;
        ctx.fill(tx - 1, y - 1, tx + 2, y + SLIDER_H + 1, 0xFFFFFFFF);
        // Label
        ctx.drawTextWithShadow(textRenderer, Text.literal(label), x + w + 6, y + 4, 0xFFCCCCCC);
        // Hover highlight
        if (mx >= x && mx <= x + w && my >= y && my <= y + SLIDER_H) {
            ctx.fill(x, y, x + w, y + SLIDER_H, 0x15FFFFFF);
        }
    }

    private void updateSliderValue(double mx) {
        int val = (int) Math.round((mx - sliderX) * 255.0 / sliderW);
        val = Math.max(0, Math.min(255, val));
        switch (dragChannel) {
            case 0 -> accentR = val;
            case 1 -> accentG = val;
            case 2 -> accentB = val;
        }
        isCustom = true;
    }

    private ThemeColors currentColors() {
        if (isCustom) return new ThemeColors(accentR, accentG, accentB);
        return selectedPreset != null ? new ThemeColors(selectedPreset) : new ThemeColors(UITheme.OCEAN);
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mx = click.x(), my = click.y();
        if (mx >= sliderX && mx <= sliderX + sliderW) {
            if (my >= sliderRY && my < sliderRY + SLIDER_H) { dragChannel = 0; updateSliderValue(mx); return true; }
            if (my >= sliderGY && my < sliderGY + SLIDER_H) { dragChannel = 1; updateSliderValue(mx); return true; }
            if (my >= sliderBY && my < sliderBY + SLIDER_H) { dragChannel = 2; updateSliderValue(mx); return true; }
        }
        dragChannel = -1;
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean shouldPause() { return false; }
}
