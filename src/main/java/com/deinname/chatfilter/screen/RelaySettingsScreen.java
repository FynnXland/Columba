package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.AdminConfig;
import com.deinname.chatfilter.RelaySync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import static com.deinname.chatfilter.screen.UIHelper.*;

/**
 * Relay settings – dual mode: WebSocket (primary) + ntfy.sh (fallback).
 */
@Environment(EnvType.CLIENT)
public final class RelaySettingsScreen extends Screen {

    private static final int PANEL_W_MAX = 450, H = 370, PAD = 16;

    private final Screen parent;
    private ThemeColors colors;
    private int panelW, px, py;

    private TextFieldWidget wsUrlField;
    private TextFieldWidget wsKeyField;
    private TextFieldWidget ntfyUrlField;
    private ButtonWidget modeButton;

    private String feedback = "";
    private int feedbackTicks = 0;
    private boolean feedbackOk = true;

    public RelaySettingsScreen(Screen parent) {
        super(Text.literal("Relay Einstellungen"));
        this.parent = parent;
        this.colors = UIHelper.loadColors();
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 20, PANEL_W_MAX);
        px = (width - panelW) / 2;
        py = (height - H) / 2;
        int fw = panelW - PAD * 2;
        int bw = (fw - 8) / 2;

        int y = py + 60;

        // ── Mode toggle button ──────────────────────────────────────────
        modeButton = ButtonWidget.builder(Text.literal(modeLabel()), b -> {
            RelaySync.RelayMode cur = RelaySync.getMode();
            RelaySync.setMode(cur == RelaySync.RelayMode.CUSTOM
                    ? RelaySync.RelayMode.NTFY : RelaySync.RelayMode.CUSTOM);
            b.setMessage(Text.literal(modeLabel()));
            setFeedback("\u00a7eModus: " + RelaySync.getMode().name(), true);
        }).dimensions(px + PAD, y, fw, 20).build();
        addDrawableChild(modeButton);
        y += 28;

        // ── Server URL ───────────────────────────────────────────────
        wsUrlField = new TextFieldWidget(textRenderer, px + PAD, y + 12, fw, 20, Text.literal(""));
        wsUrlField.setMaxLength(256);
        wsUrlField.setText(RelaySync.getCustomUrl());
        wsUrlField.setPlaceholder(Text.literal("\u00a78https://abc.ngrok-free.app"));
        addDrawableChild(wsUrlField);
        y += 38;

        // ── Server Secret ────────────────────────────────────────────
        wsKeyField = new TextFieldWidget(textRenderer, px + PAD, y + 12, fw, 20, Text.literal(""));
        wsKeyField.setMaxLength(128);
        wsKeyField.setText(RelaySync.getCustomSecret());
        wsKeyField.setPlaceholder(Text.literal("\u00a78changeme"));
        addDrawableChild(wsKeyField);
        y += 38;

        // ── ntfy URL (fallback) ─────────────────────────────────────────
        ntfyUrlField = new TextFieldWidget(textRenderer, px + PAD, y + 12, fw, 20, Text.literal(""));
        ntfyUrlField.setMaxLength(256);
        ntfyUrlField.setText(RelaySync.getNtfyBase());
        ntfyUrlField.setPlaceholder(Text.literal("\u00a78https://ntfy.sh/"));
        addDrawableChild(ntfyUrlField);
        y += 40;

