package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.ChatFilterConfig;
import com.deinname.chatfilter.FilterRule;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public final class FilterRuleEditScreen extends Screen {

    private static final int PANEL_W_MAX = 420;
    private static final int PANEL_H_MAX = 440;
    private static final int PAD = 14;

    private int panelW;
    private int panelH;

    private final Screen parent;
    private final FilterRule original;
    private final Consumer<FilterRule> onSave;
    private ThemeColors colors;
    private int timeoutLabelY; // cached for render()

    private boolean editEnabled;
    private boolean editBlock;
    private boolean editCensor;
    private boolean editStrip;
    private boolean editWarn;
    private boolean editReplace;
    private boolean editConfirm;
    private boolean editDisconnect;

    private TextFieldWidget warningField;
    private TextFieldWidget replacementField;
    private TextFieldWidget timeoutField;
    private int editTimeout;

    public FilterRuleEditScreen(Screen parent, FilterRule rule, Consumer<FilterRule> onSave) {
        super(Text.literal("Regel bearbeiten"));
        this.parent = parent;
        this.original = rule;
        this.onSave = onSave;
        this.editEnabled = rule.isEnabled();
        this.editBlock = rule.isBlockMessage();
        this.editCensor = rule.isCensorWord();
        this.editStrip = rule.isStripWord();
        this.editWarn = rule.isShowWarning();
        this.editReplace = rule.isSendReplacement();
        this.editConfirm = rule.isRequireConfirmation();
        this.editDisconnect = rule.isDisconnectFromServer();
        this.editTimeout = rule.getTimeoutSeconds();
        colors = UIHelper.loadColors();
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 20, PANEL_W_MAX);
        panelH = Math.min(height - 20, PANEL_H_MAX);
        int px = (width - panelW) / 2;
        int py = (height - panelH) / 2;
        int bw = panelW - PAD * 2;
        int bx = px + PAD;
        int y = py + 50;

        // ── Section: Status ──
        addToggle(bx, y, bw, "\u2726 Regel aktiviert", () -> editEnabled, v -> editEnabled = v);
        y += 30;

        // ── Section: Nachrichtenverarbeitung ──
        y += 4; // section gap
        addToggle(bx, y, bw, "\u25a0 Nachricht komplett blockieren", () -> editBlock, v -> editBlock = v);
        y += 24;
        addToggle(bx, y, bw, "*** Wort mit *** zensieren (Rest senden)", () -> editCensor, v -> editCensor = v);
        y += 24;
        addToggle(bx, y, bw, "\u2702 Wort entfernen (Rest senden)", () -> editStrip, v -> editStrip = v);
        y += 28;

        // ── Section: Benachrichtigungen ──
        addToggle(bx, y, bw, "\u26a0 Warnung im Chat anzeigen", () -> editWarn, v -> editWarn = v);
        y += 22;
        warningField = new TextFieldWidget(textRenderer, bx + 4, y, bw - 8, 16, Text.literal(""));
        warningField.setText(original.getWarningText());
        warningField.setMaxLength(200);
        warningField.setPlaceholder(Text.literal("\u00a78Warnungstext eingeben..."));
        addDrawableChild(warningField);
        y += 22;

        addToggle(bx, y, bw, "\u21bb Ersatznachricht senden", () -> editReplace, v -> editReplace = v);
        y += 22;
        replacementField = new TextFieldWidget(textRenderer, bx + 4, y, bw - 8, 16, Text.literal(""));
        replacementField.setText(original.getReplacementText());
        replacementField.setMaxLength(256);
        replacementField.setPlaceholder(Text.literal("\u00a78Ersatznachricht eingeben..."));
        addDrawableChild(replacementField);
        y += 24;

        // ── Section: Erweitert ──
        addToggle(bx, y, bw, "? Best\u00e4tigung anfordern (!confirm)", () -> editConfirm, v -> editConfirm = v);
        y += 24;
        addToggle(bx, y, bw, "\u23cf Server verlassen (Disconnect)", () -> editDisconnect, v -> editDisconnect = v);
        y += 24;

        // Timeout field
        timeoutLabelY = y;
        timeoutField = new TextFieldWidget(textRenderer, bx + bw - 58, y, 54, 16, Text.literal(""));
        timeoutField.setText(editTimeout > 0 ? String.valueOf(editTimeout) : "0");
        timeoutField.setMaxLength(5);
        timeoutField.setPlaceholder(Text.literal("\u00a780"));
        addDrawableChild(timeoutField);
        y += 28;

        // ── Action buttons ──
        int half = (bw - 12) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a\u2714 \u00dcbernehmen"), b -> applyChanges())
                .dimensions(bx, y, half, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7c\u2716 Abbrechen"), b -> close())
                .dimensions(bx + half + 12, y, half, 22).build());
    }

    private void addToggle(int x, int y, int w, String label,
                            Supplier<Boolean> getter, Consumer<Boolean> setter) {
        addDrawableChild(ButtonWidget.builder(
                Text.literal(toggleText(label, getter.get())),
                b -> {
                    setter.accept(!getter.get());
                    b.setMessage(Text.literal(toggleText(label, getter.get())));
                }
        ).dimensions(x, y, w, 20).build());
    }

    private static String toggleText(String label, boolean on) {
        return on ? "\u00a7a\u2713 " + label : "\u00a78\u2717 " + label;
    }

    private void applyChanges() {
        FilterRule updated = new FilterRule(original);
        updated.setEnabled(editEnabled);
        updated.setBlockMessage(editBlock);
        updated.setCensorWord(editCensor);
        updated.setStripWord(editStrip);
        updated.setShowWarning(editWarn);
        updated.setWarningText(warningField.getText());
        updated.setSendReplacement(editReplace);
        updated.setReplacementText(replacementField.getText());
        updated.setRequireConfirmation(editConfirm);
        updated.setDisconnectFromServer(editDisconnect);
        try { updated.setTimeoutSeconds(Integer.parseInt(timeoutField.getText().strip())); }
        catch (NumberFormatException e) { updated.setTimeoutSeconds(0); }
        onSave.accept(updated);
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
        int py = (height - panelH) / 2;

        ctx.fill(0, 0, width, height, t.bg);

        // Panel
        ctx.fill(px, py, px + panelW, py + panelH, t.panel);
        // Border 2px
        ctx.fill(px, py, px + panelW, py + 2, t.border);
        ctx.fill(px, py + panelH - 2, px + panelW, py + panelH, t.border);
        ctx.fill(px, py, px + 2, py + panelH, t.border);
        ctx.fill(px + panelW - 2, py, px + panelW, py + panelH, t.border);

        // Top accent
        ctx.fill(px + 2, py + 2, px + panelW - 2, py + 4, t.accent);
        ctx.fill(px + 2, py + 4, px + panelW - 2, py + 5, t.accentGlow);

        // Header
        ctx.fill(px + 2, py + 5, px + panelW - 2, py + 45, t.panelHead);
        ctx.fill(px + 2, py + 44, px + panelW - 2, py + 45, t.accentGlow);

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7l\u270e Regel bearbeiten"),
                px + panelW / 2, py + 12, t.accent);

        String kwDisplay = original.getKeyword();
        if (kwDisplay.length() > 40) kwDisplay = kwDisplay.substring(0, 38) + "\u2026";
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Keyword: \u00a7f" + kwDisplay),
                px + panelW / 2, py + 28, t.subtext);

        // Timeout label (drawn manually next to the field)
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78Timeout (Sek., 0=aus):"),
                px + PAD + 4, timeoutLabelY + 4, t.subtext);

        super.render(ctx, mx, my, delta);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean shouldPause() { return false; }
}
