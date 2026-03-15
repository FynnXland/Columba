package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.AdminConfig;
import com.deinname.chatfilter.ChatFilterConfig;
import com.deinname.chatfilter.FilterRule;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.deinname.chatfilter.screen.UIHelper.*;

/**
 * Screen for the admin to manage a specific player's filter rules.
 * All rules created here are automatically locked (player cannot remove them).
 */
@Environment(EnvType.CLIENT)
public final class AdminPlayerScreen extends Screen {

    private static final int PANEL_W_MAX = 500;
    private static final int ITEM_H  = 30;
    private static final int HEADER_H = 70;
    private static final int FOOTER_H = 90;
    private static final int PAD = 12;

    private ThemeColors colors;
    private int panelW;
    private int panelX, panelY, panelH;
    private int listTop, listBottom;

    private final Screen parent;
    private final String playerName;
    private List<FilterRule> rules;
    private boolean banned;
    private TextFieldWidget addField;
    private int scrollOffset = 0;
    private String feedback = null;
    private boolean feedbackIsError = false;
    private int feedbackTicks = 0;

    public AdminPlayerScreen(Screen parent, String playerName) {
        super(Text.literal("Spieler: " + playerName));
        this.parent = parent;
        this.playerName = playerName;
        AdminConfig.PlayerData pd = AdminConfig.getOrCreatePlayer(playerName);
        this.rules = new ArrayList<>();
        for (FilterRule r : pd.rules) rules.add(new FilterRule(r));
        this.banned = pd.banned;
        colors = UIHelper.loadColors();
    }

    private void recalcLayout() {
        panelW     = Math.min(width - 20, PANEL_W_MAX);
        panelH     = Math.min(height - 20, 520);
        panelX     = (width - panelW) / 2;
        panelY     = (height - panelH) / 2;
        listTop    = panelY + HEADER_H;
        listBottom = panelY + panelH - FOOTER_H;
    }