        // ── Save + Reset buttons ────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a\u2714 Speichern"), b -> {
            RelaySync.setCustomUrl(wsUrlField.getText().strip());
            RelaySync.setCustomSecret(wsKeyField.getText().strip());
            RelaySync.setNtfyBase(ntfyUrlField.getText().strip());
            RelaySync.save();
            // Restart polling with new settings
            RelaySync.startPolling(AdminConfig::handleIncomingMessage);
            setFeedback("\u00a7a\u2714 Gespeichert & Relay neugestartet!", true);
        }).dimensions(px + PAD, y, bw, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7c\u26a0 Rate-Limit Reset"), b -> {
            RelaySync.resetCircuitBreaker();
            setFeedback("\u00a7a\u2714 Rate-Limit zur\u00fcckgesetzt!", true);
        }).dimensions(px + PAD + bw + 8, y, bw, 20).build());
        y += 28;

        // ── Test + Close buttons ────────────────────────────────────────
        int by2 = py + H - PAD - 22;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7e\u2709 Verbindung testen"), b -> {
            setFeedback("\u00a7eTeste...", true);
            new Thread(() -> {
                int code = RelaySync.testConnection();
                if (code == 200) setFeedback("\u00a7a\u2714 Verbindung OK!", true);
                else setFeedback("\u00a7cFehler (Code " + code + ")", false);
            }, "CF-Test").start();
        }).dimensions(px + PAD, by2, bw, 22).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a77\u2716 Schlie\u00dfen"), b -> close())
                .dimensions(px + PAD + bw + 8, by2, bw, 22).build());
    }

    private String modeLabel() {
        return RelaySync.getMode() == RelaySync.RelayMode.CUSTOM
                ? "\u00a7b\u26a1 Modus: Eigener Server (Prim\u00e4r)"
                : "\u00a7e\u26a1 Modus: ntfy.sh";
    }

    private void setFeedback(String msg, boolean ok) {
        feedback = msg; feedbackOk = ok; feedbackTicks = 160;
    }

    @Override
    public void tick() { if (feedbackTicks > 0 && --feedbackTicks == 0) feedback = ""; }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeColors t = colors;
        ctx.fill(0, 0, width, height, mixA(t.bg, 0xCC000000));
        ctx.fill(px, py, px + panelW, py + H, t.panel);
        drawAccentStripe(ctx, px, py, panelW, t);
        drawBorder(ctx, px, py, panelW, H, t.border);

        // Header
        ctx.fill(px + 2, py + 5, px + panelW - 2, py + 40, t.panelHead);
        ctx.fill(px + 2, py + 39, px + panelW - 2, py + 40, t.accentGlow);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7b\u00a7l\u26a1 Relay Einstellungen"),
                px + panelW / 2, py + 10, t.text);

        // Connection status
        RelaySync.ConnectionState state = RelaySync.getConnectionState();
        String stateIcon; String stateText; int stateColor;
        switch (state) {
            case CONNECTED -> { stateIcon = "\u00a7a\u25cf"; stateText = "Verbunden (" + RelaySync.getMode().name() + ")"; stateColor = 0xFF55FF55; }
            case CONNECTING -> { stateIcon = "\u00a7e\u25cb"; stateText = "Verbinde..."; stateColor = 0xFFFFFF55; }
            case RATE_LIMITED -> { stateIcon = "\u00a7c\u26a0"; stateText = "Rate-Limited"; stateColor = 0xFFFF5555; }
            case ERROR -> { stateIcon = "\u00a7c\u2716"; stateText = "Fehler: " + RelaySync.getConnectionError(); stateColor = 0xFFFF5555; }
            default -> { stateIcon = "\u00a78\u25cb"; stateText = "Getrennt"; stateColor = 0xFF888888; }
        }
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(stateIcon + " " + stateText),
                px + panelW / 2, py + 25, stateColor);

        // Channel info
        String me = AdminConfig.getCurrentUsername();
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a77Kanal: \u00a7fcolumba-" + me.toLowerCase()),
                px + panelW / 2, py + 44, t.text);

        // Labels
        int fw = panelW - PAD * 2;
        int y = py + 60;
        y += 28; // skip mode button

        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a77Server URL:"), px + PAD, y, t.text);
        y += 38;
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a77Server Secret:"), px + PAD, y, t.text);
        y += 38;
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a78ntfy.sh URL (Fallback):"), px + PAD, y, t.text);

        // Online count
        if (AdminConfig.isAdmin()) {
            int online = AdminConfig.countOnline(AdminConfig.getPlayerNames());
            int total = AdminConfig.getPlayerNames().size();
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a77Erreichbar: "
                            + (online > 0 ? "\u00a7a" : "\u00a7c") + online + "\u00a77/" + total),
                    px + panelW / 2, py + H - 38, t.text);
        }

        // Feedback
        drawFeedback(ctx, textRenderer, feedback, feedbackOk, feedbackTicks,
                px + panelW / 2, py + H - 14);

        super.render(ctx, mx, my, delta);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(keyInput);
    }

    @Override
    public void close() { if (client != null) client.setScreen(parent); }

    @Override public boolean shouldPause() { return false; }
}

