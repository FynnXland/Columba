package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.ChatFilterConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import static com.deinname.chatfilter.screen.UIHelper.*;

/**
 * General settings screen for all Columba users.
 * Toggle overlay, ping messages, sync notifications.
 */
@Environment(EnvType.CLIENT)
public final class SettingsScreen extends Screen {

    private static final int PANEL_W_MAX = 320;
    private static final int PANEL_H_MAX = 220;
    private static final int PAD = 14;

    private final Screen parent;
    private ThemeColors colors;
    private int panelW, panelH2;

    private boolean showTimeoutOverlay;
    private boolean showPingMessages;
    private boolean showSyncNotifications;

    public SettingsScreen(Screen parent) {
        super(Text.literal("Columba - Einstellungen"));
        this.parent = parent;
        this.showTimeoutOverlay = ChatFilterConfig.isShowTimeoutOverlay();
        this.showPingMessages = ChatFilterConfig.isShowPingMessages();
        this.showSyncNotifications = ChatFilterConfig.isShowSyncNotifications();
    }

    @Override
    protected void init() {
        super.init();
        panelW = Math.min(width - 20, PANEL_W_MAX);
        panelH2 = Math.min(height - 20, PANEL_H_MAX);
        colors = UIHelper.loadColors();
        buildButtons();
    }

    private void buildButtons() {
        clearChildren();
        int px = (width - panelW) / 2;
        int py = (height - panelH2) / 2;

        int bw = panelW - PAD * 2;
        int by = py + 40;

        // Toggle: Chat-Sperre Overlay
        addDrawableChild(ButtonWidget.builder(
                Text.literal(toggleLabel("Chat-Sperre Overlay", showTimeoutOverlay)),
                b -> {
                    showTimeoutOverlay = !showTimeoutOverlay;
                    buildButtons();
                }
        ).dimensions(px + PAD, by, bw, 22).build());

        // Toggle: Ping-Nachrichten
        addDrawableChild(ButtonWidget.builder(
                Text.literal(toggleLabel("Ping-Nachrichten anzeigen", showPingMessages)),
                b -> {
                    showPingMessages = !showPingMessages;
                    buildButtons();
                }
        ).dimensions(px + PAD, by + 30, bw, 22).build());

        // Toggle: Sync-Benachrichtigungen
        addDrawableChild(ButtonWidget.builder(
                Text.literal(toggleLabel("Sync-Meldungen anzeigen", showSyncNotifications)),
                b -> {
                    showSyncNotifications = !showSyncNotifications;
                    buildButtons();
                }
        ).dimensions(px + PAD, by + 60, bw, 22).build());

        // Save
        addDrawableChild(ButtonWidget.builder(
                Text.literal("\u00a7a\u2714 Speichern"),
                b -> saveAndClose()
        ).dimensions(px + PAD, py + panelH2 - 38, (bw - 8) / 2, 22).build());

        // Cancel
        addDrawableChild(ButtonWidget.builder(
                Text.literal("\u00a7c\u2716 Abbrechen"),
                b -> close()
        ).dimensions(px + PAD + (bw - 8) / 2 + 8, py + panelH2 - 38, (bw - 8) / 2, 22).build());
    }

    private String toggleLabel(String label, boolean value) {
        return (value ? "\u00a7a\u25cf AN" : "\u00a7c\u25cb AUS") + " \u00a7f" + label;
    }

    private void saveAndClose() {
        ChatFilterConfig.setShowTimeoutOverlay(showTimeoutOverlay);
        ChatFilterConfig.setShowPingMessages(showPingMessages);
        ChatFilterConfig.setShowSyncNotifications(showSyncNotifications);
        close();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeColors t = colors;
        int px = (width - panelW) / 2;
        int py = (height - panelH2) / 2;

        // Background
        ctx.fill(0, 0, width, height, mixA(t.bg, 0xCC000000));
        ctx.fill(px, py, px + panelW, py + panelH2, t.panel);

        // Accent stripe + border
        drawAccentStripe(ctx, px, py, panelW, t);
        drawBorder(ctx, px, py, panelW, panelH2, t.border);

        // Header area
        ctx.fill(px + 2, py + 5, px + panelW - 2, py + 36, t.panelHead);
        ctx.fill(px + 2, py + 35, px + panelW - 2, py + 36, t.accentGlow);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7l\u2699 Einstellungen"),
                px + panelW / 2, py + 12, t.accent);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a77Allgemeine Mod-Einstellungen"),
                px + panelW / 2, py + 24, t.subtext);

        // Section divider above toggles
        ctx.fill(px + PAD, py + 38, px + panelW - PAD, py + 39, t.border);

        // Section divider above footer buttons
        ctx.fill(px + PAD, py + panelH2 - 46, px + panelW - PAD, py + panelH2 - 45, t.border);

        super.render(ctx, mx, my, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        return super.mouseClicked(click, bl);
    }
}