    private int visibleRows() { return Math.max(1, (listBottom - listTop) / ITEM_H); }
    private void clampScroll() {
        int max = Math.max(0, rules.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    @Override
    protected void init() {
        recalcLayout();

        // Add rule field
        int inputY = panelY + panelH - FOOTER_H + PAD;
        addField = new TextFieldWidget(textRenderer,
                panelX + PAD, inputY, panelW - PAD * 2 - 130, 20, Text.literal(""));
        addField.setMaxLength(64);
        addField.setPlaceholder(Text.literal("\u00a78Neues Keyword eingeben..."));
        addDrawableChild(addField);
        setInitialFocus(addField);

        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a+ Regel"), b -> addRule())
                .dimensions(panelX + panelW - PAD - 122, inputY, 60, 20).build());

        // Ban toggle next to add rule
        addDrawableChild(ButtonWidget.builder(
                Text.literal(banned ? "\u00a7c\u26d4 Gebannt" : "\u00a7a\u2714 Aktiv"),
                b -> {
                    banned = !banned;
                    b.setMessage(Text.literal(banned ? "\u00a7c\u26d4 Gebannt" : "\u00a7a\u2714 Aktiv"));
                    setFeedback(banned ? "\u00a7cSpieler gebannt." : "\u00a7aSpieler entbannt.", false);
                }
        ).dimensions(panelX + panelW - PAD - 56, inputY, 56, 20).build());

        // Footer buttons (4 buttons: Push | Import | Save | Cancel)
        int bw = (panelW - PAD * 2 - 24) / 4;
        int by = panelY + panelH - 36;

        // Push rules to player
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7b\u2191 Push"), b -> {
            saveData();
            AdminConfig.pushToPlayer(playerName);
            setFeedback("\u00a7aRegeln an " + playerName + " gepusht!", false);
        }).dimensions(panelX + PAD, by, bw, 22).build());

        // Import rules from clipboard
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7e\u2b06 Import"), b -> {
            try {
                String clip = client.keyboard.getClipboard();
                if (clip == null || clip.isBlank()) {
                    setFeedback("\u00a7cZwischenablage ist leer.", true);
                    return;
                }
                List<FilterRule> imported = AdminConfig.decodeRulesFromCode(clip.strip());
                if (imported != null && !imported.isEmpty()) {
                    int added = 0;
                    for (FilterRule ir : imported) {
                        boolean exists = false;
                        for (FilterRule r : rules) {
                            if (r.getKeyword().equals(ir.getKeyword())) { exists = true; break; }
                        }
                        if (!exists) { ir.setLocked(true); rules.add(ir); added++; }
                    }
                    setFeedback("\u00a7a\u2714 " + added + " Regeln importiert!", false);
                } else {
                    setFeedback("\u00a7cUng\u00fcltiger Import-Code.", true);
                }
            } catch (Exception e) {
                setFeedback("\u00a7cImport fehlgeschlagen: " + e.getMessage(), true);
            }
        }).dimensions(panelX + PAD + (bw + 8), by, bw, 22).build());

        // Save
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a\u2714 Save"), b -> saveAndClose())
                .dimensions(panelX + PAD + (bw + 8) * 2, by, bw, 22).build());

        // Cancel
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7c\u2716"), b -> close())
                .dimensions(panelX + PAD + (bw + 8) * 3, by, bw, 22).build());
    }

    private void addRule() {
        String kw = addField.getText().strip().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) return;
        for (FilterRule r : rules) {
            if (r.getKeyword().equals(kw)) {
                setFeedback("\u00a7c\"" + kw + "\" existiert bereits.", true);
                return;
            }
        }
        FilterRule rule = new FilterRule(kw);
        rule.setLocked(true);
        rules.add(rule);
        addField.setText("");
        scrollOffset = Math.max(0, rules.size() - visibleRows());
        setFeedback("\u00a7a\u2714 \"" + kw + "\" hinzugef\u00fcgt.", false);
    }

    private void removeRule(int idx) {
        if (idx < 0 || idx >= rules.size()) return;
        String kw = rules.remove(idx).getKeyword();
        clampScroll();
        setFeedback("\u00a7c\u2716 \"" + kw + "\" entfernt.", false);
    }

    private void editRule(int idx) {
        if (idx < 0 || idx >= rules.size()) return;
        FilterRule rule = rules.get(idx);
        client.setScreen(new FilterRuleEditScreen(this, new FilterRule(rule), updated -> {
            updated.setLocked(true);
            rules.set(idx, updated);
        }));
    }

    private void saveData() {
        AdminConfig.PlayerData pd = AdminConfig.getOrCreatePlayer(playerName);
        pd.rules = new ArrayList<>(rules);
        pd.banned = banned;
        AdminConfig.save();
    }

    private void saveAndClose() {
        saveData();
        close();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private void setFeedback(String msg, boolean isError) {
        feedback = msg;
        feedbackIsError = isError;
        feedbackTicks = 120;
    }

    @Override
    public void tick() {
        if (feedbackTicks > 0 && --feedbackTicks == 0) feedback = null;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeColors t = colors;

        ctx.fill(0, 0, width, height, t.bg);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, t.panel);
        drawBorder(ctx, panelX, panelY, panelW, panelH, t.border);

        // Red accent (admin screens)
        ctx.fill(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + 4, 0xFFFF4444);
        ctx.fill(panelX + 2, panelY + 4, panelX + panelW - 2, panelY + 5, 0x55FF4444);

        // Header
        ctx.fill(panelX + 2, panelY + 5, panelX + panelW - 2, panelY + 44, t.panelHead);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7c\u00a7l\u270e " + playerName),
                panelX + panelW / 2, panelY + 12, 0xFFFF6666);

        String statusTxt = rules.size() + " Regeln" + (banned ? "  \u00a7c\u26d4 GEBANNT" : "  \u00a7aAktiv");
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(statusTxt),
                panelX + panelW / 2, panelY + 28, t.subtext);
        ctx.fill(panelX + 2, panelY + 43, panelX + panelW - 2, panelY + 44, 0x55FF4444);

        // Legend
        int legendY = panelY + HEADER_H - 20;
        String legend = "\u00a74\u2666\u00a77Locked  \u00a7c\u25a0\u00a77Block  \u00a76***\u00a77Zensur  "
                + "\u00a7e\u26a0\u00a77Warn  \u00a7660s\u00a77Timeout";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(legend),
                panelX + panelW / 2, legendY, t.subtext);

        // List
        renderList(ctx, mx, my, t);

        // Footer separator
        ctx.fill(panelX + 2, listBottom, panelX + panelW - 2, listBottom + 1, 0x55FF4444);

        // Feedback (between input and buttons)
        if (feedback != null && feedbackTicks > 0) {
            float alpha = Math.min(1f, feedbackTicks / 20f);
            int a = ((int)(alpha * 255) & 0xFF) << 24;
            int col = (feedbackIsError ? 0xFF5555 : 0x7CFFB2) | a;
            ctx.drawTextWithShadow(textRenderer, Text.literal(feedback),
                    panelX + PAD, panelY + panelH - 58, col);
        }

        super.render(ctx, mx, my, delta);
    }

    private void renderList(DrawContext ctx, int mx, int my, ThemeColors t) {
        ctx.enableScissor(panelX + 2, listTop, panelX + panelW - 2, listBottom);

        if (rules.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Keine Regeln \u2014 f\u00fcge ein Keyword hinzu!"),
                    panelX + panelW / 2, listTop + (listBottom - listTop) / 2 - 4, t.subtext);
            ctx.disableScissor();
            return;
        }

        int visible = visibleRows();
        for (int vi = scrollOffset; vi < Math.min(rules.size(), scrollOffset + visible); vi++) {
            FilterRule rule = rules.get(vi);
            int ry = listTop + (vi - scrollOffset) * ITEM_H;

            boolean rHov = mx >= panelX + PAD && mx <= panelX + panelW - PAD
                    && my >= ry && my < ry + ITEM_H;
            boolean delHov = mx >= panelX + panelW - PAD - 30 && mx <= panelX + panelW - PAD
                    && my >= ry + 3 && my < ry + ITEM_H - 3;

            int rowBg = rHov ? t.rowHover : (vi % 2 == 0 ? t.rowEven : t.rowAccent);
            ctx.fill(panelX + PAD, ry, panelX + panelW - PAD, ry + ITEM_H, rowBg);
            ctx.fill(panelX + PAD, ry + ITEM_H - 1, panelX + panelW - PAD, ry + ITEM_H,
                    mixColor(t.border, 0x30000000));

            // Lock indicator (always locked in admin mode)
            ctx.fill(panelX + PAD, ry + 1, panelX + PAD + 3, ry + ITEM_H - 1, 0xFFFF4444);
            ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a74\u2666"),
                    panelX + PAD + 7, ry + 11, t.text);

            // Keyword
            String kw = rule.getKeyword();
            if (kw.length() > 22) kw = kw.substring(0, 20) + "\u2026";
            ctx.drawTextWithShadow(textRenderer, Text.literal(kw),
                    panelX + PAD + 22, ry + 11, t.text);

            // Action icons
            String icons = rule.getActionIcons();
            int iconsW = textRenderer.getWidth(icons);
            ctx.drawTextWithShadow(textRenderer, Text.literal(icons),
                    panelX + panelW - PAD - 36 - iconsW, ry + 11, t.text);

            // Delete button
            int dbx = panelX + panelW - PAD - 30;
            ctx.fill(dbx, ry + 4, panelX + panelW - PAD, ry + ITEM_H - 4,
                    delHov ? 0x55FF3333 : 0x18FF4444);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7c\u2716"),
                    dbx + 15, ry + 11, delHov ? 0xFFFF5555 : 0xFF884444);
        }
        ctx.disableScissor();
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mx = click.x(), my = click.y();

        int visible = visibleRows();
        for (int vi = scrollOffset; vi < Math.min(rules.size(), scrollOffset + visible); vi++) {
            int ry = listTop + (vi - scrollOffset) * ITEM_H;
            if (my < ry || my >= ry + ITEM_H) continue;
            if (mx < panelX + PAD || mx > panelX + panelW - PAD) continue;

            if (mx >= panelX + panelW - PAD - 30) {
                removeRule(vi);
                return true;
            }
            editRule(vi);
            return true;
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horiz, double vert) {
        if (vert != 0) {
            scrollOffset -= (int) Math.signum(vert);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, horiz, vert);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int key = keyInput.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (addField != null && addField.isFocused()) { addRule(); return true; }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_UP) { scrollOffset--; clampScroll(); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scrollOffset++; clampScroll(); return true; }
        return super.keyPressed(keyInput);
    }

    @Override public boolean shouldPause() { return false; }

    // ── Utility ──────────────────────────────────────────────────────────────
}
