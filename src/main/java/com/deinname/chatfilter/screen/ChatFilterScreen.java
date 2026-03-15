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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.deinname.chatfilter.screen.UIHelper.*;

@Environment(EnvType.CLIENT)
public final class ChatFilterScreen extends Screen {

    // ── Fixed colors ─────────────────────────────────────────────────────────
    private static final int SUCCESS     = 0xFF7CFFB2;
    private static final int ERROR_COL   = 0xFFFF5555;
    private static final int ENABLED_COL = 0xFF55FF55;
    private static final int DISABLED_COL= 0xFF555555;
    private static final int DEL_IDLE    = 0xFF884444;
    private static final int DEL_HOV     = 0xFFFF5555;
    private static final int TOOLTIP_BG  = 0xEE181828;
    private static final int TOOLTIP_BRD = 0xFF444466;

    // ── Layout ───────────────────────────────────────────────────────────────
    private static final int PANEL_W_MAX = 500;
    private static final int ITEM_H    = 30;
    private static final int HEADER_H  = 78;
    private static final int FOOTER_H  = 100;
    private static final int PAD       = 12;

    private ThemeColors colors;
    private int panelW;
    private int panelX, panelY, panelH;
    private int listTop, listBottom;

    private final Screen parent;
    private final List<FilterRule> draft;
    private final List<FilterRule> adminRules;
    private TextFieldWidget inputField;
    private TextFieldWidget searchField;
    private int scrollOffset = 0;
    private String feedback = null;
    private boolean feedbackIsError = false;
    private int feedbackTicks = 0;
    private boolean dirty = false;
    private String searchQuery = "";
    private List<Integer> filteredIndices = new ArrayList<>();

    // Tooltip
    private String tooltipText = null;
    private int tooltipX, tooltipY;

    public ChatFilterScreen(Screen parent) {
        super(Text.literal("Columba"));
        this.parent = parent;
        this.draft = new ArrayList<>();
        for (FilterRule r : ChatFilterConfig.getRules()) draft.add(new FilterRule(r));
        this.adminRules = new ArrayList<>(AdminConfig.getAdminRulesForCurrentPlayer());
        colors = UIHelper.loadColors();
        rebuildFilter();
    }

    private void recalcLayout() {
        panelW     = Math.min(width - 20, PANEL_W_MAX);
        panelH     = Math.min(height - 20, 560);
        panelX     = (this.width - panelW) / 2;
        panelY     = (this.height - panelH) / 2;
        listTop    = panelY + HEADER_H;
        listBottom = panelY + panelH - FOOTER_H;
    }

    private int visibleRows() { return Math.max(1, (listBottom - listTop) / ITEM_H); }
    private int clampScroll() {
        int max = Math.max(0, filteredIndices.size() - visibleRows());
        return scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    private void rebuildFilter() {
        filteredIndices.clear();
        for (int i = 0; i < draft.size(); i++) {
            if (searchQuery.isEmpty() || draft.get(i).getKeyword().contains(searchQuery)) {
                filteredIndices.add(i);
            }
        }
        clampScroll();
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        colors = UIHelper.loadColors();
        recalcLayout();
        int headerY = panelY + 44;

        // Search field (top right of header)
        searchField = new TextFieldWidget(textRenderer,
                panelX + panelW - PAD - 140, headerY, 130, 16, Text.literal(""));
        searchField.setMaxLength(32);
        searchField.setPlaceholder(Text.literal("\u00a78Suche..."));
        searchField.setChangedListener(s -> { searchQuery = s.strip().toLowerCase(Locale.ROOT); rebuildFilter(); });
        if (!searchQuery.isEmpty()) searchField.setText(searchQuery);
        addDrawableChild(searchField);

        // Theme editor button (top left of header)
        addDrawableChild(ButtonWidget.builder(
                Text.literal("\u00a7f\u2726 " + colors.displayName),
                b -> client.setScreen(new ThemeEditorScreen(this, tc -> this.colors = tc))
        ).dimensions(panelX + PAD, headerY, 100, 16).build());

        // Admin button (only if admin)
        if (AdminConfig.isAdmin()) {
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("\u00a7c\u2726 Admin"),
                    b -> client.setScreen(new AdminScreen(this))
            ).dimensions(panelX + PAD + 104, headerY, 60, 16).build());
        }

