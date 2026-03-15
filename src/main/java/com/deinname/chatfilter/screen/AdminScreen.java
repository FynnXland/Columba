package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.AdminConfig;
import com.deinname.chatfilter.AutoUpdater;
import com.deinname.chatfilter.ChatFilterConfig;
import com.deinname.chatfilter.ChatFilterMod;
import com.deinname.chatfilter.EmbeddedRelay;
import com.deinname.chatfilter.NgrokTunnel;
import com.deinname.chatfilter.PlayerHeadCache;
import com.deinname.chatfilter.RelaySync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static com.deinname.chatfilter.screen.UIHelper.*;

/**
 * Admin Panel v2.1 – clean, tabbed layout.
 *
 * Tab 0 – Spieler   : list of managed players
 * Tab 1 – Control  : troll controls + screenshot for selected player
 * Tab 2 – Relay     : quick relay status
 */
@Environment(EnvType.CLIENT)
public final class AdminScreen extends Screen {

    private static final Identifier ICON_TEXTURE = Identifier.of("columba", "icon.png");

    // ── Layout ───────────────────────────────────────────────────────────────
    private static final int PANEL_W_MAX = 580;
    private static final int PANEL_H_MAX = 540;
    private static final int TAB_H    = 28;
    private static final int HEADER_H = 52;
    private static final int FOOTER_H = 48;
    private static final int ITEM_H   = 36;
    private static final int PAD      = 12;

    private ThemeColors colors;
    private int panelW;
    private int px, py, panelH;
    private int listTop, listBottom;

    private final Screen parent;
    private List<String> playerNames;
    private int selectedIdx = -1;    // selected player in list
    private int activeTab   = 0;     // 0=Players, 1=Control, 2=Relay, 3=Debug, 4=Settings
    private int scrollOffset = 0;
    private int trollScrollOffset = 0;
    private int syncScrollOffset = 0;   // scroll for player monitor in Sync tab
    private int debugScrollOffset = 0;  // scroll for debug log tab
    private int settingsScrollOffset = 0; // scroll for settings tab
    private int syncContentHeight = 0;  // total content height in sync player monitor
    private int debugContentHeight = 0; // total content height in debug log

    private TextFieldWidget addField;
    private TextFieldWidget chatField;  // fake chat input in troll tab
    private TextFieldWidget connectField; // server IP input in troll tab
    private TextFieldWidget deathMsgField; // custom death message input in settings tab
    private ButtonWidget sendBtn;       // send button for fake chat
    private String feedback = null;
    private boolean feedbackOk = true;
    private int feedbackTicks = 0;
    private long lastAutoPing = 0; // auto-ping timer for sync tab

    public AdminScreen(Screen parent) {
        super(Text.literal("Columba Admin"));
        this.parent = parent;
        this.playerNames = new ArrayList<>(AdminConfig.getPlayerNames());
        this.colors = UIHelper.loadColors();
        PlayerHeadCache.prefetchAll(playerNames, () -> {});
        if (AdminConfig.isAdmin()) AdminConfig.pingAllPlayers();
    }

    private void recalc() {
        panelW     = Math.min(width - 20, PANEL_W_MAX);
        panelH     = Math.min(height - 20, PANEL_H_MAX);
        px         = (width - panelW) / 2;
        py         = (height - panelH) / 2;
        listTop    = py + HEADER_H + TAB_H + 4;
        listBottom = py + panelH - FOOTER_H;
    }

    private int visibleRows() { return Math.max(1, (listBottom - listTop) / ITEM_H); }

    private void clampScroll() {
        int max = Math.max(0, playerNames.size() - visibleRows());
        scrollOffset = Math.max(0, Math.min(scrollOffset, max));
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        recalc();
        clearChildren();

        // Add player field (shown on tabs EXCEPT Control and Settings)
        int inputY = py + panelH - FOOTER_H + 10;
        if (activeTab != 1 && activeTab != 4) {
            addField = new TextFieldWidget(textRenderer,
                    px + PAD, inputY, panelW - PAD * 2 - 110, 20, Text.literal(""));
            addField.setMaxLength(32);
            addField.setPlaceholder(Text.literal("\u00a78Spielername..."));
            addDrawableChild(addField);

            addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7a\u271a Hinzuf\u00fcgen"), b -> addPlayer())
                    .dimensions(px + panelW - PAD - 102, inputY, 102, 20).build());
        } else {
            addField = null;
        }

        // Death message input (shown on Settings tab in footer area)
        if (activeTab == 4) {
            deathMsgField = new TextFieldWidget(textRenderer,
                    px + PAD, inputY, panelW - PAD * 2 - 110, 20, Text.literal(""));
            deathMsgField.setMaxLength(128);
            deathMsgField.setText(AdminConfig.deathMessage);
            deathMsgField.setPlaceholder(Text.literal("\u00a78Death-Text (%name% = Spielername)"));
            addDrawableChild(deathMsgField);

            addDrawableChild(ButtonWidget.builder(Text.literal("\u00a7c\u2714 Setzen"), b -> {
                if (deathMsgField != null && !deathMsgField.getText().trim().isEmpty()) {
                    AdminConfig.deathMessage = deathMsgField.getText().trim();
                    setFeedback("\u00a7cDeath-Text: " + AdminConfig.deathMessage.replace("%name%", "?"), true);
                }
            }).dimensions(px + panelW - PAD - 102, inputY, 102, 20).build());
        } else {
            deathMsgField = null;
        }

        // Tab buttons
        String[] tabs = {"\u00a7f\u25a3 Spieler", "\u00a76\uD83C\uDFAE Control", "\u00a7b\u2630 Sync", "\u00a78\u2261 Log", "\u00a7e\u2699 Settings"};
        int tw = (panelW - PAD * 2 - (tabs.length - 1) * 4) / tabs.length;
        int ty = py + HEADER_H;
        for (int i = 0; i < tabs.length; i++) {
            final int ti = i;
            addDrawableChild(ButtonWidget.builder(Text.literal(tabs[i]), b -> {
                int oldTab = activeTab;
                activeTab = ti;
                // Dynamic status rate: faster when troll tab is active
                if (ti == 1 && oldTab != 1) sendRateToAll(100);   // 5s on troll tab
                if (ti != 1 && oldTab == 1) sendRateToAll(200);  // 10s otherwise
                init();
            }).dimensions(px + PAD + i * (tw + 4), ty, tw, TAB_H - 4).build());
        }

        // Chat input field for control tab (in footer area)
        chatField = new TextFieldWidget(textRenderer,
                px + PAD, inputY, panelW - PAD * 2 - 80, 20, Text.literal(""));
        chatField.setMaxLength(256);
        chatField.setPlaceholder(Text.literal("\u00a78Nachricht oder /befehl senden..."));
        chatField.setVisible(activeTab == 1);
        addDrawableChild(chatField);

        sendBtn = ButtonWidget.builder(Text.literal("\u00a7e\u2709 Senden"), b -> sendFakeChat())
                .dimensions(px + panelW - PAD - 72, inputY, 72, 20).build();
        sendBtn.visible = (activeTab == 1);
        addDrawableChild(sendBtn);