        // Relay button (admin only — non-admins are auto-configured)
        int settingsBtnX;
        if (AdminConfig.isAdmin()) {
            int syncX = panelX + PAD + 168;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(com.deinname.chatfilter.RelaySync.isEnabled()
                            ? "\u00a7a\u25cf Sync" : "\u00a78\u25cb Sync"),
                    b -> client.setScreen(new RelaySettingsScreen(this))
            ).dimensions(syncX, headerY, 54, 16).build());
            settingsBtnX = syncX + 58;
        } else {
            settingsBtnX = panelX + PAD + 104;
        }

        // Settings button
        addDrawableChild(ButtonWidget.builder(
                Text.literal("\u00a77\u2699"),
                b -> client.setScreen(new SettingsScreen(this))
        ).dimensions(settingsBtnX, headerY, 20, 16).build());

        // Input field
        int inputY = panelY + panelH - FOOTER_H + PAD;
        inputField = new TextFieldWidget(textRenderer,
                panelX + PAD, inputY,
                panelW - PAD * 2 - 120, 20, Text.literal(""));
        inputField.setMaxLength(ChatFilterConfig.MAX_WORD_LENGTH);
        inputField.setPlaceholder(Text.literal("\u00a78Neues Wort eingeben... (Enter)"));
        addDrawableChild(inputField);
        setInitialFocus(inputField);

        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a+ Hinzuf\u00fcgen"), b -> commitAddWord())
                .dimensions(panelX + panelW - PAD - 112, inputY, 112, 20).build());

        // Footer buttons
        int bw = (panelW - PAD * 2 - 16) / 4;
        int by = panelY + panelH - 38;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a77\u2191 Export"), b -> doExport())
                .dimensions(panelX + PAD, by, bw, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a77\u2193 Import"), b -> doImport())
                .dimensions(panelX + PAD + bw + 4, by, bw, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a\u2714 Speichern"), b -> saveAndClose())
                .dimensions(panelX + PAD + (bw + 4) * 2, by, bw + 4, 22).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7c\u2716 Abbrechen"), b -> close())
                .dimensions(panelX + PAD + (bw + 4) * 3, by, bw, 22).build());
    }

    @Override
    public void resize(net.minecraft.client.MinecraftClient client, int w, int h) {
        String si = inputField != null ? inputField.getText() : "";
        String sq = searchField != null ? searchField.getText() : "";
        super.resize(client, w, h);
        if (inputField != null) inputField.setText(si);
        if (searchField != null) searchField.setText(sq);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void commitAddWord() {
        String word = inputField.getText().strip().toLowerCase(Locale.ROOT);
        if (word.isEmpty()) return;
        if (word.length() > ChatFilterConfig.MAX_WORD_LENGTH) {
            setFeedback("Zu lang (max. " + ChatFilterConfig.MAX_WORD_LENGTH + " Z.).", true);
            return;
        }
        for (FilterRule r : draft) {
            if (r.getKeyword().equals(word)) {
                setFeedback("\"" + word + "\" ist bereits vorhanden.", true);
                return;
            }
        }
        draft.add(new FilterRule(word));
        inputField.setText("");
        dirty = true;
        rebuildFilter();
        scrollOffset = Math.max(0, filteredIndices.size() - visibleRows());
        setFeedback("\u00a7a\u2714 \"" + word + "\" hinzugef\u00fcgt.", false);
    }

    private void removeRule(int draftIndex) {
        if (draftIndex < 0 || draftIndex >= draft.size()) return;
        String kw = draft.remove(draftIndex).getKeyword();
        dirty = true;
        rebuildFilter();
        setFeedback("\u00a7c\u2716 \"" + kw + "\" entfernt.", false);
    }

    private void editRule(int draftIndex) {
        if (draftIndex < 0 || draftIndex >= draft.size()) return;
        FilterRule rule = draft.get(draftIndex);
        client.setScreen(new FilterRuleEditScreen(this, new FilterRule(rule), updated -> {
            draft.set(draftIndex, updated);
            dirty = true;
        }));
    }

    private void saveAndClose() {
        ChatFilterConfig.setRules(draft);
        dirty = false;
        close();
    }

    @Override
    public void close() {
        // Auto-save on close
        if (dirty) {
            ChatFilterConfig.setRules(draft);
            dirty = false;
        }
        if (client != null) client.setScreen(parent);
    }

    private void doExport() {
        ChatFilterConfig.setRules(draft);
        dirty = false;
        Path path = ChatFilterConfig.exportRules();
        setFeedback(path != null
                ? "\u00a7aExportiert: \u00a77" + path.getFileName()
                : "\u00a7cExport fehlgeschlagen.", path == null);
    }

    private void doImport() {
        List<FilterRule> imported = ChatFilterConfig.readExportFile();
        if (imported == null) {
            setFeedback("\u00a7cDatei nicht gefunden: chatfilter_shared.json", true);
            return;
        }
        Set<String> existing = new HashSet<>();
        for (FilterRule r : draft) existing.add(r.getKeyword());
        int added = 0;
        for (FilterRule r : imported) {
            if (!existing.contains(r.getKeyword())) {
                draft.add(new FilterRule(r));
                added++;
            }
        }
        if (added > 0) { dirty = true; rebuildFilter(); }
        setFeedback(added > 0
                ? "\u00a7a" + added + " Regel(n) importiert."
                : "\u00a7eKeine neuen Regeln.", added == 0);
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
        tooltipText = null;

        // Full-screen background
        ctx.fill(0, 0, width, height, t.bg);

        // Panel body
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, t.panel);

        // Border (2px)
        drawBorder(ctx, panelX, panelY, panelW, panelH, t.border);

        // Top accent stripe (3px gradient effect)
        ctx.fill(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + 4, t.accent);
        ctx.fill(panelX + 2, panelY + 4, panelX + panelW - 2, panelY + 5, t.accentGlow);

        // Header area
        ctx.fill(panelX + 2, panelY + 5, panelX + panelW - 2, panelY + 40, t.panelHead);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7l\u2726 Columba" + (dirty ? " \u00a7e\u00a7l*" : "")),
                panelX + panelW / 2, panelY + 12, t.accent);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(draft.size() + " Regeln"
                        + (adminRules.isEmpty() ? "" : " + " + adminRules.size() + " \u00a74Admin")
                        + (!searchQuery.isEmpty() ? " \u00a78| \u00a7f" + filteredIndices.size() + " gefunden" : "")),
                panelX + panelW / 2, panelY + 26, t.subtext);
        ctx.fill(panelX + 2, panelY + 39, panelX + panelW - 2, panelY + 40, t.accentGlow);

        // Legend bar (below search/theme buttons)
        int legendY = panelY + HEADER_H - 14;
        ctx.fill(panelX + 2, legendY - 2, panelX + panelW - 2, legendY + 12, mixColor(t.panel, 0x10FFFFFF));
        String legend = "\u00a74\u2666\u00a77Lock  \u00a7c\u25a0\u00a77Block  \u00a76***\u00a77Zensur  \u00a7a\u2702\u00a77Strip  "
                + "\u00a7e\u26a0\u00a77Warn  \u00a7b\u21bb\u00a77Ersatz  "
                + "\u00a7d?\u00a77Confirm  \u00a74\u23cf\u00a77DC";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(legend),
                panelX + panelW / 2, legendY, t.subtext);

        // List area
        renderList(ctx, mx, my, t);
        renderScrollbar(ctx, t);

        // Footer separator
        ctx.fill(panelX + 2, listBottom, panelX + panelW - 2, listBottom + 1, t.accentGlow);

        // Feedback
        if (feedback != null && feedbackTicks > 0) {
            float alpha = Math.min(1f, feedbackTicks / 20f);
            int feedAlpha = ((int)(alpha * 255) & 0xFF) << 24;
            int feedColor = (feedbackIsError ? 0xFF5555 : 0x7CFFB2) | feedAlpha;
            ctx.drawTextWithShadow(textRenderer, Text.literal(feedback),
                    panelX + PAD, panelY + panelH - FOOTER_H + PAD + 24, feedColor);
        }

        super.render(ctx, mx, my, delta);

        // Tooltip (render last, on top)
        if (tooltipText != null) {
            int tw = textRenderer.getWidth(tooltipText) + 8;
            int tx = Math.min(tooltipX + 8, width - tw - 4);
            int ty = Math.max(4, tooltipY - 14);
            ctx.fill(tx - 2, ty - 2, tx + tw + 2, ty + 12, TOOLTIP_BG);
            ctx.fill(tx - 2, ty - 2, tx + tw + 2, ty - 1, TOOLTIP_BRD);
            ctx.drawTextWithShadow(textRenderer, Text.literal(tooltipText), tx + 2, ty, 0xFFFFFFFF);
        }
    }

    private void renderList(DrawContext ctx, int mx, int my, ThemeColors t) {
        ctx.enableScissor(panelX + 2, listTop, panelX + panelW - 2, listBottom);

        // Build combined view: own filtered rules + admin rules
        List<FilterRule> combined = new ArrayList<>();
        List<Integer> combinedDraftIdx = new ArrayList<>(); // -1 = admin rule
        for (int fi : filteredIndices) {
            combined.add(draft.get(fi));
            combinedDraftIdx.add(fi);
        }
        for (FilterRule ar : adminRules) {
            if (searchQuery.isEmpty() || ar.getKeyword().contains(searchQuery)) {
                combined.add(ar);
                combinedDraftIdx.add(-1);
            }
        }

        if (combined.isEmpty()) {
            String msg = searchQuery.isEmpty()
                    ? "Keine Regeln vorhanden \u2014 f\u00fcge ein Wort hinzu!"
                    : "Keine Treffer f\u00fcr \"" + searchQuery + "\"";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg),
                    panelX + panelW / 2, listTop + (listBottom - listTop) / 2 - 4, t.subtext);
            ctx.disableScissor();
            return;
        }

        int visible = visibleRows();
        int totalRows = combined.size();
        for (int vi = scrollOffset; vi < Math.min(totalRows, scrollOffset + visible); vi++) {
            FilterRule rule = combined.get(vi);
            boolean isAdmin = combinedDraftIdx.get(vi) == -1;
            int ry = listTop + (vi - scrollOffset) * ITEM_H;

            boolean rHov = mx >= panelX + PAD && mx <= panelX + panelW - PAD
                        && my >= ry && my < ry + ITEM_H;
            boolean delHov = !isAdmin && mx >= panelX + panelW - PAD - 30
                           && mx <= panelX + panelW - PAD
                           && my >= ry + 3 && my < ry + ITEM_H - 3;

            // Row bg
            int rowBg = rHov ? t.rowHover : (vi % 2 == 0 ? t.rowEven : t.rowAccent);
            ctx.fill(panelX + PAD, ry, panelX + panelW - PAD, ry + ITEM_H, rowBg);
            ctx.fill(panelX + PAD, ry + ITEM_H - 1, panelX + panelW - PAD, ry + ITEM_H,
                    mixColor(t.border, 0x30000000));

            // Left bar: red for locked, green/gray for own
            int barColor = isAdmin ? 0xFFFF4444
                    : (rule.isEnabled() ? ENABLED_COL : DISABLED_COL);
            ctx.fill(panelX + PAD, ry + 1, panelX + PAD + 3, ry + ITEM_H - 1, barColor);

            // Status indicator
            String dot = isAdmin ? "\u00a74\u2666" :
                    (rule.isEnabled() ? "\u00a7a\u25cf" : "\u00a78\u25cb");
            ctx.drawTextWithShadow(textRenderer, Text.literal(dot),
                    panelX + PAD + 7, ry + 11, t.text);

            // Keyword
            String kw = rule.getKeyword();
            if (kw.length() > 24) kw = kw.substring(0, 22) + "\u2026";
            int kwColor = isAdmin ? 0xFFFF8888 : (rule.isEnabled() ? t.text : t.subtext);
            ctx.drawTextWithShadow(textRenderer, Text.literal(kw),
                    panelX + PAD + 22, ry + 11, kwColor);

            // Action icons
            String icons = rule.getActionIcons();
            int iconsWidth = textRenderer.getWidth(icons);
            int iconsX = panelX + panelW - PAD - 36 - iconsWidth;
            ctx.drawTextWithShadow(textRenderer, Text.literal(icons), iconsX, ry + 11, t.text);

            // Tooltip
            if (rHov && mx >= iconsX && mx <= iconsX + iconsWidth) {
                tooltipText = getActionTooltip(rule)
                        + (isAdmin ? " \u00a74[Locked]" : "");
                tooltipX = mx;
                tooltipY = ry;
            }

            // Delete button (only for own rules)
            if (!isAdmin) {
                int dbx = panelX + panelW - PAD - 30;
                ctx.fill(dbx, ry + 4, panelX + panelW - PAD, ry + ITEM_H - 4,
                        delHov ? 0x55FF3333 : 0x18FF4444);
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7c\u2716"),
                        dbx + 15, ry + 11, delHov ? DEL_HOV : DEL_IDLE);
            } else {
                // Lock icon instead of delete
                int dbx = panelX + panelW - PAD - 30;
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a74\u2666"),
                        dbx + 15, ry + 11, 0xFF884444);
            }
        }
        ctx.disableScissor();
    }

    private void renderScrollbar(DrawContext ctx, ThemeColors t) {
        int adminCount = 0;
        for (FilterRule ar : adminRules) {
            if (searchQuery.isEmpty() || ar.getKeyword().contains(searchQuery)) adminCount++;
        }
        int total = filteredIndices.size() + adminCount;
        int visible = visibleRows();
        if (total <= visible) return;
        int barH   = listBottom - listTop;
        int thumbH = Math.max(20, barH * visible / total);
        int thumbY = listTop + scrollOffset * (barH - thumbH) / Math.max(1, total - visible);
        ctx.fill(panelX + panelW - 7, listTop, panelX + panelW - 2, listBottom,
                mixColor(t.panel, 0x22FFFFFF));
        ctx.fill(panelX + panelW - 6, thumbY + 1, panelX + panelW - 3, thumbY + thumbH - 1,
                t.accent);
    }

    private static String getActionTooltip(FilterRule r) {
        List<String> parts = new ArrayList<>();
        if (r.isLocked()) parts.add("Gesperrt");
        if (r.isBlockMessage()) parts.add("Blockieren");
        if (r.isCensorWord()) parts.add("Zensieren");
        if (r.isStripWord()) parts.add("Entfernen");
        if (r.isShowWarning()) parts.add("Warnung");
        if (r.isSendReplacement()) parts.add("Ersetzen");
        if (r.isRequireConfirmation()) parts.add("Best\u00e4tigung");
        if (r.isDisconnectFromServer()) parts.add("Disconnect");
        if (r.getTimeoutSeconds() > 0) parts.add("Timeout:" + r.getTimeoutSeconds() + "s");
        return parts.isEmpty() ? "Keine Aktionen" : String.join(", ", parts);
    }

    // ── Input handling ───────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mx = click.x();
        double my = click.y();

        // Build same combined list as renderList
        List<Integer> combinedDraftIdx = new ArrayList<>(filteredIndices);
        for (FilterRule ar : adminRules) {
            if (searchQuery.isEmpty() || ar.getKeyword().contains(searchQuery)) {
                combinedDraftIdx.add(-1);
            }
        }

        int visible = visibleRows();
        int totalRows = combinedDraftIdx.size();
        for (int vi = scrollOffset; vi < Math.min(totalRows, scrollOffset + visible); vi++) {
            int ry = listTop + (vi - scrollOffset) * ITEM_H;
            if (my < ry || my >= ry + ITEM_H) continue;
            if (mx < panelX + PAD || mx > panelX + panelW - PAD) continue;

            int draftIdx = combinedDraftIdx.get(vi);
            boolean isAdmin = draftIdx == -1;

            // Admin rules are read-only
            if (isAdmin) return true;

            if (mx >= panelX + panelW - PAD - 30) {
                removeRule(draftIdx);
                return true;
            }
            if (mx <= panelX + PAD + 20) {
                draft.get(draftIdx).setEnabled(!draft.get(draftIdx).isEnabled());
                dirty = true;
                return true;
            }
            editRule(draftIdx);
            return true;
        }
        return super.mouseClicked(click, bl);
    }

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
        int mods = keyInput.modifiers();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (inputField != null && inputField.isFocused()) {
                commitAddWord();
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_S && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            saveAndClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            if (searchField != null) { setFocused(searchField); return true; }
        }
        if (key == GLFW.GLFW_KEY_UP) { scrollOffset--; clampScroll(); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scrollOffset++; clampScroll(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(keyInput);
    }

    @Override public boolean shouldPause() { return false; }
}