        // Server IP input field for connect command (in footer, above chat field)
        connectField = new TextFieldWidget(textRenderer,
                px + PAD, inputY - 24, panelW - PAD * 2 - 80, 20, Text.literal(""));
        connectField.setMaxLength(256);
        connectField.setPlaceholder(Text.literal("\u00a78Server-IP eingeben (z.B. mc.server.de)"));
        connectField.setVisible(activeTab == 1);
        addDrawableChild(connectField);
    }

    private void addPlayer() {
        if (addField == null) return;
        String name = addField.getText().strip();
        if (name.isEmpty()) return;
        if (playerNames.contains(name)) {
            setFeedback("\u00a7c\"" + name + "\" existiert bereits.", false);
            return;
        }
        AdminConfig.getOrCreatePlayer(name);
        playerNames.add(name);
        addField.setText("");
        selectedIdx = playerNames.size() - 1;
        scrollOffset = Math.max(0, playerNames.size() - visibleRows());
        setFeedback("\u00a7e\u231b UUID wird abgerufen...", true);
        // Fetch UUID + head async
        PlayerHeadCache.fetchAsync(name, () ->
                setFeedback("\u00a7a\u2714 \"" + name + "\" hinzugef\u00fcgt. Skin geladen!", true));
    }

    private void setFeedback(String msg, boolean ok) {
        feedback = msg; feedbackOk = ok; feedbackTicks = 140;
    }

    @Override public void tick() {
        if (feedbackTicks > 0 && --feedbackTicks == 0) feedback = null;
    }

    @Override
    public void close() {
        // Reset status rate to default (60s) when leaving admin panel
        sendRateToAll(200);
        AdminConfig.save();
        if (client != null) client.setScreen(parent);
    }

    /** Send a RATE command to all managed players. */
    private void sendRateToAll(int ticks) {
        for (String name : playerNames) {
            AdminConfig.sendTrollCommand(name, "RATE:" + ticks);
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeColors t = colors;

        // Auto-ping when sync tab is active (every 20 seconds)
        if (activeTab == 2) {
            long now = System.currentTimeMillis();
            if (now - lastAutoPing > 20_000) {
                lastAutoPing = now;
                AdminConfig.pingAllPlayers();
            }
        }

        // Hide chat field + send button visibility
        if (chatField != null) chatField.setVisible(activeTab == 1);
        if (sendBtn != null) sendBtn.visible = (activeTab == 1);

        // Background
        ctx.fill(0, 0, width, height, mixA(t.bg, 0xCC000000));
        ctx.fill(px, py, px + panelW, py + panelH, t.panel);

        // Red admin accent top bar
        ctx.fill(px, py, px + panelW, py + 3, 0xFFFF4455);
        ctx.fill(px, py + 3, px + panelW, py + 4, 0x44FF4455);

        // Header
        ctx.fill(px, py + 4, px + panelW, py + HEADER_H, t.panelHead);

        // Dove icon (16×16 next to title)
        String titleStr = "\u00a7f\u00a7l\u2726 Columba \u00a7r\u00a77v" + ChatFilterMod.VERSION;
        int titleW = textRenderer.getWidth(Text.literal(titleStr));
        int iconSize = 16;
        int totalW = iconSize + 4 + titleW;
        int startX = px + panelW / 2 - totalW / 2;
        int titleY = py + 10;
        ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, ICON_TEXTURE,
                startX, titleY - 2, 0, 0, iconSize, iconSize, iconSize, iconSize);
        ctx.drawTextWithShadow(textRenderer, Text.literal(titleStr),
                startX + iconSize + 4, titleY, 0xFFFF7788);

        // Player count + online status + selected player (merged into one line)
        int online = AdminConfig.countOnline(playerNames);
        StringBuilder subLine = new StringBuilder();
        subLine.append(playerNames.size()).append(" Spieler  \u00a77|\u00a7r  ");
        subLine.append(online > 0 ? "\u00a7a" + online + " online" : "\u00a7c0 online");
        if (selectedIdx >= 0 && selectedIdx < playerNames.size()) {
            subLine.append("  \u00a77|\u00a7r  \u00a7f\u00a7l").append(playerNames.get(selectedIdx));
        }
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(subLine.toString()),
                px + panelW / 2, py + 27, t.subtext);

        // Update button (top-right corner of header)
        if (AutoUpdater.isUpdateReady()) {
            String updLabel = "\u00a7a\u2B07 Update v" + AutoUpdater.getLatestVersion();
            int updW = textRenderer.getWidth(Text.literal(updLabel)) + 12;
            int updH = 14;
            int updX = px + panelW - updW - 8;
            int updY = py + 8;
            boolean updHov = mx >= updX && mx <= updX + updW && my >= updY && my <= updY + updH;
            ctx.fill(updX - 1, updY - 1, updX + updW + 1, updY + updH + 1, updHov ? 0xFF55FF55 : 0xFF33AA33);
            ctx.fill(updX, updY, updX + updW, updY + updH, updHov ? 0xFF225522 : 0xFF1A3A1A);
            ctx.drawTextWithShadow(textRenderer, Text.literal(updLabel),
                    updX + 6, updY + 3, 0xFF55FF55);
        }

        // Border
        drawBorder(ctx, px, py, panelW, panelH, t.border);

        // Tab buttons – active tab gets accent background
        int ty = py + HEADER_H;
        int tw = (panelW - PAD * 2 - 16) / 5;
        String[] tabLabels = {"\u00a7f\u25a3 Spieler", "\u00a76\uD83C\uDFAE Control", "\u00a7b\u2630 Sync", "\u00a78\u2261 Log", "\u00a7e\u2699 Settings"};
        int[] tabAccents = {0xFF44AA66, 0xFFFF8844, 0xFF44AADD, 0xFF888888, 0xFFFFAA33};
        for (int i = 0; i < 5; i++) {
            int tx2 = px + PAD + i * (tw + 4);
            if (i == activeTab) {
                ctx.fill(tx2, ty, tx2 + tw, ty + TAB_H - 4, mixA(tabAccents[i], 0x30000000));
                ctx.fill(tx2, ty + TAB_H - 4, tx2 + tw, ty + TAB_H - 2, tabAccents[i]);
            }
        }
        ctx.fill(px, py + HEADER_H + TAB_H, px + panelW, py + HEADER_H + TAB_H + 1, t.border);

        // Tab content
        switch (activeTab) {
            case 0 -> renderPlayersTab(ctx, mx, my, t);
            case 1 -> renderTrollTab(ctx, mx, my, t);
            case 2 -> renderRelayTab(ctx, mx, my, t);
            case 3 -> renderDebugTab(ctx, mx, my, t);
            case 4 -> renderSettingsTab(ctx, mx, my, t);
        }

        // Footer separator
        ctx.fill(px, py + panelH - FOOTER_H, px + panelW, py + panelH - FOOTER_H + 1, t.border);

        // Feedback
        drawFeedback(ctx, textRenderer, feedback, feedbackOk, feedbackTicks,
                px + panelW / 2, py + panelH - FOOTER_H + 36);

        super.render(ctx, mx, my, delta);
    }

    // ── Tab 0: Players ────────────────────────────────────────────────────────

    private void renderPlayersTab(DrawContext ctx, int mx, int my, ThemeColors t) {
        if (playerNames.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a77Keine Spieler \u2014 tippe einen Namen unten ein"),
                    px + panelW / 2, listTop + (listBottom - listTop) / 2 - 4, t.subtext);
            return;
        }

        ctx.enableScissor(px + 2, listTop, px + panelW - 2, listBottom);
        int visible = visibleRows();
        for (int vi = scrollOffset; vi < Math.min(playerNames.size(), scrollOffset + visible); vi++) {
            String name = playerNames.get(vi);
            AdminConfig.PlayerData pd = AdminConfig.getPlayer(name);
            boolean banned = pd != null && pd.banned;
            int ruleCount = pd != null ? pd.rules.size() : 0;
            int ry = listTop + (vi - scrollOffset) * ITEM_H;
            boolean selected = vi == selectedIdx;
            boolean hov = mx >= px + PAD && mx <= px + panelW - PAD
                    && my >= ry && my < ry + ITEM_H;

            // Row
            int rowBg = selected ? 0x44226688
                    : hov ? t.rowHover
                    : (vi % 2 == 0 ? t.rowEven : t.rowAccent);
            ctx.fill(px + PAD, ry, px + panelW - PAD, ry + ITEM_H, rowBg);

            // Left indicator bar
            ctx.fill(px + PAD, ry + 1, px + PAD + 3, ry + ITEM_H - 1,
                    banned ? 0xFFFF4444 : selected ? 0xFF4488CC : 0xFF44AA66);

            // Player head pixel art / loading indicator
            int nameX = px + PAD + 8;
            int[] headPx = PlayerHeadCache.getHeadPixels(name);
            if (headPx != null) {
                // Render 8×8 face as pixel art (3px per pixel = 24×24)
                int hx = px + PAD + 6, hy = ry + 4;
                int pxSz = 3;
                ctx.fill(hx - 1, hy - 1, hx + 25, hy + 25, 0xFF1A1A1A); // border
                for (int faceY = 0; faceY < 8; faceY++) {
                    for (int faceX = 0; faceX < 8; faceX++) {
                        int color = headPx[faceY * 8 + faceX];
                        if (((color >> 24) & 0xFF) < 10) continue;
                        ctx.fill(hx + faceX * pxSz, hy + faceY * pxSz,
                                 hx + (faceX+1) * pxSz, hy + (faceY+1) * pxSz, color);
                    }
                }
                // Online dot overlay (bottom-right corner)
                boolean onl = AdminConfig.isRecentlyOnline(name);
                ctx.fill(hx + 20, hy + 20, hx + 25, hy + 25, onl ? 0xFF22CC55 : 0xFF555555);
                nameX = px + PAD + 38;
            } else if (PlayerHeadCache.isLoading(name)) {
                ctx.fill(px + PAD + 6, ry + 4, px + PAD + 32, ry + ITEM_H - 4, 0xFF222222);
                String spin = new String[]{"\u00b7", "\u00b7\u00b7", "\u00b7\u00b7\u00b7"}[
                        (int)((System.currentTimeMillis() / 400) % 3)];
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a78" + spin),
                        px + PAD + 19, ry + 12, 0xFF888888);
                nameX = px + PAD + 38;
            }

            // Name
            ctx.drawTextWithShadow(textRenderer, Text.literal(banned ? "\u00a7c" + name : "\u00a7f" + name),
                    nameX, ry + 5, t.text);

            // Subtitle (online status + server + rule count + quick stats)
            boolean isOnline = AdminConfig.isRecentlyOnline(name);
            String server = AdminConfig.getPlayerServer(name);
            long lastSeen = AdminConfig.getLastSeenTime(name);
            AdminConfig.PlayerStatus ps = AdminConfig.getPlayerStatus(name);
            String lastStr = "";
            if (lastSeen > 0) {
                long secs = (System.currentTimeMillis() - lastSeen) / 1000;
                lastStr = secs < 60 ? secs + "s" : (secs / 60) + "min";
            }
            StringBuilder sub2b = new StringBuilder();
            sub2b.append(isOnline ? "\u00a7a\u25cf " : "\u00a78\u25cb ");
            sub2b.append(ruleCount).append("R");
            if (ps != null) {
                sub2b.append(" \u00a7c\u2764").append(Math.round(ps.hp / 2));
                sub2b.append(" \u00a7a\u2605").append(ps.level);
                sub2b.append(" \u00a77").append(ps.x).append(",").append(ps.y).append(",").append(ps.z);
            }
            if (server != null) sub2b.append("  \u00a77").append(server);
            if (lastSeen > 0) sub2b.append("  \u00a78").append(lastStr);
            if (banned) sub2b.append("  \u00a7c\u26d4");
            ctx.drawTextWithShadow(textRenderer, Text.literal(sub2b.toString()),
                    nameX, ry + 19, t.subtext);

            // Action icons (right side)
            int rx = px + panelW - PAD - 2;

            // Delete button (×)
            boolean delH = mx >= rx - 22 && mx <= rx && my >= ry + 6 && my < ry + ITEM_H - 6;
            ctx.fill(rx - 22, ry + 6, rx, ry + ITEM_H - 6, delH ? 0x55FF3333 : 0x22FF4444);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(delH ? "\u00a7c\u2716" : "\u00a78\u2716"),
                    rx - 11, ry + 13, 0xFFFFFFFF);

            // Edit button (✎)
            boolean editH = mx >= rx - 50 && mx < rx - 26 && my >= ry + 6 && my < ry + ITEM_H - 6;
            ctx.fill(rx - 50, ry + 6, rx - 26, ry + ITEM_H - 6, editH ? 0x55AADDFF : 0x22AADDFF);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7b\u270e"),
                    rx - 38, ry + 13, 0xFFFFFFFF);

            // Ban toggle
            boolean banH = mx >= rx - 78 && mx < rx - 54 && my >= ry + 6 && my < ry + ITEM_H - 6;
            ctx.fill(rx - 78, ry + 6, rx - 54, ry + ITEM_H - 6,
                    banned ? (banH ? 0x55FF8800 : 0x33FF8800) : (banH ? 0x55FF4444 : 0x22FF4444));
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(banned ? "\u00a76\u26d4" : "\u00a7c\u26d4"),
                    rx - 66, ry + 13, 0xFFFFFFFF);

            // Push button
            boolean pushH = mx >= rx - 108 && mx < rx - 82 && my >= ry + 6 && my < ry + ITEM_H - 6;
            ctx.fill(rx - 108, ry + 6, rx - 82, ry + ITEM_H - 6, pushH ? 0x5500DDFF : 0x2200DDFF);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7b\u2191"),
                    rx - 95, ry + 13, 0xFFFFFFFF);

            // Info button (ℹ)
            boolean infoH = mx >= rx - 136 && mx < rx - 112 && my >= ry + 6 && my < ry + ITEM_H - 6;
            ctx.fill(rx - 136, ry + 6, rx - 112, ry + ITEM_H - 6, infoH ? 0x5588FF88 : 0x2288FF88);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7a\u2139"),
                    rx - 124, ry + 13, 0xFFFFFFFF);

            // Row divider
            ctx.fill(px + PAD, ry + ITEM_H - 1, px + panelW - PAD, ry + ITEM_H, 0x22FFFFFF);
        }
        ctx.disableScissor();

        // Scrollbar
        renderScrollbar(ctx, t, playerNames.size());
    }

    // ── Tab 1: Troll ─────────────────────────────────────────────────────────

    private void renderTrollTab(DrawContext ctx, int mx, int my, ThemeColors t) {
        if (selectedIdx < 0 || selectedIdx >= playerNames.size()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a77\u2190 Zuerst einen Spieler ausw\u00e4hlen"),
                    px + panelW / 2, listTop + 38, t.subtext);
            return;
        }

        String target = playerNames.get(selectedIdx);

        // Scissor for scrollable troll content
        ctx.enableScissor(px + 2, listTop, px + panelW - 2, listBottom);

        int cy = listTop + 6 - trollScrollOffset;
        int contentW = panelW - PAD * 2;

        // ─ Dauer-Effekte section ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a76\u2726 Dauer-Effekte", 0xFFFF8844, t);
        cy += 18;

        String[][] toggles = {
                {"\u2663 Sneak",       "SNEAK"},
                {"\u27f3 BunnyHop",    "BHOP"},
                {"\u21ba Spin",        "SPIN"},
                {"\u2744 Freeze",      "FREEZE"},
                {"\u21c4 SlotCycle",   "SLOTCYCLE"},
                {"\u2718 NoPick",      "NOPICK"},
                {"\u25a3 DVD",         "DVD"},
                {"\u2316 Zoom",        "ZOOM"},
                {"\u2b06 LookUp",      "LOOKUP"},
                {"\u2b07 LookDown",    "LOOKDOWN"},
                {"\u2694 AutoAttack",  "AUTOATTACK"},
                {"\u21c4 SwapWS",      "SWAPWS"},
        };
        int bw = (contentW - 12) / 4;
        for (int i = 0; i < toggles.length; i++) {
            int col = i % 4, row = i / 4;
            int bx = px + PAD + col * (bw + 4);
            int by = cy + row * 26;
            boolean hov = mx >= bx && mx <= bx + bw && my >= by && my < by + 22;
            boolean active = AdminConfig.isTrollActive(target, toggles[i][1]);
            // Active: green tint + left bar; Hover: accent tint; Default: row color
            int bg = active ? 0x4400FF88 : (hov ? mixA(t.accent, 0x28FFFFFF) : t.rowEven);
            ctx.fill(bx, by, bx + bw, by + 22, bg);
            // Left indicator bar (3px)
            ctx.fill(bx, by, bx + 3, by + 22, active ? 0xFF00CC66 : (hov ? t.accent : 0x33FFFFFF));
            // Top/bottom border
            ctx.fill(bx, by, bx + bw, by + 1, active ? 0xFF00CC66 : t.border);
            ctx.fill(bx, by + 21, bx + bw, by + 22, active ? 0xFF00CC66 : t.border);
            // Label with state
            String label = (active ? "\u00a7a" : "\u00a7f") + toggles[i][0];
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    bx + bw / 2, by + 7, t.text);
        }
        cy += 26 * ((toggles.length + 3) / 4) + 8;

        // ─ Sofort-Aktionen section ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a7c\u26a1 Sofort-Aktionen", 0xFFFF4466, t);
        cy += 18;

        String[][] instants = {
                {"\u2191 Springen",    "JUMP"},
                {"\u25bc Droppen",     "DROP"},
                {"\u25a1 Alles drop",  "DROPALL"},
                {"\u2b12 Inv Shuffle", "INVSHUFFLE"},
        };
        for (int i = 0; i < instants.length; i++) {
            int col = i % 4;
            int bx = px + PAD + col * (bw + 4);
            int by = cy;
            boolean hov = mx >= bx && mx <= bx + bw && my >= by && my < by + 22;
            int bg = hov ? mixA(0xFFFF6666, 0x28FFFFFF) : t.rowEven;
            ctx.fill(bx, by, bx + bw, by + 22, bg);
            ctx.fill(bx, by, bx + 3, by + 22, hov ? 0xFFFF6666 : 0x33FFFFFF);
            ctx.fill(bx, by, bx + bw, by + 1, t.border);
            ctx.fill(bx, by + 21, bx + bw, by + 22, t.border);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(instants[i][0]),
                    bx + bw / 2, by + 7, t.text);
        }
        cy += 30;

        // ─ Jumpscare section ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a74\u2620 Jumpscare", 0xFFCC2222, t);
        cy += 18;

        String[][] scares = {
                {"\uD83D\uDC7B Guardian", "ELDERGUARDIAN"},
                {"\uD83D\uDC7E Warden",   "WARDENEMERGE"},
                {"\uD83D\uDCA3 Creeper",  "CREEPERPRIMED"},
                {"\u2764 Totem Pop",       "TOTEMPOP"},
                {"\uD83D\uDC80 Fake Death",   "FAKEDEATH"},
        };
        for (int i = 0; i < scares.length; i++) {
            int col = i % 4, row = i / 4;
            int bx = px + PAD + col * (bw + 4);
            int by = cy + row * 26;
            boolean hov = mx >= bx && mx <= bx + bw && my >= by && my < by + 22;
            int bg = hov ? mixA(0xFFCC2222, 0x33FFFFFF) : 0x33441111;
            ctx.fill(bx, by, bx + bw, by + 22, bg);
            ctx.fill(bx, by, bx + 3, by + 22, hov ? 0xFFCC2222 : 0x33FF4444);
            ctx.fill(bx, by, bx + bw, by + 1, 0xFFCC2222);
            ctx.fill(bx, by + 21, bx + bw, by + 22, 0xFFCC2222);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a74" + scares[i][0]),
                    bx + bw / 2, by + 7, 0xFFFF4444);
        }
        cy += 26 * ((scares.length + 3) / 4) + 8;

        // ─ Quick actions row: Push + Reset ─
        int halfW = (contentW - 8) / 2;
        // Push button
        int pushX = px + PAD;
        boolean pushHov = mx >= pushX && mx <= pushX + halfW && my >= cy && my < cy + 22;
        ctx.fill(pushX, cy, pushX + halfW, cy + 22, pushHov ? mixA(t.accent, 0x33FFFFFF) : t.rowEven);
        ctx.fill(pushX, cy, pushX + 3, cy + 22, 0xFF44AADD);
        ctx.fill(pushX, cy, pushX + halfW, cy + 1, t.border);
        ctx.fill(pushX, cy + 21, pushX + halfW, cy + 22, t.border);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7b\u2191 Regeln senden"),
                pushX + halfW / 2, cy + 7, t.text);

        // Reset button
        int resetX = px + PAD + halfW + 8;
        boolean resetHov = mx >= resetX && mx <= resetX + halfW && my >= cy && my < cy + 22;
        ctx.fill(resetX, cy, resetX + halfW, cy + 22, resetHov ? 0x55FF3333 : 0x33FF2222);
        ctx.fill(resetX, cy, resetX + 3, cy + 22, 0xFFFF4444);
        ctx.fill(resetX, cy, resetX + halfW, cy + 1, 0xFFFF4444);
        ctx.fill(resetX, cy + 21, resetX + halfW, cy + 22, 0xFFFF4444);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7c\u2718 Reset"),
                resetX + halfW / 2, cy + 7, 0xFFFF5555);
        cy += 30;

        // ─ Remote Control button (full width) ─
        boolean rcHov = mx >= px + PAD && mx <= px + PAD + contentW && my >= cy && my < cy + 22;
        ctx.fill(px + PAD, cy, px + PAD + contentW, cy + 22,
                rcHov ? mixA(0xFF9966FF, 0x33FFFFFF) : 0x33663399);
        ctx.fill(px + PAD, cy, px + PAD + 3, cy + 22, 0xFF9966FF);
        ctx.fill(px + PAD, cy, px + PAD + contentW, cy + 1, 0xFF9966FF);
        ctx.fill(px + PAD, cy + 21, px + PAD + contentW, cy + 22, 0xFF9966FF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7d\uD83C\uDFAE Fernsteuerung"),
                px + panelW / 2, cy + 7, 0xFFDD99FF);
        cy += 30;

        // ─ Server Control section ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a7c\u2301 Server-Kontrolle", 0xFFFF5555, t);
        cy += 18;

        // Disconnect button (half width)
        int scHalfW = (contentW - 8) / 2;
        int dcX = px + PAD;
        boolean dcHov = mx >= dcX && mx <= dcX + scHalfW && my >= cy && my < cy + 22;
        ctx.fill(dcX, cy, dcX + scHalfW, cy + 22, dcHov ? 0x55FF2222 : 0x33AA1111);
        ctx.fill(dcX, cy, dcX + 3, cy + 22, 0xFFFF4444);
        ctx.fill(dcX, cy, dcX + scHalfW, cy + 1, 0xFFFF4444);
        ctx.fill(dcX, cy + 21, dcX + scHalfW, cy + 22, 0xFFFF4444);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7c\u2716 Disconnect"),
                dcX + scHalfW / 2, cy + 7, 0xFFFF5555);

        // Connect button (half width, right side)
        int cnX = px + PAD + scHalfW + 8;
        boolean cnHov = mx >= cnX && mx <= cnX + scHalfW && my >= cy && my < cy + 22;
        ctx.fill(cnX, cy, cnX + scHalfW, cy + 22, cnHov ? mixA(0xFF44CC44, 0x33FFFFFF) : 0x33116611);
        ctx.fill(cnX, cy, cnX + 3, cy + 22, 0xFF44CC44);
        ctx.fill(cnX, cy, cnX + scHalfW, cy + 1, 0xFF44CC44);
        ctx.fill(cnX, cy + 21, cnX + scHalfW, cy + 22, 0xFF44CC44);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7a\u2192 Connect"),
                cnX + scHalfW / 2, cy + 7, 0xFF55FF55);
        cy += 30;

        // ─ Screenshot section ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a7e\uD83D\uDCF7 Screenshot", 0xFFFFAA44, t);
        cy += 18;

        // Screenshot request button
        boolean scHov = mx >= px + PAD && mx <= px + PAD + contentW && my >= cy && my < cy + 22;
        ctx.fill(px + PAD, cy, px + PAD + contentW, cy + 22,
                scHov ? mixA(0xFFFFAA33, 0x33FFFFFF) : t.rowEven);
        ctx.fill(px + PAD, cy, px + PAD + 3, cy + 22, 0xFFFFAA33);
        ctx.fill(px + PAD, cy, px + PAD + contentW, cy + 1, t.border);
        ctx.fill(px + PAD, cy + 21, px + PAD + contentW, cy + 22, t.border);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7e\uD83D\uDCF7 Screenshot anfordern"),
                px + panelW / 2, cy + 7, t.text);
        cy += 28;

        // Screenshot preview
        int[] pixels = AdminConfig.getScreenshotPixels();
        int scW = AdminConfig.getScreenshotW();
        int scH = AdminConfig.getScreenshotH();
        if (pixels != null && scW > 0 && scH > 0) {
            int scale = 2;
            int dispW = scW * scale;
            int dispH = scH * scale;
            int imgX = px + PAD + (contentW - dispW) / 2;
            int imgY = cy;
            // Border
            ctx.fill(imgX - 1, imgY - 1, imgX + dispW + 1, imgY + dispH + 1, 0xFF444444);
            // Render pixels
            for (int y = 0; y < scH; y++) {
                for (int x = 0; x < scW; x++) {
                    int color = pixels[y * scW + x];
                    ctx.fill(imgX + x * scale, imgY + y * scale,
                            imgX + x * scale + scale, imgY + y * scale + scale, color);
                }
            }
            long age = (System.currentTimeMillis() - AdminConfig.getScreenshotTimestamp()) / 1000;
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a78\uD83D\uDCF7 " + age + "s alt"),
                    px + panelW / 2, imgY + dispH + 3, 0xFF555555);
            cy += dispH + 18;
        } else {
            ctx.fill(px + PAD, cy, px + PAD + contentW, cy + 30, 0x44111111);
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a78Kein Screenshot verf\u00fcgbar"),
                    px + panelW / 2, cy + 10, 0xFF555555);
            cy += 36;
        }

        // Active effects summary
        StringBuilder activeList = new StringBuilder();
        for (String[] tr : toggles) {
            if (AdminConfig.isTrollActive(target, tr[1]))
                activeList.append("\u00a7a").append(tr[1]).append(" ");
        }
        String activeTxt = activeList.length() > 0
                ? "\u00a77Aktiv: " + activeList.toString().trim()
                : "\u00a78Keine Effekte aktiv";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(activeTxt),
                px + panelW / 2, cy, t.subtext);

        ctx.disableScissor();

        // Clamp troll scroll
        int contentH = cy + trollScrollOffset - (listTop + 6) + 20;
        int visibleH = listBottom - listTop;
        int maxScroll = Math.max(0, contentH - visibleH);
        if (trollScrollOffset > maxScroll) trollScrollOffset = maxScroll;
    }

    // ── Tab 2: Relay ─────────────────────────────────────────────────────────

    // Stored position of "Alle anpingen" and "Alle entbannen" buttons for click handling
    private int pingBtnX, pingBtnY, pingBtnW;
    private int unbanBtnX, unbanBtnY, unbanBtnW;
    private int reloadBtnX, reloadBtnY, reloadBtnW;

    private void renderRelayTab(DrawContext ctx, int mx, int my, ThemeColors t) {
        int cy = listTop + 8;
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7b\u00a7l\u26a1 Sync Status"),
                px + panelW / 2, cy, t.text);
        cy += 20;

        // Connection status indicator
        RelaySync.ConnectionState state = RelaySync.getConnectionState();
        String stateIcon; String stateText; int stateColor;
        switch (state) {
            case CONNECTED -> { stateIcon = "\u00a7a\u25cf"; stateText = "Verbunden"; stateColor = 0xFF55FF55; }
            case CONNECTING -> { stateIcon = "\u00a7e\u25cb"; stateText = "Verbinde..."; stateColor = 0xFFFFFF55; }
            case RATE_LIMITED -> { stateIcon = "\u00a7c\u26a0"; stateText = "Rate-Limited"; stateColor = 0xFFFF5555; }
            case ERROR -> { stateIcon = "\u00a7c\u2716"; stateText = "Fehler: " + RelaySync.getConnectionError(); stateColor = 0xFFFF5555; }
            default -> { stateIcon = "\u00a78\u25cb"; stateText = "Getrennt"; stateColor = 0xFF888888; }
        }
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(stateIcon + " " + stateText),
                px + panelW / 2, cy, stateColor);
        cy += 16;

        String me = AdminConfig.getCurrentUsername();
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a77Kanal: \u00a7fcolumba-" + me.toLowerCase()),
                px + panelW / 2, cy, t.subtext);
        cy += 14;

        // Show relay mode + URL
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a78" + RelaySync.getMode().name() + ": " + RelaySync.getRelayBase()),
                px + panelW / 2, cy, t.subtext);
        cy += 14;

        // Embedded relay + ngrok status (admin only)
        if (AdminConfig.isAdmin()) {
            String serverStatus = EmbeddedRelay.isRunning()
                    ? "\u00a7a\u25cf Server l\u00e4uft (Port " + EmbeddedRelay.getPort() + ")"
                    : "\u00a7c\u25cb Server gestoppt";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(serverStatus),
                    px + panelW / 2, cy, t.subtext);
            cy += 12;

            String ngrokStatus;
            if (NgrokTunnel.isRunning()) {
                String url = NgrokTunnel.getPublicUrl();
                ngrokStatus = url.isEmpty()
                        ? "\u00a7e\u25cb ngrok startet..."
                        : "\u00a7a\u25cf ngrok: \u00a7f" + url;
            } else {
                ngrokStatus = "\u00a78\u25cb ngrok inaktiv";
            }
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(ngrokStatus),
                    px + panelW / 2, cy, t.subtext);
            cy += 14;
        }
        cy += 4;

        // Online counter
        int online = AdminConfig.countOnline(playerNames);
        int total = playerNames.size();
        String cTxt = "\u00a77Erreichbar: "
                + (online > 0 ? "\u00a7a" : "\u00a7c") + online
                + "\u00a77/" + total + " Spieler";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(cTxt),
                px + panelW / 2, cy, t.text);
        cy += 22;

        // Ping-all + Reload buttons (side by side)
        int btnW = 140;
        int gap = 8;
        int totalW = btnW * 2 + gap;
        int startX = px + (panelW - totalW) / 2;

        // Ping-all button
        pingBtnW = btnW;
        pingBtnX = startX;
        pingBtnY = cy;
        boolean hov = mx >= pingBtnX && mx <= pingBtnX + pingBtnW && my >= cy && my < cy + 22;
        ctx.fill(pingBtnX, cy, pingBtnX + pingBtnW, cy + 22, hov ? mixA(t.accent, 0x33FFFFFF) : t.rowEven);
        ctx.fill(pingBtnX, cy, pingBtnX + pingBtnW, cy + 1, t.border);
        ctx.fill(pingBtnX, cy + 21, pingBtnX + pingBtnW, cy + 22, t.border);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7e\u27f3 Alle anpingen"),
                pingBtnX + pingBtnW / 2, cy + 7, t.text);

        // Reload button
        reloadBtnW = btnW;
        reloadBtnX = startX + btnW + gap;
        reloadBtnY = cy;
        boolean hovR = mx >= reloadBtnX && mx <= reloadBtnX + reloadBtnW && my >= cy && my < cy + 22;
        ctx.fill(reloadBtnX, cy, reloadBtnX + reloadBtnW, cy + 22, hovR ? 0x5544AADD : t.rowEven);
        ctx.fill(reloadBtnX, cy, reloadBtnX + reloadBtnW, cy + 1, t.border);
        ctx.fill(reloadBtnX, cy + 21, reloadBtnX + reloadBtnW, cy + 22, t.border);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7b\u21bb Aktualisieren"),
                reloadBtnX + reloadBtnW / 2, cy + 7, t.text);
        cy += 30;

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a78Spieler werden automatisch verbunden"),
                px + panelW / 2, cy, t.subtext);
        cy += 18;

        // Active bans overview
        List<String> banned = new java.util.ArrayList<>();
        for (String n : playerNames) {
            if (AdminConfig.isPlayerBanned(n)) banned.add(n);
        }
        if (!banned.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a7c\u26d4 Gesperrt: \u00a7f" + String.join(", ", banned)),
                    px + panelW / 2, cy, t.text);
            cy += 14;

            // Unban all button
            unbanBtnW = 180;
            unbanBtnX = px + (panelW - unbanBtnW) / 2;
            unbanBtnY = cy;
            boolean hov2 = mx >= unbanBtnX && mx <= unbanBtnX + unbanBtnW && my >= cy && my < cy + 20;
            ctx.fill(unbanBtnX, cy, unbanBtnX + unbanBtnW, cy + 20, hov2 ? 0x5500CC44 : t.rowEven);
            ctx.fill(unbanBtnX, cy, unbanBtnX + unbanBtnW, cy + 1, t.border);
            ctx.fill(unbanBtnX, cy + 19, unbanBtnX + unbanBtnW, cy + 20, t.border);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7a\u2714 Alle entbannen"),
                    unbanBtnX + unbanBtnW / 2, cy + 6, t.text);
            cy += 24;
        } else {
            unbanBtnW = 0;
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a7a\u2714 Keine aktiven Sperren"),
                    px + panelW / 2, cy, t.subtext);
            cy += 16;
        }

        // ─ Verbundene Spieler (scrollable) ─
        cy += 6;
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a78\u2500 Spieler-Monitor \u2500"),
                px + PAD, cy, t.subtext);
        cy += 14;

        int monitorTop = cy;
        int monitorBottom = py + panelH - FOOTER_H - 4;
        // Enable scissor to clip overflow
        ctx.enableScissor(px + PAD, monitorTop, px + panelW - PAD, monitorBottom);
        cy -= syncScrollOffset;

        for (String name : playerNames) {
            boolean onl = AdminConfig.isRecentlyOnline(name);
            AdminConfig.PlayerStatus ps = AdminConfig.getPlayerStatus(name);
            String server = AdminConfig.getPlayerServer(name);
            long lastSeen = AdminConfig.getLastSeenTime(name);
            String timeStr = "";
            if (lastSeen > 0) {
                long secs = (System.currentTimeMillis() - lastSeen) / 1000;
                timeStr = secs < 60 ? "vor " + secs + "s" : "vor " + (secs / 60) + "min";
            }

            // Player card background
            int cardH = ps != null ? 48 : 14;
            int cardColor = onl ? 0x11228844 : 0x0A444444;
            ctx.fill(px + PAD, cy - 1, px + panelW - PAD, cy + cardH, cardColor);
            ctx.fill(px + PAD, cy - 1, px + PAD + 2, cy + cardH, onl ? 0xFF44CC66 : 0xFF555555);

            // Line 1: dot + name + server + time
            String dot = onl ? "\u00a7a\u25cf" : "\u00a78\u25cb";
            String line1 = dot + " \u00a7f\u00a7l" + name
                    + " \u00a77" + (server != null ? server : "")
                    + " \u00a78" + timeStr;
            ctx.drawTextWithShadow(textRenderer, Text.literal(line1),
                    px + PAD + 6, cy + 1, t.text);
            cy += 12;

            if (ps != null) {
                // Line 2: health + food + level + armor + game mode + fps
                int hearts = Math.round(ps.hp / 2);
                int maxHearts = Math.round(ps.maxHp / 2);
                String hpColor = hearts > 5 ? "\u00a7c" : (hearts > 2 ? "\u00a76" : "\u00a74");
                String foodColor = ps.food > 12 ? "\u00a76" : (ps.food > 6 ? "\u00a7e" : "\u00a7c");
                String line2 = hpColor + "\u2764" + hearts + "/" + maxHearts
                        + " " + foodColor + "\u2668" + ps.food
                        + " \u00a7a\u2605" + ps.level
                        + " \u00a7b\u2748" + ps.armor
                        + " \u00a7d" + ps.gameMode
                        + " \u00a7f" + ps.fps + "fps"
                        + " \u00a77Ping:" + ps.ping + "ms";
                ctx.drawTextWithShadow(textRenderer, Text.literal(line2),
                        px + PAD + 10, cy + 1, t.text);
                cy += 11;

                // Line 3: coordinates + dimension + held item
                String dimIcon = "overworld".equals(ps.dimension) ? "\u00a72\u2600"
                        : "the_nether".equals(ps.dimension) ? "\u00a7c\u2668"
                        : "the_end".equals(ps.dimension) ? "\u00a7d\u2734" : "\u00a77\u25c6";
                String line3 = "\u00a77XYZ: \u00a7f" + ps.x + " " + ps.y + " " + ps.z
                        + " " + dimIcon
                        + " \u00a77\u2694\u00a7f " + (ps.heldItem.isEmpty() ? "-" : ps.heldItem);
                ctx.drawTextWithShadow(textRenderer, Text.literal(line3),
                        px + PAD + 10, cy + 1, t.text);
                cy += 11;

                // Line 4: movement state + environment
                StringBuilder states = new StringBuilder("\u00a78");
                if (ps.sprinting) states.append("\u00a7e\u2933Sprint ");
                if (ps.sneaking) states.append("\u00a76\u2935Sneak ");
                if (ps.swimming) states.append("\u00a7b\u2248Swim ");
                if (ps.flying) states.append("\u00a7d\u2708Fly ");
                if (ps.inWater) states.append("\u00a79\u2248Wasser ");
                if (ps.inLava) states.append("\u00a7c\u2668Lava ");
                if (ps.raining) states.append("\u00a77\u2602Regen ");
                if (ps.thundering) states.append("\u00a7e\u26a1Gewitter ");
                states.append("\u00a78Licht:").append(ps.light);
                if (ps.air < 300) states.append(" \u00a7bLuft:").append(ps.air);
                long tod = ps.timeOfDay;
                String timeOfDay = tod < 6000 ? "Morgen" : tod < 12000 ? "Tag" : tod < 18000 ? "Abend" : "Nacht";
                states.append(" \u00a78").append(timeOfDay);
                ctx.drawTextWithShadow(textRenderer, Text.literal(states.toString()),
                        px + PAD + 10, cy + 1, t.text);
                cy += 14;
            } else {
                cy += 2;
            }
        }
        syncContentHeight = cy + syncScrollOffset - monitorTop;
        ctx.disableScissor();
    }

    // ── Tab 3: Debug Log ──────────────────────────────────────────────────────

    private int clearBtnX, clearBtnY, clearBtnW;

    private void renderDebugTab(DrawContext ctx, int mx, int my, ThemeColors t) {
        int cy = listTop + 8;
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a78\u00a7l\u2261 Relay Debug Log"),
                px + panelW / 2, cy, t.text);
        cy += 18;

        // Clear button
        clearBtnW = 100;
        clearBtnX = px + panelW - PAD - clearBtnW;
        clearBtnY = cy;
        boolean hovC = mx >= clearBtnX && mx <= clearBtnX + clearBtnW && my >= cy && my < cy + 16;
        ctx.fill(clearBtnX, cy, clearBtnX + clearBtnW, cy + 16, hovC ? 0x55FF4444 : t.rowEven);
        ctx.fill(clearBtnX, cy, clearBtnX + clearBtnW, cy + 1, t.border);
        ctx.fill(clearBtnX, cy + 15, clearBtnX + clearBtnW, cy + 16, t.border);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7c\u2716 Leeren"),
                clearBtnX + clearBtnW / 2, cy + 4, t.text);

        // Log entry count
        java.util.List<String> log = RelaySync.getDebugLog();
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78" + log.size() + " Eintr\u00e4ge"),
                px + PAD, cy + 4, t.subtext);
        cy += 22;

        // Scrollable debug log
        int logTop = cy;
        int logBottom = py + panelH - FOOTER_H - 4;
        ctx.enableScissor(px + PAD, logTop, px + panelW - PAD, logBottom);

        int logY = logTop - debugScrollOffset;
        for (int li = 0; li < log.size(); li++) {
            if (logY + 10 > logTop - 200 && logY < logBottom + 200) { // only render visible
                ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a78" + log.get(li)),
                        px + PAD + 4, logY, t.subtext);
            }
            logY += 10;
        }
        debugContentHeight = log.size() * 10;
        ctx.disableScissor();

        // Scrollbar indicator
        int viewH = logBottom - logTop;
        if (debugContentHeight > viewH) {
            int barH = Math.max(10, viewH * viewH / debugContentHeight);
            int barY = logTop + (int)((float)debugScrollOffset / debugContentHeight * viewH);
            ctx.fill(px + panelW - PAD - 2, barY, px + panelW - PAD, barY + barH, 0x44FFFFFF);
        }
    }

    // ── Tab 4: Settings ──────────────────────────────────────────────────────

    private static final int[][] VIDEO_PRESETS = {{80, 45}, {120, 67}, {160, 90}, {200, 112}, {240, 135}};
    private static final float[] SPF_PRESETS = {0.1f, 0.25f, 0.5f, 1f, 2f, 3f, 5f};
    private static final int[][] SS_PRESETS = {{80, 45}, {120, 67}, {160, 90}, {200, 112}, {240, 135}};

    private void renderSettingsTab(DrawContext ctx, int mx, int my, ThemeColors t) {
        int cy = listTop + 8 - settingsScrollOffset;
        int contentW = panelW - PAD * 2;

        // ─ Video (Fernsteuerung) ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a7e\uD83D\uDCF9 Video (Fernsteuerung)", 0xFFFFAA33, t);
        cy += 20;

        // Video resolution
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Aufl\u00f6sung:"), px + PAD, cy + 6, t.text);
        int bx = px + PAD + 80;
        int bw = (contentW - 84) / VIDEO_PRESETS.length - 4;
        for (int i = 0; i < VIDEO_PRESETS.length; i++) {
            int[] p = VIDEO_PRESETS[i];
            int x = bx + i * (bw + 4);
            boolean sel = AdminConfig.videoW == p[0] && AdminConfig.videoH == p[1];
            boolean hov = mx >= x && mx <= x + bw && my >= cy && my < cy + 20;
            ctx.fill(x, cy, x + bw, cy + 20, sel ? 0x44FFAA33 : (hov ? 0x33FFFFFF : t.rowEven));
            ctx.fill(x, cy, x + 3, cy + 20, sel ? 0xFFFFAA33 : 0x33FFFFFF);
            ctx.fill(x, cy, x + bw, cy + 1, t.border);
            ctx.fill(x, cy + 19, x + bw, cy + 20, t.border);
            String label = (sel ? "\u00a7e" : "\u00a7f") + p[0] + "\u00d7" + p[1];
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + bw / 2, cy + 6, t.text);
        }
        cy += 26;

        // Video SPF
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Bildrate:"), px + PAD, cy + 6, t.text);
        bw = (contentW - 84) / SPF_PRESETS.length - 4;
        for (int i = 0; i < SPF_PRESETS.length; i++) {
            float spf = SPF_PRESETS[i];
            int x = bx + i * (bw + 4);
            boolean sel = AdminConfig.videoSpf == spf;
            boolean hov = mx >= x && mx <= x + bw && my >= cy && my < cy + 20;
            ctx.fill(x, cy, x + bw, cy + 20, sel ? 0x44FFAA33 : (hov ? 0x33FFFFFF : t.rowEven));
            ctx.fill(x, cy, x + 3, cy + 20, sel ? 0xFFFFAA33 : 0x33FFFFFF);
            ctx.fill(x, cy, x + bw, cy + 1, t.border);
            ctx.fill(x, cy + 19, x + bw, cy + 20, t.border);
            String spfLabel = spf == (int) spf ? String.valueOf((int) spf) : String.valueOf(spf);
            String label = (sel ? "\u00a7e" : "\u00a7f") + spfLabel + " SPF";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + bw / 2, cy + 6, t.text);
        }
        cy += 26;

        // Video info
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78Aktuell: " + AdminConfig.videoW + "\u00d7" + AdminConfig.videoH
                        + " @ " + (AdminConfig.videoSpf == (int) AdminConfig.videoSpf
                            ? String.valueOf((int) AdminConfig.videoSpf)
                            : String.valueOf(AdminConfig.videoSpf)) + "s/Frame"),
                px + PAD, cy, 0xFF666666);
        cy += 18;

        // ─ Screenshot ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a7b\uD83D\uDCF7 Screenshot", 0xFF44AADD, t);
        cy += 20;

        // Screenshot resolution
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Aufl\u00f6sung:"), px + PAD, cy + 6, t.text);
        bw = (contentW - 84) / SS_PRESETS.length - 4;
        for (int i = 0; i < SS_PRESETS.length; i++) {
            int[] p = SS_PRESETS[i];
            int x = bx + i * (bw + 4);
            boolean sel = AdminConfig.ssW == p[0] && AdminConfig.ssH == p[1];
            boolean hov = mx >= x && mx <= x + bw && my >= cy && my < cy + 20;
            ctx.fill(x, cy, x + bw, cy + 20, sel ? 0x4444AADD : (hov ? 0x33FFFFFF : t.rowEven));
            ctx.fill(x, cy, x + 3, cy + 20, sel ? 0xFF44AADD : 0x33FFFFFF);
            ctx.fill(x, cy, x + bw, cy + 1, t.border);
            ctx.fill(x, cy + 19, x + bw, cy + 20, t.border);
            String label = (sel ? "\u00a7b" : "\u00a7f") + p[0] + "\u00d7" + p[1];
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + bw / 2, cy + 6, t.text);
        }
        cy += 26;

        // Screenshot info
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78Aktuell: " + AdminConfig.ssW + "\u00d7" + AdminConfig.ssH),
                px + PAD, cy, 0xFF666666);
        cy += 22;

        // ─ Hinweis ─
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78\u26a0 H\u00f6here Aufl\u00f6sung = mehr Daten = langsamer"),
                px + PAD, cy, 0xFF555555);
        cy += 18;

        // ── Troll Settings ──
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a76\uD83C\uDFAE Troll-Einstellungen", 0xFFFF8844, t);
        cy += 20;

        // Spin Speed
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Spin Speed:"), px + PAD, cy + 6, t.text);
        float[] spinPresets = {5f, 10f, 15f, 25f, 40f};
        bw = (contentW - 84) / spinPresets.length - 4;
        for (int i = 0; i < spinPresets.length; i++) {
            int x = bx + i * (bw + 4);
            boolean sel = AdminConfig.spinSpeed == spinPresets[i];
            boolean hov = mx >= x && mx <= x + bw && my >= cy && my < cy + 20;
            ctx.fill(x, cy, x + bw, cy + 20, sel ? 0x44FF8844 : (hov ? 0x33FFFFFF : t.rowEven));
            ctx.fill(x, cy, x + 3, cy + 20, sel ? 0xFFFF8844 : 0x33FFFFFF);
            ctx.fill(x, cy, x + bw, cy + 1, t.border);
            ctx.fill(x, cy + 19, x + bw, cy + 20, t.border);
            String label = (sel ? "\u00a76" : "\u00a7f") + (int) spinPresets[i] + "\u00b0/t";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + bw / 2, cy + 6, t.text);
        }
        cy += 26;

        // DVD Speed
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77DVD Speed:"), px + PAD, cy + 6, t.text);
        float[] dvdSpeedPresets = {1.0f, 1.5f, 2.3f, 3.5f, 5.0f};
        bw = (contentW - 84) / dvdSpeedPresets.length - 4;
        for (int i = 0; i < dvdSpeedPresets.length; i++) {
            int x = bx + i * (bw + 4);
            boolean sel = Math.abs(AdminConfig.dvdSpeed - dvdSpeedPresets[i]) < 0.1f;
            boolean hov = mx >= x && mx <= x + bw && my >= cy && my < cy + 20;
            ctx.fill(x, cy, x + bw, cy + 20, sel ? 0x44FF8844 : (hov ? 0x33FFFFFF : t.rowEven));
            ctx.fill(x, cy, x + 3, cy + 20, sel ? 0xFFFF8844 : 0x33FFFFFF);
            ctx.fill(x, cy, x + bw, cy + 1, t.border);
            ctx.fill(x, cy + 19, x + bw, cy + 20, t.border);
            String label = (sel ? "\u00a76" : "\u00a7f") + dvdSpeedPresets[i] + "px";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + bw / 2, cy + 6, t.text);
        }
        cy += 26;

        // DVD Size
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77DVD Gr\u00f6\u00dfe:"), px + PAD, cy + 6, t.text);
        int[] dvdSizePresets = {60, 90, 120, 160, 200};
        bw = (contentW - 84) / dvdSizePresets.length - 4;
        for (int i = 0; i < dvdSizePresets.length; i++) {
            int x = bx + i * (bw + 4);
            boolean sel = AdminConfig.dvdSize == dvdSizePresets[i];
            boolean hov = mx >= x && mx <= x + bw && my >= cy && my < cy + 20;
            ctx.fill(x, cy, x + bw, cy + 20, sel ? 0x44FF8844 : (hov ? 0x33FFFFFF : t.rowEven));
            ctx.fill(x, cy, x + 3, cy + 20, sel ? 0xFFFF8844 : 0x33FFFFFF);
            ctx.fill(x, cy, x + bw, cy + 1, t.border);
            ctx.fill(x, cy + 19, x + bw, cy + 20, t.border);
            String label = (sel ? "\u00a76" : "\u00a7f") + dvdSizePresets[i] + "px";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + bw / 2, cy + 6, t.text);
        }
        cy += 26;

        // Death Message
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Death-Text:"), px + PAD, cy + 6, t.text);
        String[] deathMsgPresets = {
            "%name% was killed by the System",
            "%name% died mysteriously",
            "%name% was slain by Herobrine",
            "%name% fell out of the world"
        };
        bw = (contentW - 84) / 2 - 4;
        for (int i = 0; i < deathMsgPresets.length; i++) {
            int col = i % 2, row = i / 2;
            int x = bx + col * (bw + 4);
            int y2 = cy + row * 22;
            boolean sel = AdminConfig.deathMessage.equals(deathMsgPresets[i]);
            boolean hov = mx >= x && mx <= x + bw && my >= y2 && my < y2 + 20;
            ctx.fill(x, y2, x + bw, y2 + 20, sel ? 0x44FF4444 : (hov ? 0x33FFFFFF : t.rowEven));
            ctx.fill(x, y2, x + 3, y2 + 20, sel ? 0xFFFF4444 : 0x33FFFFFF);
            ctx.fill(x, y2, x + bw, y2 + 1, t.border);
            ctx.fill(x, y2 + 19, x + bw, y2 + 20, t.border);
            // Truncate label to fit
            String raw = deathMsgPresets[i].replace("%name%", "?");
            String label = (sel ? "\u00a7c" : "\u00a7f") + (raw.length() > 20 ? raw.substring(0, 18) + ".." : raw);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + bw / 2, y2 + 6, t.text);
        }
        cy += 22 * ((deathMsgPresets.length + 1) / 2) + 8;

        // ─ Update section ─
        drawSectionHeader(ctx, textRenderer, px + PAD, cy, contentW,
                "\u00a7a\u2B07 Update", 0xFF55FF55, t);
        cy += 20;

        // Update check button
        boolean updChecking = AutoUpdater.isChecking();
        String updBtnLabel = updChecking ? "\u00a7e\u27f3 Prüfe..." : "\u00a7a\u2B07 Nach Updates suchen";
        int updBtnW = contentW;
        boolean updHov = !updChecking && mx >= px + PAD && mx <= px + PAD + updBtnW && my >= cy && my < cy + 22;
        int updBg = updChecking ? 0x33AAAA33 : (updHov ? 0x4455FF55 : 0x33225522);
        ctx.fill(px + PAD, cy, px + PAD + updBtnW, cy + 22, updBg);
        ctx.fill(px + PAD, cy, px + PAD + 3, cy + 22, updChecking ? 0xFFAAAA33 : 0xFF55FF55);
        ctx.fill(px + PAD, cy, px + PAD + updBtnW, cy + 1, 0xFF55FF55);
        ctx.fill(px + PAD, cy + 21, px + PAD + updBtnW, cy + 22, 0xFF55FF55);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(updBtnLabel),
                px + panelW / 2, cy + 7, updChecking ? 0xFFAAAA33 : 0xFF55FF55);
        cy += 26;

        // Status text
        String status = AutoUpdater.getCheckStatus();
        if (status != null) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(status), px + PAD, cy, 0xFFAAAAAA);
            cy += 14;
        }

        // Version info
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78Installiert: v" + ChatFilterMod.VERSION),
                px + PAD, cy, 0xFF666666);
        cy += 14;

        // Current troll settings summary
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78Spin: " + (int)AdminConfig.spinSpeed + "\u00b0/t | DVD: "
                        + AdminConfig.dvdSpeed + "px, " + AdminConfig.dvdSize + "px"),
                px + PAD, cy, 0xFF666666);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mx = click.x(), my = click.y();

        // Update button click (top-right of header)
        if (AutoUpdater.isUpdateReady()) {
            String updLabel = "\u00a7a\u2B07 Update v" + AutoUpdater.getLatestVersion();
            int updW = textRenderer.getWidth(Text.literal(updLabel)) + 12;
            int updH = 14;
            int updX = px + panelW - updW - 8;
            int updY = py + 8;
            if (mx >= updX && mx <= updX + updW && my >= updY && my <= updY + updH) {
                setFeedback("\u00a7aUpdate v" + AutoUpdater.getLatestVersion() + " bereit! Starte Minecraft neu.", true);
                return true;
            }
        }

        // Tab 0 – player list clicks
        if (activeTab == 0) {
            int visible = visibleRows();
            for (int vi = scrollOffset; vi < Math.min(playerNames.size(), scrollOffset + visible); vi++) {
                int ry = listTop + (vi - scrollOffset) * ITEM_H;
                if (my < ry || my >= ry + ITEM_H || mx < px + PAD || mx > px + panelW - PAD) continue;
                int rx = px + panelW - PAD - 2;
                if (mx >= rx - 22)  { deletePlayer(vi); return true; }
                if (mx >= rx - 50)  { client.setScreen(new AdminPlayerScreen(this, playerNames.get(vi))); return true; }
                if (mx >= rx - 78)  { toggleBan(vi); return true; }
                if (mx >= rx - 108) { pushPlayer(vi); return true; }
                if (mx >= rx - 136) { client.setScreen(new PlayerInfoScreen(this, playerNames.get(vi))); return true; }
                // Select
                selectedIdx = vi;
                return true;
            }
        }

        // Tab 1 – troll buttons (layout must match renderTrollTab exactly)
        if (activeTab == 1 && selectedIdx >= 0 && selectedIdx < playerNames.size()) {
            String target = playerNames.get(selectedIdx);

            if (my < listTop || my >= listBottom) {
                return super.mouseClicked(click, bl);
            }

            int contentW = panelW - PAD * 2;
            int cy = listTop + 6 - trollScrollOffset;

            // Section header: Dauer-Effekte
            cy += 18;

            // Toggle buttons (16 items, 4-col grid, row height 26)
            String[] toggleCmds = {"SNEAK","BHOP","SPIN","FREEZE","SLOTCYCLE","NOPICK","DVD","ZOOM","LOOKUP","LOOKDOWN","AUTOATTACK","SWAPWS"};
            int bw = (contentW - 12) / 4;
            for (int i = 0; i < toggleCmds.length; i++) {
                int col = i % 4, row = i / 4;
                int bx = px + PAD + col * (bw + 4);
                int by = cy + row * 26;
                if (mx >= bx && mx <= bx + bw && my >= by && my < by + 22) {
                    String cmd = toggleCmds[i];
                    // Send config before toggling on
                    if (cmd.equals("SPIN")) {
                        AdminConfig.sendTrollCommand(target, "SPINCFG:" + AdminConfig.spinSpeed);
                    } else if (cmd.equals("DVD")) {
                        AdminConfig.sendTrollCommand(target, "DVDCFG:" + AdminConfig.dvdSpeed + ":" + AdminConfig.dvdSize);
                    }
                    boolean nowActive = AdminConfig.toggleTroll(target, cmd);
                    AdminConfig.sendTrollCommand(target, cmd);
                    String state = AdminConfig.TOGGLE_TROLLS_SET.contains(cmd)
                            ? (nowActive ? " \u00a7aAN" : " \u00a7cAUS") : "";
                    setFeedback("\u00a76" + cmd + state + " \u00a77\u2192 \u00a7f" + target, true);
                    return true;
                }
            }
            cy += 26 * ((toggleCmds.length + 3) / 4) + 8;

            // Section header: Sofort-Aktionen
            cy += 18;

            // Instant buttons (4 items, 4-col grid)
            String[] instantCmds = {"JUMP","DROP","DROPALL","INVSHUFFLE"};
            for (int i = 0; i < instantCmds.length; i++) {
                int col = i % 4;
                int bx = px + PAD + col * (bw + 4);
                int by = cy;
                if (mx >= bx && mx <= bx + bw && my >= by && my < by + 22) {
                    String cmd = instantCmds[i];
                    AdminConfig.sendTrollCommand(target, cmd);
                    setFeedback("\u00a76" + cmd + " \u00a77\u2192 \u00a7f" + target, true);
                    return true;
                }
            }
            cy += 30;

            // Section header: Jumpscare
            cy += 18;

            // Jumpscare buttons (5 items, multi-row)
            String[] scareCmds = {"ELDERGUARDIAN","WARDENEMERGE","CREEPERPRIMED","TOTEMPOP","FAKEDEATH"};
            for (int i = 0; i < scareCmds.length; i++) {
                int col = i % 4, row = i / 4;
                int bx = px + PAD + col * (bw + 4);
                int by = cy + row * 26;
                if (mx >= bx && mx <= bx + bw && my >= by && my < by + 22) {
                    String cmd = scareCmds[i];
                    // FAKEDEATH includes custom death message
                    if (cmd.equals("FAKEDEATH")) {
                        cmd = "FAKEDEATH:" + AdminConfig.deathMessage;
                    }
                    AdminConfig.sendTrollCommand(target, cmd);
                    setFeedback("\u00a74\u2620 " + scareCmds[i] + " \u00a77\u2192 \u00a7f" + target, true);
                    return true;
                }
            }
            cy += 26 * ((scareCmds.length + 3) / 4) + 8;

            // Push + Reset side-by-side
            int halfW = (contentW - 8) / 2;
            int pushX2 = px + PAD;
            if (mx >= pushX2 && mx <= pushX2 + halfW && my >= cy && my < cy + 22) {
                AdminConfig.save();
                AdminConfig.pushToPlayer(target);
                setFeedback("\u00a7b\u2191 Regeln \u2192 " + target, true);
                return true;
            }
            int resetX = px + PAD + halfW + 8;
            if (mx >= resetX && mx <= resetX + halfW && my >= cy && my < cy + 22) {
                AdminConfig.toggleTroll(target, "RESET");
                AdminConfig.sendTrollCommand(target, "RESET");
                setFeedback("\u00a76RESET \u00a77\u2192 \u00a7f" + target, true);
                return true;
            }
            cy += 30;

            // Remote Control button (full width)
            if (mx >= px + PAD && mx <= px + PAD + contentW && my >= cy && my < cy + 22) {
                client.setScreen(new RemoteControlScreen(this, target));
                return true;
            }
            cy += 30;

            // Server Control section header
            cy += 18;

            // Disconnect + Connect buttons (side by side)
            int scHalfW = (contentW - 8) / 2;
            int dcX = px + PAD;
            if (mx >= dcX && mx <= dcX + scHalfW && my >= cy && my < cy + 22) {
                AdminConfig.sendTrollCommand(target, "DISCONNECT");
                setFeedback("\u00a7c\u2716 Disconnect \u00a77\u2192 \u00a7f" + target, true);
                return true;
            }
            int cnX = px + PAD + scHalfW + 8;
            if (mx >= cnX && mx <= cnX + scHalfW && my >= cy && my < cy + 22) {
                String ip = connectField != null ? connectField.getText().trim() : "";
                if (ip.isEmpty()) {
                    setFeedback("\u00a7cBitte Server-IP eingeben!", false);
                } else {
                    AdminConfig.sendTrollCommand(target, "CONNECT:" + ip);
                    setFeedback("\u00a7a\u2192 Connect zu " + ip + " \u00a77\u2192 \u00a7f" + target, true);
                }
                return true;
            }
            cy += 30;

            // Screenshot section header
            cy += 18;

            // Screenshot request button
            if (mx >= px + PAD && mx <= px + PAD + contentW && my >= cy && my < cy + 22) {
                AdminConfig.sendTrollCommand(target,
                        "SCREENSHOT_REQ:" + AdminConfig.ssW + "x" + AdminConfig.ssH);
                setFeedback("\u00a7e\uD83D\uDCF7 Screenshot angefordert \u2192 " + target, true);
                return true;
            }
        }

        // Tab 2 – Sync: Ping all + Reload + Unban all buttons
        if (activeTab == 2) {
            if (pingBtnW > 0 && mx >= pingBtnX && mx <= pingBtnX + pingBtnW
                    && my >= pingBtnY && my < pingBtnY + 22) {
                AdminConfig.pingAllPlayers();
                setFeedback("\u00a7e\u27f3 Ping an alle Spieler gesendet!", true);
                return true;
            }
            if (reloadBtnW > 0 && mx >= reloadBtnX && mx <= reloadBtnX + reloadBtnW
                    && my >= reloadBtnY && my < reloadBtnY + 22) {
                // Force re-ping + broadcast relay URL to all players
                AdminConfig.pingAllPlayers();
                if (RelaySync.getMode() == RelaySync.RelayMode.CUSTOM) {
                    for (String name : playerNames) {
                        RelaySync.pushRelayUrlViaNtfy(name);
                    }
                }
                setFeedback("\u00a7b\u21bb Aktualisierung gesendet!", true);
                return true;
            }
            if (unbanBtnW > 0 && mx >= unbanBtnX && mx <= unbanBtnX + unbanBtnW
                    && my >= unbanBtnY && my < unbanBtnY + 20) {
                for (String n : playerNames) {
                    if (AdminConfig.isPlayerBanned(n)) {
                        AdminConfig.setPlayerBanned(n, false);
                        AdminConfig.pushToPlayer(n);
                    }
                }
                setFeedback("\u00a7a\u2714 Alle Spieler entbannt!", true);
                return true;
            }
        }

        // Tab 3 – Debug: Clear button
        if (activeTab == 3) {
            if (clearBtnW > 0 && mx >= clearBtnX && mx <= clearBtnX + clearBtnW
                    && my >= clearBtnY && my < clearBtnY + 16) {
                RelaySync.clearDebugLog();
                debugScrollOffset = 0;
                setFeedback("\u00a78Log geleert.", true);
                return true;
            }
        }

        // Tab 4 – Settings: resolution + SPF presets + troll settings
        if (activeTab == 4) {
            int cy = listTop + 8 - settingsScrollOffset;
            int contentW = panelW - PAD * 2;
            cy += 20; // section header

            // Video resolution presets
            int bx = px + PAD + 80;
            int bw = (contentW - 84) / VIDEO_PRESETS.length - 4;
            for (int i = 0; i < VIDEO_PRESETS.length; i++) {
                int x = bx + i * (bw + 4);
                if (mx >= x && mx <= x + bw && my >= cy && my < cy + 20) {
                    AdminConfig.videoW = VIDEO_PRESETS[i][0];
                    AdminConfig.videoH = VIDEO_PRESETS[i][1];
                    setFeedback("\u00a7eVideo: " + AdminConfig.videoW + "\u00d7" + AdminConfig.videoH, true);
                    return true;
                }
            }
            cy += 26;

            // Video SPF presets
            bw = (contentW - 84) / SPF_PRESETS.length - 4;
            for (int i = 0; i < SPF_PRESETS.length; i++) {
                int x = bx + i * (bw + 4);
                if (mx >= x && mx <= x + bw && my >= cy && my < cy + 20) {
                    AdminConfig.videoSpf = SPF_PRESETS[i];
                    String spfLabel = SPF_PRESETS[i] == (int) SPF_PRESETS[i]
                        ? String.valueOf((int) SPF_PRESETS[i]) : String.valueOf(SPF_PRESETS[i]);
                    setFeedback("\u00a7eVideo: " + spfLabel + " SPF", true);
                    return true;
                }
            }
            cy += 26 + 18 + 20; // info + section header

            // Screenshot resolution presets
            bw = (contentW - 84) / SS_PRESETS.length - 4;
            for (int i = 0; i < SS_PRESETS.length; i++) {
                int x = bx + i * (bw + 4);
                if (mx >= x && mx <= x + bw && my >= cy && my < cy + 20) {
                    AdminConfig.ssW = SS_PRESETS[i][0];
                    AdminConfig.ssH = SS_PRESETS[i][1];
                    setFeedback("\u00a7bScreenshot: " + AdminConfig.ssW + "\u00d7" + AdminConfig.ssH, true);
                    return true;
                }
            }
            cy += 26 + 22 + 18 + 20; // SS info (+26) + SS info text (+22) + hint (+18) + troll section header (+20)

            // ── Troll settings clicks ──
            // Spin speed
            float[] spinPresets = {5f, 10f, 15f, 25f, 40f};
            bw = (contentW - 84) / spinPresets.length - 4;
            for (int i = 0; i < spinPresets.length; i++) {
                int x = bx + i * (bw + 4);
                if (mx >= x && mx <= x + bw && my >= cy && my < cy + 20) {
                    AdminConfig.spinSpeed = spinPresets[i];
                    setFeedback("\u00a76Spin: " + (int) spinPresets[i] + "\u00b0/tick", true);
                    return true;
                }
            }
            cy += 26;

            // DVD speed
            float[] dvdSpeedPresets = {1.0f, 1.5f, 2.3f, 3.5f, 5.0f};
            bw = (contentW - 84) / dvdSpeedPresets.length - 4;
            for (int i = 0; i < dvdSpeedPresets.length; i++) {
                int x = bx + i * (bw + 4);
                if (mx >= x && mx <= x + bw && my >= cy && my < cy + 20) {
                    AdminConfig.dvdSpeed = dvdSpeedPresets[i];
                    setFeedback("\u00a76DVD Speed: " + dvdSpeedPresets[i] + "px/t", true);
                    return true;
                }
            }
            cy += 26;

            // DVD size
            int[] dvdSizePresets = {60, 90, 120, 160, 200};
            bw = (contentW - 84) / dvdSizePresets.length - 4;
            for (int i = 0; i < dvdSizePresets.length; i++) {
                int x = bx + i * (bw + 4);
                if (mx >= x && mx <= x + bw && my >= cy && my < cy + 20) {
                    AdminConfig.dvdSize = dvdSizePresets[i];
                    setFeedback("\u00a76DVD Gr\u00f6\u00dfe: " + dvdSizePresets[i] + "px", true);
                    return true;
                }
            }
            cy += 26;

            // Death message presets (2 columns)
            String[] deathMsgPresets = {
                "%name% was killed by the System",
                "%name% died mysteriously",
                "%name% was slain by Herobrine",
                "%name% fell out of the world"
            };
            bw = (contentW - 84) / 2 - 4;
            for (int i = 0; i < deathMsgPresets.length; i++) {
                int col = i % 2, row = i / 2;
                int x = bx + col * (bw + 4);
                int y2 = cy + row * 22;
                if (mx >= x && mx <= x + bw && my >= y2 && my < y2 + 20) {
                    AdminConfig.deathMessage = deathMsgPresets[i];
                    setFeedback("\u00a7cDeath: " + deathMsgPresets[i].replace("%name%", "?"), true);
                    return true;
                }
            }
            cy += 22 * ((deathMsgPresets.length + 1) / 2) + 8;

            // Update check button
            cy += 20; // section header
            if (!AutoUpdater.isChecking() && mx >= px + PAD && mx <= px + PAD + contentW && my >= cy && my < cy + 22) {
                AutoUpdater.checkManual(ChatFilterMod.VERSION);
                setFeedback("\u00a7e\u27f3 Suche nach Updates...", true);
                return true;
            }
        }

        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horiz, double vert) {
        if (vert != 0 && activeTab == 0) {
            scrollOffset -= (int) Math.signum(vert);
            clampScroll();
            return true;
        }
        if (vert != 0 && activeTab == 1) {
            trollScrollOffset -= (int) (Math.signum(vert) * 20);
            if (trollScrollOffset < 0) trollScrollOffset = 0;
            return true;
        }
        if (vert != 0 && activeTab == 2) {
            syncScrollOffset -= (int) (Math.signum(vert) * 20);
            int maxScroll = Math.max(0, syncContentHeight - (listBottom - listTop - 180));
            syncScrollOffset = Math.max(0, Math.min(syncScrollOffset, maxScroll));
            return true;
        }
        if (vert != 0 && activeTab == 3) {
            debugScrollOffset -= (int) (Math.signum(vert) * 20);
            int viewH = listBottom - listTop - 40;
            int maxScroll = Math.max(0, debugContentHeight - viewH);
            debugScrollOffset = Math.max(0, Math.min(debugScrollOffset, maxScroll));
            return true;
        }
        if (vert != 0 && activeTab == 4) {
            settingsScrollOffset -= (int) (Math.signum(vert) * 20);
            settingsScrollOffset = Math.max(0, Math.min(settingsScrollOffset, 300));
            return true;
        }
        return super.mouseScrolled(mx, my, horiz, vert);
    }

    @Override
    public boolean keyPressed(KeyInput ki) {
        int k = ki.key();
        if (k == GLFW.GLFW_KEY_ESCAPE)  { close(); return true; }
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
            if (addField != null && addField.isFocused()) { addPlayer(); return true; }
            if (chatField != null && chatField.isFocused()) { sendFakeChat(); return true; }
        }
        if (k == GLFW.GLFW_KEY_UP   && activeTab == 0) { scrollOffset--; clampScroll(); return true; }
        if (k == GLFW.GLFW_KEY_DOWN && activeTab == 0) { scrollOffset++; clampScroll(); return true; }
        return super.keyPressed(ki);
    }

    @Override public boolean shouldPause() { return false; }

    // ── Player actions ────────────────────────────────────────────────────────

    private void deletePlayer(int idx) {
        if (idx < 0 || idx >= playerNames.size()) return;
        String name = playerNames.remove(idx);
        AdminConfig.removePlayer(name);
        if (selectedIdx >= playerNames.size()) selectedIdx = playerNames.size() - 1;
        clampScroll();
        setFeedback("\u00a7c\u2716 \"" + name + "\" entfernt.", false);
    }

    private void toggleBan(int idx) {
        String name = playerNames.get(idx);
        boolean b = AdminConfig.isPlayerBanned(name);
        AdminConfig.setPlayerBanned(name, !b);
        // Push ban status to player via relay
        AdminConfig.pushToPlayer(name);
        setFeedback((!b ? "\u00a7c\u26d4 " : "\u00a7a\u2714 ") + name + (!b ? " gebannt." : " entbannt."), !b);
    }

    private void pushPlayer(int idx) {
        String name = playerNames.get(idx);
        AdminConfig.save();
        AdminConfig.pushToPlayer(name);
        setFeedback("\u00a7b\u2191 Regeln gepusht \u2192 \u00a7f" + name, true);
    }

    private void sendFakeChat() {
        if (selectedIdx < 0 || selectedIdx >= playerNames.size()) {
            setFeedback("\u00a7cKein Spieler ausgew\u00e4hlt.", false);
            return;
        }
        String msg = chatField.getText().strip();
        if (msg.isEmpty()) return;
        String target = playerNames.get(selectedIdx);
        AdminConfig.sendTrollCommand(target, "FAKECHAT:" + msg);
        chatField.setText("");
        setFeedback("\u00a7e\u2709 \"" + msg + "\" \u2192 " + target, true);
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private void renderScrollbar(DrawContext ctx, ThemeColors t, int total) {
        int visible = visibleRows();
        if (total <= visible) return;
        int barH = listBottom - listTop;
        int thumbH = Math.max(16, barH * visible / total);
        int thumbY = listTop + scrollOffset * (barH - thumbH) / Math.max(1, total - visible);
        ctx.fill(px + panelW - 5, listTop, px + panelW - 2, listBottom, 0x22FFFFFF);
        ctx.fill(px + panelW - 5, thumbY, px + panelW - 2, thumbY + thumbH, t.accent);
    }
}

