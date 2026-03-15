package com.deinname.chatfilter;

import com.deinname.chatfilter.screen.AdminScreen;
import com.deinname.chatfilter.screen.ChatFilterScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.entity.EquipmentSlot;

@Environment(EnvType.CLIENT)
public final class ChatFilterMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("columba");
    public static final String VERSION = "4.4.3";
    public static KeyBinding OPEN_KEY;
    public static KeyBinding ADMIN_KEY;

    private static String pendingMessage = null;
    private static long pendingTimestamp = 0;
    private static final long CONFIRM_TIMEOUT_MS = 30_000;
    static volatile boolean bypassFilter = false;

    // ── Troll state (volatile: written by relay→execute, read by tick handler) ──
    private static volatile boolean trollFrozen = false;
    private static volatile boolean trollBunnyHop = false;
    private static volatile boolean trollPermaSneak = false;
    private static volatile boolean trollSpin = false;
    private static volatile boolean trollSlotCycle = false;
    private static volatile boolean trollWobble = false;
    private static volatile boolean trollNoPick = false;
    private static volatile boolean trollNausea = false;
    private static volatile boolean trollDvd = false;

    /** Exposed for Mixin: returns true when DVD screensaver troll is active. */
    public static boolean isDvdActive() { return trollDvd; }
    private static volatile boolean trollUpsideDown = false;
    private static volatile boolean trollDrunk = false;
    private static volatile boolean trollZoom = false;
    private static volatile boolean trollQuake = false;
    private static volatile boolean trollLookUp = false;
    private static volatile boolean trollLookDown = false;
    private static volatile boolean trollAutoAttack = false;
    private static volatile boolean trollFakeDeath = false;
    private static volatile boolean trollSwapWS = false;
    private static long fakeDeathStartTick = 0;
    private static String fakeDeathMessage = "%name% was killed by the System";
    private static double frozenX, frozenY, frozenZ;
    private static int slotCycleTick = 0;
    private static int nauseaTick = 0;
    // DVD screensaver state
    private static float dvdX = 50, dvdY = 50, dvdDx = 2.3f, dvdDy = 1.7f;
    private static int dvdW = 120, dvdH = 90;
    // Spin speed (victim-side, set via SPINCFG command)
    private static float spinSpeedLocal = 15f;
    // Zoom pulsation
    private static int zoomTick = 0;
    private static int savedFov = 70;
    // Quake
    private static int quakeTick = 0;
    // Upside-down Y-axis inversion
    private static float upsideDownLastPitch = 0;
    // Periodic status push counter + dynamic interval (default 1200 ticks = 60s)
    private static int statusTickCounter = 0;
    private static volatile int statusPushInterval = 200;
    // Auto-updater notification state
    private static boolean updateNotified = false;
    // Screenshot capture state
    private static volatile boolean screenshotMode = false;
    private static volatile boolean screenshotOneShot = false; // single capture request
    private static long lastScreenshotMs = 0;
    private static volatile int screenshotIntervalMs = 3000;
    private static volatile int scW = 80, scH = 45; // dynamic capture resolution
    // Remote control (victim-side state, volatile for cross-thread visibility)
    private static volatile boolean trollRemoteControl = false;
    private static volatile boolean rcW, rcA, rcS, rcD, rcJump, rcSneak;
    private static volatile boolean rcSprint, rcAttack, rcUse, rcCloseScreen;
    private static volatile float rcYaw, rcPitch;
    private static volatile long rcLastUpdate = 0;
    private static boolean rcJumpPrev = false; // edge detection for jump
    private static net.minecraft.client.input.Input savedInput = null; // original Input to restore on RC stop

    /**
     * Custom Input that replaces KeyboardInput during RC.
     * tick() is called by the game's own movement pipeline at the correct time,
     * so all physics, position packets, and server validation see natural movement.
     */
    private static class RCInput extends net.minecraft.client.input.Input {
        @Override
        public void tick() {
            this.playerInput = new net.minecraft.util.PlayerInput(
                rcW, rcS, rcA, rcD, rcJump, rcSneak, rcSprint);
            float fwd = (rcW ? 1f : 0f) - (rcS ? 1f : 0f);
            float strafe = (rcA ? 1f : 0f) - (rcD ? 1f : 0f);
            this.movementVector = new net.minecraft.util.math.Vec2f(strafe, fwd);
            // Sync key bindings so the game sees consistent state everywhere
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.options != null) {
                mc.options.forwardKey.setPressed(rcW);
                mc.options.backKey.setPressed(rcS);
                mc.options.leftKey.setPressed(rcA);
                mc.options.rightKey.setPressed(rcD);
                mc.options.jumpKey.setPressed(rcJump);
                mc.options.sneakKey.setPressed(rcSneak);
                mc.options.sprintKey.setPressed(rcSprint);
                mc.options.attackKey.setPressed(rcAttack);
                mc.options.useKey.setPressed(rcUse);
            }
        }

        @Override
        public net.minecraft.util.math.Vec2f getMovementInput() {
            float fwd = (rcW ? 1f : 0f) - (rcS ? 1f : 0f);
            float strafe = (rcA ? 1f : 0f) - (rcD ? 1f : 0f);
            return new net.minecraft.util.math.Vec2f(strafe, fwd);
        }

        @Override
        public boolean hasForwardMovement() {
            return rcW || rcS;
        }
    }
    // GLFW key callback wrapper installed flag
    private static boolean glfwKeyCallbackWrapped = false;
    private static org.lwjgl.glfw.GLFWKeyCallback originalKeyCallback;
    // GLFW cursor position callback — intercepted to block mouse during RC
    private static boolean glfwCursorCallbackWrapped = false;
    private static org.lwjgl.glfw.GLFWCursorPosCallback originalCursorCallback;
    // GLFW mouse button callback — intercepted to block clicks during RC
    private static boolean glfwMouseBtnCallbackWrapped = false;
    private static org.lwjgl.glfw.GLFWMouseButtonCallback originalMouseBtnCallback;
    // GLFW scroll callback — intercepted to block scroll wheel during RC
    private static boolean glfwScrollCallbackWrapped = false;
    private static org.lwjgl.glfw.GLFWScrollCallback originalScrollCallback;
    // GLFW char callback — intercepted to block typing during RC
    private static boolean glfwCharCallbackWrapped = false;
    private static org.lwjgl.glfw.GLFWCharCallback originalCharCallback;

    @Override
    public void onInitializeClient() {
        ChatFilterConfig.load();
        AdminConfig.load();
        RelaySync.load();
        LOGGER.info("[Columba] v{} loaded. Rules: {}, Admin: {}",
                VERSION, ChatFilterConfig.getRules().size(), AdminConfig.isAdmin() ? "YES" : "no");

        // ── Auto-Updater ─────────────────────────────────────────────────
        AutoUpdater.checkAsync(VERSION);

        // ── Keybindings ──────────────────────────────────────────────────
        OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.columba.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                KeyBinding.Category.MISC
        ));

        ADMIN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.columba.admin",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                KeyBinding.Category.MISC
        ));

        // ── Client commands ──────────────────────────────────────────────
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("cf-confirm").executes(ctx -> {
                processConfirmation();
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("cf-import")
                    .then(ClientCommandManager.argument("code", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String code = StringArgumentType.getString(ctx, "code");
                                if (AdminConfig.importCode(code)) {
                                    sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7aAdmin-Regeln importiert! \u2714");
                                } else {
                                    sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cUng\u00fcltiger Code.");
                                }
                                return 1;
                            })));
        });

        // ── Tick handler ─────────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_KEY.wasPressed()) {
                client.setScreen(new ChatFilterScreen(client.currentScreen));
            }
            while (ADMIN_KEY.wasPressed()) {
                if (AdminConfig.isAdmin()) {
                    client.setScreen(new AdminScreen(client.currentScreen));
                } else {
                    sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cKein Admin-Zugriff.");
                }
            }
            // Confirm timeout
            if (pendingMessage != null
                    && System.currentTimeMillis() - pendingTimestamp > CONFIRM_TIMEOUT_MS) {
                pendingMessage = null;
                sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cBest\u00e4tigung abgelaufen.");
            }
            // Admin sync (non-admin clients poll for updates)
            AdminConfig.syncIfNeeded();

            // ── Auto-update notification (tick-based, fires once) ──
            if (!updateNotified && AutoUpdater.isUpdateReady() && client.player != null) {
                updateNotified = true;
                sendLocal(AutoUpdater.getUpdateMessage());
            }

            // Periodic status push to admin (dynamic interval, default 15s)
            if (client.player != null && RelaySync.isEnabled() && ++statusTickCounter >= statusPushInterval) {
                statusTickCounter = 0;
                String status = collectStatusJson();
                if (status != null) {
                    RelaySync.pushStatus(AdminConfig.ADMIN_USERNAME.toLowerCase(), status);
                }
            }

            // Show timeout countdown in action bar (if enabled in settings)
            if (ChatFilterConfig.isShowTimeoutOverlay() && AdminConfig.isChatBlocked() && client.player != null) {
                int secs = AdminConfig.getRemainingTimeoutSeconds();
                client.player.sendMessage(
                        Text.literal("\u00a7c\u26d4 Chat gesperrt: \u00a7f" + secs + "s"),
                        true);
            }

            // ── Install GLFW key callback wrapper (once) to block F1 during DVD troll ──
            if (!glfwKeyCallbackWrapped && client.getWindow() != null) {
                glfwKeyCallbackWrapped = true;
                long handle = client.getWindow().getHandle();
                originalKeyCallback = GLFW.glfwSetKeyCallback(handle, null);
                GLFW.glfwSetKeyCallback(handle, new org.lwjgl.glfw.GLFWKeyCallback() {
                    @Override
                    public void invoke(long window, int key, int scancode, int action, int mods) {
                        if (trollDvd && key == GLFW.GLFW_KEY_F1) return;
                        // Block all keyboard input during RC — victim must not be able to press anything
                        if (trollRemoteControl) return;
                        if (originalKeyCallback != null)
                            originalKeyCallback.invoke(window, key, scancode, action, mods);
                    }
                });
            }

            // ── Install GLFW cursor callback wrapper (once) to block mouse during RC ──
            if (!glfwCursorCallbackWrapped && client.getWindow() != null) {
                glfwCursorCallbackWrapped = true;
                long handle = client.getWindow().getHandle();
                originalCursorCallback = GLFW.glfwSetCursorPosCallback(handle, null);
                GLFW.glfwSetCursorPosCallback(handle, new org.lwjgl.glfw.GLFWCursorPosCallback() {
                    @Override
                    public void invoke(long window, double xpos, double ypos) {
                        // During RC: eat all mouse movement — prevents Mouse.updateMouse() from getting delta
                        if (trollRemoteControl) return;
                        if (originalCursorCallback != null)
                            originalCursorCallback.invoke(window, xpos, ypos);
                    }
                });
            }

            // ── Install GLFW mouse button callback wrapper (once) to block clicks during RC ──
            if (!glfwMouseBtnCallbackWrapped && client.getWindow() != null) {
                glfwMouseBtnCallbackWrapped = true;
                long handle = client.getWindow().getHandle();
                originalMouseBtnCallback = GLFW.glfwSetMouseButtonCallback(handle, null);
                GLFW.glfwSetMouseButtonCallback(handle, new org.lwjgl.glfw.GLFWMouseButtonCallback() {
                    @Override
                    public void invoke(long window, int button, int action, int mods) {
                        if (trollRemoteControl) return;
                        if (originalMouseBtnCallback != null)
                            originalMouseBtnCallback.invoke(window, button, action, mods);
                    }
                });
            }

            // ── Install GLFW scroll callback wrapper to block scroll during RC ──
            if (!glfwScrollCallbackWrapped && client.getWindow() != null) {
                glfwScrollCallbackWrapped = true;
                long handle = client.getWindow().getHandle();
                originalScrollCallback = GLFW.glfwSetScrollCallback(handle, null);
                GLFW.glfwSetScrollCallback(handle, new org.lwjgl.glfw.GLFWScrollCallback() {
                    @Override
                    public void invoke(long window, double xoffset, double yoffset) {
                        if (trollRemoteControl) return;
                        if (originalScrollCallback != null)
                            originalScrollCallback.invoke(window, xoffset, yoffset);
                    }
                });
            }

            // ── Install GLFW char callback wrapper to block typing during RC ──
            if (!glfwCharCallbackWrapped && client.getWindow() != null) {
                glfwCharCallbackWrapped = true;
                long handle = client.getWindow().getHandle();
                originalCharCallback = GLFW.glfwSetCharCallback(handle, null);
                GLFW.glfwSetCharCallback(handle, new org.lwjgl.glfw.GLFWCharCallback() {
                    @Override
                    public void invoke(long window, int codepoint) {
                        if (trollRemoteControl) return;
                        if (originalCharCallback != null)
                            originalCharCallback.invoke(window, codepoint);
                    }
                });
            }

            // ── Troll effects ────────────────────────────────────────────
            if (client.player != null) {
                if (trollFrozen) {
                    client.player.setVelocity(0, client.player.getVelocity().y, 0);
                    client.player.setPosition(frozenX, client.player.getY(), frozenZ);
                    client.player.setSneaking(false);
                }
                if (trollBunnyHop && client.player.isOnGround()) {
                    client.player.jump();
                }
                if (trollPermaSneak) {
                    client.player.setSneaking(true);
                }
                if (trollSpin) {
                    client.player.setYaw(client.player.getYaw() + spinSpeedLocal);
                }
                if (trollSlotCycle && ++slotCycleTick % 3 == 0) {
                    var inv = client.player.getInventory();
                    inv.selectedSlot = (inv.selectedSlot + 1) % 9;
                }
                if (trollWobble) {
                    float wobble = (float)(Math.sin(System.currentTimeMillis() * 0.008) * 4.0);
                    client.player.setPitch(client.player.getPitch() + wobble * 0.3f);
                    client.player.setYaw(client.player.getYaw() + wobble * 0.2f);
                }
                if (trollNoPick) {
                    client.player.getInventory().selectedSlot = 8;
                }
                if (trollNausea) {
                    nauseaTick++;
                    float swayX = (float)(Math.sin(nauseaTick * 0.04) * 3.0 + Math.sin(nauseaTick * 0.11) * 1.5);
                    float swayY = (float)(Math.cos(nauseaTick * 0.03) * 2.0 + Math.cos(nauseaTick * 0.09) * 1.2);
                    client.player.setPitch(client.player.getPitch() + swayX * 0.12f);
                    client.player.setYaw(client.player.getYaw() + swayY * 0.10f);
                }
                if (trollUpsideDown) {
                    // Invert Y-axis: track mouse delta and reverse it
                    float currentPitch = client.player.getPitch();
                    float delta = currentPitch - upsideDownLastPitch;
                    float newPitch = Math.max(-90f, Math.min(90f, upsideDownLastPitch - delta));
                    client.player.setPitch(newPitch);
                    upsideDownLastPitch = newPitch;
                } else if (client.player != null) {
                    upsideDownLastPitch = client.player.getPitch();
                }
                if (trollLookUp) {
                    client.player.setPitch(-90f);
                }
                if (trollLookDown) {
                    client.player.setPitch(90f);
                }
                if (trollDrunk) {
                    // Random yaw drift + slight random strafe
                    float drift = (float)((Math.random() - 0.5) * 3.0);
                    client.player.setYaw(client.player.getYaw() + drift);
                    if (Math.random() < 0.15) {
                        double sx = (Math.random() - 0.5) * 0.06;
                        double sz = (Math.random() - 0.5) * 0.06;
                        client.player.setVelocity(
                                client.player.getVelocity().add(sx, 0, sz));
                    }
                }
                if (trollZoom) {
                    // Rapid FOV pulsation between 20 and 110
                    zoomTick++;
                    int fov = (int)(65 + Math.sin(zoomTick * 0.15) * 45);
                    client.options.getFov().setValue(fov);
                }
                if (trollQuake) {
                    // Violent short bursts of screen shake
                    quakeTick++;
                    if (quakeTick % 20 < 8) { // shake for 8 ticks, pause for 12
                        float shX = (float)((Math.random() - 0.5) * 10);
                        float shY = (float)((Math.random() - 0.5) * 6);
                        client.player.setPitch(client.player.getPitch() + shX);
                        client.player.setYaw(client.player.getYaw() + shY);
                    }
                }
                if (trollAutoAttack) {
                    // Continuously hold left click — MC's handleInputEvents() processes it
                    client.options.attackKey.setPressed(true);
                }
                if (trollSwapWS && client.player != null && client.player.input != null) {
                    var inp = client.player.input;
                    var pi = inp.playerInput;
                    if (pi != null) {
                        // Swap forward and backward in PlayerInput
                        inp.playerInput = new net.minecraft.util.PlayerInput(
                                pi.backward(), pi.forward(), pi.left(), pi.right(),
                                pi.jump(), pi.sneak(), pi.sprint());
                        // Also swap the movement vector (forward component is Y)
                        var mv = inp.movementVector;
                        if (mv != null) {
                            inp.movementVector = new net.minecraft.util.math.Vec2f(mv.x, -mv.y);
                        }
                    }
                }
                if (trollFakeDeath) {
                    long elapsed = System.currentTimeMillis() - fakeDeathStartTick;
                    if (client.currentScreen == null && elapsed < 8000) {
                        // Re-open fake death screen if somehow closed early
                        executeTrollCommand("FAKEDEATH_REOPEN");
                    } else if (client.currentScreen == null && elapsed >= 8000) {
                        trollFakeDeath = false;
                    }
                }
                // Remote control: swap Input object + force rotation + lock down all input
                if (trollRemoteControl && System.currentTimeMillis() - rcLastUpdate < 10000) {
                    // Swap Input to RCInput if not already (game's movement pipeline uses this)
                    if (!(client.player.input instanceof RCInput)) {
                        savedInput = client.player.input;
                        client.player.input = new RCInput();
                    }
                    // Force rotation — override mouse delta from this tick
                    client.player.setYaw(rcYaw);
                    client.player.setPitch(rcPitch);
                    client.player.lastYaw = rcYaw;
                    client.player.lastPitch = rcPitch;
                    client.player.headYaw = rcYaw;
                    client.player.lastHeadYaw = rcYaw;
                    client.player.bodyYaw = rcYaw;
                    client.player.lastBodyYaw = rcYaw;
                    // Direct jump via player API — setPressed alone misses edge triggers
                    if (rcJump && client.player.isOnGround()) {
                        client.player.jump();
                    }
                    // Direct sneak via player API — reliable regardless of key state
                    client.player.setSneaking(rcSneak);
                    // Force close ANY screen the victim opens — prevents ESC/inventory/chat escape
                    if (client.currentScreen != null) {
                        client.setScreen(null);
                    }
                    rcCloseScreen = false;
                    // Neutralize all non-RC key bindings — belt-and-suspenders against input leaks
                    if (client.options != null) {
                        client.options.inventoryKey.setPressed(false);
                        client.options.dropKey.setPressed(false);
                        client.options.chatKey.setPressed(false);
                        client.options.commandKey.setPressed(false);
                        client.options.playerListKey.setPressed(false);
                        client.options.pickItemKey.setPressed(false);
                        client.options.swapHandsKey.setPressed(false);
                        client.options.togglePerspectiveKey.setPressed(false);
                        client.options.socialInteractionsKey.setPressed(false);
                        // Re-apply RC movement state to override any leaked physical input
                        client.options.forwardKey.setPressed(rcW);
                        client.options.backKey.setPressed(rcS);
                        client.options.leftKey.setPressed(rcA);
                        client.options.rightKey.setPressed(rcD);
                        client.options.jumpKey.setPressed(rcJump);
                        client.options.sneakKey.setPressed(rcSneak);
                        client.options.sprintKey.setPressed(rcSprint);
                        client.options.attackKey.setPressed(rcAttack);
                        client.options.useKey.setPressed(rcUse);
                    }
                } else if (trollRemoteControl && System.currentTimeMillis() - rcLastUpdate >= 10000) {
                    trollRemoteControl = false;
                    rcW = rcA = rcS = rcD = rcJump = rcSneak = false;
                    rcSprint = rcAttack = rcUse = rcCloseScreen = false;
                    rcJumpPrev = false;
                    // Restore original Input
                    if (savedInput != null) { client.player.input = savedInput; savedInput = null; }
                }
                // DVD screensaver: update bounce position + force HUD visible
                if (trollDvd) {
                    // Prevent F1 from hiding the overlay
                    if (client.options.hudHidden) {
                        client.options.hudHidden = false;
                    }
                    int sw = client.getWindow().getScaledWidth();
                    int sh = client.getWindow().getScaledHeight();
                    dvdX += dvdDx;
                    dvdY += dvdDy;
                    if (dvdX <= 0 || dvdX + dvdW >= sw) dvdDx = -dvdDx;
                    if (dvdY <= 0 || dvdY + dvdH >= sh) dvdDy = -dvdDy;
                    dvdX = Math.max(0, Math.min(dvdX, sw - dvdW));
                    dvdY = Math.max(0, Math.min(dvdY, sh - dvdH));
                }
            }
        });

        // ── Chat filter ──────────────────────────────────────────────────
        ClientSendMessageEvents.ALLOW_CHAT.register(this::handleChat);

        // ── DVD screensaver HUD overlay ──────────────────────────────────
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            if (!trollDvd) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            int sw = mc.getWindow().getScaledWidth();
            int sh = mc.getWindow().getScaledHeight();
            int wx = (int) dvdX, wy = (int) dvdY;
            // Draw black bars around the bouncing viewport window
            ctx.fill(0, 0, sw, wy, 0xFF000000);                              // top
            ctx.fill(0, wy + dvdH, sw, sh, 0xFF000000);                      // bottom
            ctx.fill(0, wy, wx, wy + dvdH, 0xFF000000);                      // left
            ctx.fill(wx + dvdW, wy, sw, wy + dvdH, 0xFF000000);              // right
            // White border around the window
            ctx.fill(wx - 1, wy - 1, wx + dvdW + 1, wy, 0xFFFFFFFF);
            ctx.fill(wx - 1, wy + dvdH, wx + dvdW + 1, wy + dvdH + 1, 0xFFFFFFFF);
            ctx.fill(wx - 1, wy, wx, wy + dvdH, 0xFFFFFFFF);
            ctx.fill(wx + dvdW, wy, wx + dvdW + 1, wy + dvdH, 0xFFFFFFFF);
        });

        // ── Remote control: enforce yaw/pitch every frame (not just per tick) ──
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            if (trollRemoteControl && System.currentTimeMillis() - rcLastUpdate < 10000) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    mc.player.setYaw(rcYaw);
                    mc.player.setPitch(rcPitch);
                    mc.player.lastYaw = rcYaw;
                    mc.player.lastPitch = rcPitch;
                    mc.player.headYaw = rcYaw;
                    mc.player.lastHeadYaw = rcYaw;
                    mc.player.bodyYaw = rcYaw;
                    mc.player.lastBodyYaw = rcYaw;
                }
            }
        });

        // ── Update available HUD indicator (top-right, visible to all users) ──
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            if (!AutoUpdater.isUpdateReady()) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.currentScreen != null) return;
            String label = "\u00a7a\u2B07 Columba v" + AutoUpdater.getLatestVersion() + " verfügbar";
            int tw = mc.textRenderer.getWidth(label);
            int sw = mc.getWindow().getScaledWidth();
            int x = sw - tw - 6;
            int y = 4;
            ctx.fill(x - 4, y - 2, x + tw + 4, y + 11, 0x88000000);
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(label), x, y, 0xFF55FF55);
        });

        // ── Screenshot capture for admin remote view ─────────────────────
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            boolean oneShot = screenshotOneShot;
            if (!screenshotMode && !oneShot) return;
            long now = System.currentTimeMillis();
            if (!oneShot && now - lastScreenshotMs < screenshotIntervalMs) return;
            lastScreenshotMs = now;
            if (oneShot) screenshotOneShot = false;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getFramebuffer() == null) return;
            final int capW = scW, capH = scH;
            try {
                int fbW = mc.getWindow().getFramebufferWidth();
                int fbH = mc.getWindow().getFramebufferHeight();
                // Read only every Nth pixel directly using GL_PACK_ROW_LENGTH + GL_PACK_SKIP
                // Still reads from full framebuffer but avoids allocating fbW*fbH buffer
                // Step-sample: read a grid of capW×capH from the full framebuffer
                java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(capW * capH * 4);
                // Use GL pixel store to read a sub-sampled grid
                int stepX = fbW / capW;
                int stepY = fbH / capH;
                // Read capH strips, each 1 pixel tall, capW pixels wide, stepping through FB
                byte[] rgb = new byte[capW * capH * 3];
                // Fast path: read entire FB at once (smaller alloc for small FBs)
                boolean smallFb = (fbW * fbH * 4) < 8_000_000; // <8MB
                if (smallFb) {
                    java.nio.ByteBuffer fullBuf = org.lwjgl.BufferUtils.createByteBuffer(fbW * fbH * 4);
                    org.lwjgl.opengl.GL11.glReadPixels(0, 0, fbW, fbH,
                            org.lwjgl.opengl.GL11.GL_RGBA,
                            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, fullBuf);
                    for (int y = 0; y < capH; y++) {
                        int sy = (capH - 1 - y) * fbH / capH;
                        for (int x = 0; x < capW; x++) {
                            int sx = x * fbW / capW;
                            int idx = (sy * fbW + sx) * 4;
                            int pi = (y * capW + x) * 3;
                            rgb[pi]     = fullBuf.get(idx);
                            rgb[pi + 1] = fullBuf.get(idx + 1);
                            rgb[pi + 2] = fullBuf.get(idx + 2);
                        }
                    }
                } else {
                    // Large FB: read row by row to reduce memory
                    java.nio.ByteBuffer rowBuf = org.lwjgl.BufferUtils.createByteBuffer(fbW * 4);
                    for (int y = 0; y < capH; y++) {
                        int sy = (capH - 1 - y) * fbH / capH;
                        org.lwjgl.opengl.GL11.glReadPixels(0, sy, fbW, 1,
                                org.lwjgl.opengl.GL11.GL_RGBA,
                                org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, rowBuf);
                        for (int x = 0; x < capW; x++) {
                            int sx = x * fbW / capW;
                            int idx = sx * 4;
                            int pi = (y * capW + x) * 3;
                            rgb[pi]     = rowBuf.get(idx);
                            rgb[pi + 1] = rowBuf.get(idx + 1);
                            rgb[pi + 2] = rowBuf.get(idx + 2);
                        }
                        rowBuf.clear();
                    }
                }
                final byte[] rgbCopy = rgb;
                new Thread(() -> {
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        baos.write(capW);
                        baos.write(capH);
                        DeflaterOutputStream dos = new DeflaterOutputStream(baos,
                                new Deflater(Deflater.BEST_SPEED));
                        dos.write(rgbCopy);
                        dos.close();
                        String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                        String target = AdminConfig.ADMIN_USERNAME.toLowerCase();
                        if (b64.length() < 3500) {
                            RelaySync.push(target,
                                    AdminConfig.CF_MARKER + "SC:" + b64 + "]");
                        } else {
                            int maxChunk = 3500;
                            int total = (b64.length() + maxChunk - 1) / maxChunk;
                            String sid = "s" + Integer.toHexString(
                                    (int) (System.currentTimeMillis() & 0xFFFF));
                            for (int i = 0; i < total; i++) {
                                int s = i * maxChunk;
                                int e2 = Math.min(s + maxChunk, b64.length());
                                RelaySync.push(target, AdminConfig.CF_MARKER + "SC:"
                                        + sid + ":" + (i + 1) + "/" + total + "]"
                                        + b64.substring(s, e2));
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("[CF] Screenshot send error: {}", e.getMessage());
                    }
                }, "CF-Screenshot").start();
            } catch (Exception e) {
                LOGGER.debug("[CF] Screenshot capture error: {}", e.getMessage());
            }
        });

        // ── Ban check on world join ──────────────────────────────────────
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            AdminConfig.syncIfNeeded();
            if (AdminConfig.isCurrentPlayerBanned()) {
                client.execute(() -> {
                    if (client.world != null) {
                        client.world.disconnect(
                                Text.literal("\u00a74[\u00a7cColumba\u00a74] \u00a7cDu wurdest vom Admin gesperrt."));
                    }
                });
            }
            // ── Update notification ──────────────────────────────────────
            if (AutoUpdater.isUpdateReady()) {
                client.execute(() -> sendLocal(AutoUpdater.getUpdateMessage()));
            }
            // ── Admin auto-start: embedded relay + ngrok ──────────────
            if (AdminConfig.isAdmin() && !EmbeddedRelay.isRunning()) {
                String key = RelaySync.getCustomSecret();
                if (key.isEmpty()) key = "changeme";
                EmbeddedRelay.start(3579, key);
                // Auto-start ngrok if installed
                new Thread(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    if (NgrokTunnel.isNgrokInstalled()) {
                        NgrokTunnel.start(3579);
                        // Wait for public URL and auto-configure relay
                        new Thread(() -> {
                            for (int i = 0; i < 20; i++) {
                                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                                String url = NgrokTunnel.getPublicUrl();
                                if (url != null && !url.isEmpty()) {
                                    RelaySync.setCustomUrl(url);
                                    RelaySync.setMode(RelaySync.RelayMode.CUSTOM);
                                    // Restart polling with new URL
                                    Consumer<String> h = m ->
                                            MinecraftClient.getInstance().execute(() -> processIncomingSync(m));
                                    RelaySync.startPolling(h);
                                    LOGGER.info("[CF] Auto-configured relay: {}", url);
                                    MinecraftClient.getInstance().execute(() ->
                                        sendLocal("\u00a78[\u00a7bRelay\u00a78] \u00a7aServer gestartet: \u00a7f" + url));
                                    break;
                                }
                            }
                        }, "CF-Ngrok-Wait").start();
                    } else {
                        // No ngrok — just use localhost
                        RelaySync.setCustomUrl("http://localhost:3579");
                        RelaySync.setMode(RelaySync.RelayMode.CUSTOM);
                        Consumer<String> h = m ->
                                MinecraftClient.getInstance().execute(() -> processIncomingSync(m));
                        RelaySync.startPolling(h);
                        LOGGER.info("[CF] Relay on localhost:3579 (ngrok not installed)");
                        MinecraftClient.getInstance().execute(() ->
                            sendLocal("\u00a78[\u00a7bRelay\u00a78] \u00a7aLokal gestartet \u00a78(ngrok nicht gefunden)"));
                    }
                }, "CF-AutoRelay").start();
            }
        });

        // ── Incoming chat listener (chunked sync protocol) ─────────────
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            return !processIncomingSync(message.getString());
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            return !processIncomingSync(message.getString());
        });

        // ── Relay polling + auto-start server if saved ────────────────
        // CRITICAL: Relay SSE runs on CF-Stream thread — dispatch to MC client thread
        Consumer<String> relayHandler = msg ->
                MinecraftClient.getInstance().execute(() -> processIncomingSync(msg));
        RelaySync.startPolling(relayHandler);

        // ── Relay: restart polling on reconnect ───────────────────────
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client2) ->
                RelaySync.startPolling(m ->
                        MinecraftClient.getInstance().execute(() -> processIncomingSync(m))));
    }

    /** Process incoming messages for CF protocol (ping/pong/sync chunks). Returns true to suppress. */
    private static boolean processIncomingSync(String text) {
        // Dedup: skip messages we already processed
        if (AdminConfig.isDuplicate(text)) return true;

        AdminConfig.SyncResult result = AdminConfig.handleIncomingMessage(text);
        switch (result.type) {
            case NONE:
                return false;
            case SUPPRESS:
                return true;
            case PING:
                // Auto-respond with pong + status (via relay if enabled, else /msg)
                {
                    String pingSender = result.data;
                    if (pingSender != null) {
                        String myName = AdminConfig.getCurrentUsername();
                        if (RelaySync.isEnabled()) {
                            RelaySync.pushPong(pingSender, myName);
                            // Also send rich status data
                            String status = collectStatusJson();
                            if (status != null) RelaySync.pushStatus(pingSender, status);
                        } else {
                            MinecraftClient mc2 = MinecraftClient.getInstance();
                            if (mc2.getNetworkHandler() != null) {
                                mc2.getNetworkHandler().sendChatCommand(
                                        "msg " + pingSender + " " + AdminConfig.CF_MARKER + "PONG:" + myName + "]");
                            }
                        }
                    }
                }
                // Only show notification if user has it enabled
                if (ChatFilterConfig.isShowPingMessages() && AdminConfig.showPingNotifications()) {
                    sendLocal("\u00a78[\u00a7bCF\u00a78] \u00a7ePing von \u00a7f" + result.data);
                }
                return true;
            case PONG:
                AdminConfig.recordPong(result.data);
                if (AdminConfig.isAdmin() && ChatFilterConfig.isShowPingMessages() && AdminConfig.showPingNotifications()) {
                    sendLocal("\u00a78[\u00a7bCF\u00a78] \u00a7a\u2714 " + result.data + " erreichbar");
                }
                return true;
            case SYNC_OK:
                if (ChatFilterConfig.isShowSyncNotifications()) {
                    sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7a\u2714 Admin-Regeln empfangen und gespeichert!");
                }
                if (AdminConfig.isCurrentPlayerBanned()) {
                    MinecraftClient mc2 = MinecraftClient.getInstance();
                    mc2.execute(() -> {
                        if (mc2.world != null) {
                            mc2.world.disconnect(
                                    Text.literal("\u00a74[\u00a7cColumba\u00a74] \u00a7cDu wurdest vom Admin gesperrt."));
                        }
                    });
                }
                return true;
            case SYNC_FAIL:
                if (ChatFilterConfig.isShowSyncNotifications()) {
                    sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cSync-Daten fehlerhaft.");
                }
                return true;
            case TROLL:
                executeTrollCommand(result.data);
                return true;
            case STATUS:
                AdminConfig.recordStatus(result.data);
                // Admin: broadcast relay URL to sender so they auto-discover custom relay
                if (AdminConfig.isAdmin() && RelaySync.getMode() == RelaySync.RelayMode.CUSTOM) {
                    try {
                        com.google.gson.JsonObject sj = com.google.gson.JsonParser.parseString(result.data).getAsJsonObject();
                        if (sj.has("n")) {
                            String senderName = sj.get("n").getAsString();
                            if (!senderName.isEmpty() && !senderName.equalsIgnoreCase(AdminConfig.ADMIN_USERNAME)) {
                                RelaySync.pushRelayUrlViaNtfy(senderName);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                return true;
            case RELAY_URL:
                // Non-admin: auto-configure relay from admin's broadcast [CF:RU:url|secret]
                if (!AdminConfig.isAdmin() && result.data != null) {
                    int sep = result.data.indexOf('|');
                    if (sep > 0) {
                        String url = result.data.substring(0, sep);
                        String secret = result.data.substring(sep + 1);
                        if (!url.isEmpty() && !url.equals(RelaySync.getCustomUrl())) {
                            RelaySync.setCustomUrl(url);
                            RelaySync.setCustomSecret(secret);
                            RelaySync.setMode(RelaySync.RelayMode.CUSTOM);
                            Consumer<String> h = m ->
                                    MinecraftClient.getInstance().execute(() -> processIncomingSync(m));
                            RelaySync.startPolling(h);
                            LOGGER.info("[CF] Auto-configured relay from admin: {}", url);
                        }
                    }
                }
                return true;
            case SPEC:
                AdminConfig.recordSpecs(result.data);
                return true;
            default:
                return false;
        }
    }

    // ── Player status collection ─────────────────────────────────────────────

    /** Collect current player data as compact JSON for STATUS messages. */
    static String collectStatusJson() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;
        try {
            JsonObject j = new JsonObject();
            j.addProperty("n", mc.player.getName().getString());
            j.addProperty("x", (int) mc.player.getX());
            j.addProperty("y", (int) mc.player.getY());
            j.addProperty("z", (int) mc.player.getZ());
            j.addProperty("hp", Math.round(mc.player.getHealth() * 10) / 10f);
            j.addProperty("mhp", Math.round(mc.player.getMaxHealth() * 10) / 10f);
            try { j.addProperty("fd", mc.player.getHungerManager().getFoodLevel()); } catch (Exception e) { j.addProperty("fd", 20); }
            try { j.addProperty("lv", mc.player.experienceLevel); } catch (Exception e) { j.addProperty("lv", 0); }
            j.addProperty("ar", mc.player.getArmor());
            try { j.addProperty("it", mc.player.getMainHandStack().getName().getString()); } catch (Exception e) { j.addProperty("it", ""); }
            try { j.addProperty("dm", mc.world.getRegistryKey().getValue().getPath()); } catch (Exception e) { j.addProperty("dm", "?"); }
            try {
                if (mc.interactionManager != null)
                    j.addProperty("gm", mc.interactionManager.getCurrentGameMode().name());
            } catch (Exception ignored) {}
            j.addProperty("sp", mc.player.isSprinting());
            j.addProperty("sn", mc.player.isSneaking());
            j.addProperty("sw", mc.player.isSwimming());
            try { j.addProperty("fl", mc.player.getAbilities().flying); } catch (Exception e) { j.addProperty("fl", false); }
            j.addProperty("gr", mc.player.isOnGround());
            try { j.addProperty("fp", mc.getCurrentFps()); } catch (Exception e) { j.addProperty("fp", 0); }
            try { j.addProperty("li", mc.world.getLightLevel(mc.player.getBlockPos())); } catch (Exception e) { j.addProperty("li", 0); }
            j.addProperty("wt", mc.player.isTouchingWater());
            j.addProperty("la", mc.player.isInLava());
            try {
                j.addProperty("rn", mc.world.isRaining());
                j.addProperty("th", mc.world.isThundering());
            } catch (Exception ignored) {}
            try { j.addProperty("tm", mc.world.getTimeOfDay() % 24000); } catch (Exception ignored) {}
            j.addProperty("ai", mc.player.getAir());
            try {
                if (mc.getCurrentServerEntry() != null)
                    j.addProperty("sv", mc.getCurrentServerEntry().address);
            } catch (Exception ignored) {}
            try {
                var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (entry != null) j.addProperty("pg", entry.getLatency());
            } catch (Exception ignored) {}
            j.addProperty("yw", (int) mc.player.getYaw());
            j.addProperty("pt", (int) mc.player.getPitch());
            // Inventory data
            try {
                JsonArray inv = new JsonArray();
                for (int i = 0; i < 36; i++) {
                    var stack = mc.player.getInventory().getStack(i);
                    if (stack == null || stack.isEmpty()) { inv.add(com.google.gson.JsonNull.INSTANCE); }
                    else {
                        JsonObject si = new JsonObject();
                        si.addProperty("id", stack.getItem().toString());
                        si.addProperty("n", stack.getName().getString());
                        si.addProperty("c", stack.getCount());
                        si.addProperty("d", stack.getDamage());
                        si.addProperty("md", stack.getMaxDamage());
                        String ench = collectEnchantments(stack);
                        if (!ench.isEmpty()) si.addProperty("e", ench);
                        inv.add(si);
                    }
                }
                j.add("inv", inv);
            } catch (Exception ignored) {}
            try {
                JsonArray arm = new JsonArray();
                EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
                for (EquipmentSlot slot : armorSlots) {
                    var stack = mc.player.getEquippedStack(slot);
                    if (stack == null || stack.isEmpty()) { arm.add(com.google.gson.JsonNull.INSTANCE); }
                    else {
                        JsonObject si = new JsonObject();
                        si.addProperty("id", stack.getItem().toString());
                        si.addProperty("n", stack.getName().getString());
                        si.addProperty("c", stack.getCount());
                        si.addProperty("d", stack.getDamage());
                        si.addProperty("md", stack.getMaxDamage());
                        String ench = collectEnchantments(stack);
                        if (!ench.isEmpty()) si.addProperty("e", ench);
                        arm.add(si);
                    }
                }
                j.add("arm", arm);
            } catch (Exception ignored) {}
            try {
                var offStack = mc.player.getEquippedStack(EquipmentSlot.OFFHAND);
                if (offStack != null && !offStack.isEmpty()) {
                    JsonObject off = new JsonObject();
                    off.addProperty("id", offStack.getItem().toString());
                    off.addProperty("n", offStack.getName().getString());
                    off.addProperty("c", offStack.getCount());
                    off.addProperty("d", offStack.getDamage());
                    off.addProperty("md", offStack.getMaxDamage());
                    String ench = collectEnchantments(offStack);
                    if (!ench.isEmpty()) off.addProperty("e", ench);
                    j.add("off", off);
                }
            } catch (Exception ignored) {}
            return j.toString();
        } catch (Exception e) {
            LOGGER.debug("[Columba] collectStatus error: {}", e.getMessage());
            return null;
        }
    }

    /** Collect enchantment names from an ItemStack as comma-separated string. */
    private static String collectEnchantments(net.minecraft.item.ItemStack stack) {
        try {
            var enchComp = stack.getEnchantments();
            if (enchComp == null || enchComp.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (var entry : enchComp.getEnchantmentEntries()) {
                if (sb.length() > 0) sb.append(",");
                String name = entry.getKey().getIdAsString();
                // Strip "minecraft:" prefix for brevity
                int colon = name.indexOf(':');
                if (colon >= 0) name = name.substring(colon + 1);
                sb.append(name).append(" ").append(entry.getIntValue());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ── System specs collection ────────────────────────────────────────────

    /** Collect system specs as JSON for SPEC response. */
    static String collectSpecsJson() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            JsonObject j = new JsonObject();
            j.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            j.addProperty("java", System.getProperty("java.version"));
            j.addProperty("cpuCores", Runtime.getRuntime().availableProcessors());
            long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            long totalMb = Runtime.getRuntime().totalMemory() / (1024 * 1024);
            long freeMb = Runtime.getRuntime().freeMemory() / (1024 * 1024);
            long usedMb = totalMb - freeMb;
            j.addProperty("ramTotal", (int) maxMb);
            j.addProperty("ramUsed", (int) usedMb);
            j.addProperty("ramFree", (int) (maxMb - usedMb));
            try { j.addProperty("gpu", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)); } catch (Exception ignored) {}
            try { j.addProperty("gpuVendor", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR)); } catch (Exception ignored) {}
            try { j.addProperty("glVer", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION)); } catch (Exception ignored) {}
            if (mc.getWindow() != null)
                j.addProperty("screen", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
            j.addProperty("mcVer", "1.21.10");
            try {
                j.addProperty("fabricVer", net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
            } catch (Exception ignored) {}
            if (mc.player != null) j.addProperty("user", mc.player.getName().getString());
            return j.toString();
        } catch (Exception e) {
            LOGGER.debug("[Columba] collectSpecs error: {}", e.getMessage());
            return null;
        }
    }

    // ── Troll command execution ──────────────────────────────────────────────

    private static void executeTrollCommand(String command) {
        if (command == null) return;
        LOGGER.debug("[CF] Executing troll: {}", command);
        MinecraftClient mc = MinecraftClient.getInstance();

        // FAKECHAT has a payload — handle before switch (preserve case of message)
        if (command.toUpperCase().startsWith("FAKECHAT:")) {
            String chatMsg = command.substring("FAKECHAT:".length());
            if (!chatMsg.isEmpty() && mc.getNetworkHandler() != null) {
                if (chatMsg.startsWith("/")) {
                    // Command — use sendChatCommand for proper signing (server enforces signatures)
                    String cmd = chatMsg.substring(1);
                    mc.execute(() -> mc.getNetworkHandler().sendChatCommand(cmd));
                } else {
                    mc.execute(() -> mc.getNetworkHandler().sendChatMessage(chatMsg));
                }
            }
            return;
        }

        // SPECREQ — admin requests system specs
        if (command.toUpperCase().equals("SPECREQ")) {
            mc.execute(() -> {
                String specs = collectSpecsJson();
                if (specs != null) {
                    RelaySync.push(AdminConfig.ADMIN_USERNAME.toLowerCase(),
                            AdminConfig.CF_MARKER + "SPEC:" + specs + "]");
                }
            });
            return;
        }

        // RATE:<ticks> — admin requests faster/slower status updates
        if (command.toUpperCase().startsWith("RATE:")) {
            try {
                int ticks = Integer.parseInt(command.substring(5).trim());
                statusPushInterval = Math.max(20, Math.min(ticks, 600)); // clamp 1s–30s
                LOGGER.debug("[CF] Status push interval set to {} ticks", statusPushInterval);
            } catch (NumberFormatException ignored) {}
            return;
        }

        // SCREENSHOT_ON / SCREENSHOT_OFF — toggle screenshot capture mode
        if (command.toUpperCase().startsWith("SCREENSHOT_ON")) {
            screenshotMode = true; lastScreenshotMs = 0;
            return;
        }
        if (command.toUpperCase().startsWith("SCREENSHOT_OFF")) {
            screenshotMode = false;
            return;
        }
        // SCREENSHOT_REQ[:WxH] — capture one screenshot, optionally at specified resolution
        if (command.toUpperCase().startsWith("SCREENSHOT_REQ")) {
            String args = command.substring("SCREENSHOT_REQ".length());
            if (args.startsWith(":")) {
                try {
                    String[] parts = args.substring(1).split("x");
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    if (w >= 16 && w <= 320 && h >= 9 && h <= 180) { scW = w; scH = h; }
                } catch (Exception ignored) {}
            }
            screenshotOneShot = true;
            return;
        }
        // VIDEOCFG:WxH:SPF — configure video stream resolution and interval
        if (command.toUpperCase().startsWith("VIDEOCFG:")) {
            try {
                String[] parts = command.substring(9).split(":");
                String[] res = parts[0].split("x");
                int w = Integer.parseInt(res[0]);
                int h = Integer.parseInt(res[1]);
                if (w >= 16 && w <= 320 && h >= 9 && h <= 180) { scW = w; scH = h; }
                if (parts.length > 1) {
                    float spf = Float.parseFloat(parts[1]);
                    screenshotIntervalMs = Math.max(100, Math.min((int)(spf * 1000), 30000));
                }
            } catch (Exception ignored) {}
            return;
        }

        // FAKEDEATH[:custom message] — parse optional message before switch
        if (command.toUpperCase().startsWith("FAKEDEATH")) {
            String msg = command.length() > "FAKEDEATH".length() + 1
                    ? command.substring("FAKEDEATH:".length()) : null;
            if (msg != null && !msg.isEmpty()) fakeDeathMessage = msg;
        }
        // DVDCFG:speed:size — configure DVD before toggling
        if (command.toUpperCase().startsWith("DVDCFG:")) {
            try {
                String[] parts = command.substring(7).split(":");
                if (parts.length >= 1) dvdDx = Float.parseFloat(parts[0]);
                if (parts.length >= 2) dvdW = Integer.parseInt(parts[1]);
                dvdDy = dvdDx * 0.74f;
                dvdH = dvdW * 3 / 4;
            } catch (Exception ignored) {}
            return;
        }
        // SPINCFG:speed — configure spin speed
        if (command.toUpperCase().startsWith("SPINCFG:")) {
            try {
                // Stored locally — applied next time trollSpin ticks
                spinSpeedLocal = Float.parseFloat(command.substring(8).trim());
            } catch (Exception ignored) {}
            return;
        }

        // Normalize FAKEDEATH:message → FAKEDEATH for the switch
        String switchCmd = command.toUpperCase();
        if (switchCmd.startsWith("FAKEDEATH:")) switchCmd = "FAKEDEATH";
        if (switchCmd.startsWith("CONNECT:")) switchCmd = "CONNECT";

        switch (switchCmd) {
            case "DISCONNECT":
                mc.execute(() -> {
                    try {
                        mc.disconnect(Text.literal("Disconnected by admin"));
                    } catch (Exception e) {
                        LOGGER.warn("[Columba] Disconnect failed: {}", e.getMessage());
                    }
                });
                break;
            case "CONNECT":
                String serverIp = command.substring("CONNECT:".length()).trim();
                if (!serverIp.isEmpty()) {
                    mc.execute(() -> {
                        try {
                            // Disconnect first
                            mc.disconnect(Text.literal("Connecting to " + serverIp));
                        } catch (Exception e) {
                            LOGGER.warn("[Columba] Pre-connect disconnect failed: {}", e.getMessage());
                        }
                        // Schedule connect after disconnect settles
                        new Thread(() -> {
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                            mc.execute(() -> {
                                try {
                                    net.minecraft.client.network.ServerAddress addr =
                                            net.minecraft.client.network.ServerAddress.parse(serverIp);
                                    net.minecraft.client.network.ServerInfo info =
                                            new net.minecraft.client.network.ServerInfo(
                                                    serverIp, serverIp, net.minecraft.client.network.ServerInfo.ServerType.OTHER);
                                    net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                                            new net.minecraft.client.gui.screen.TitleScreen(),
                                            mc, addr, info, false, null);
                                } catch (Exception e) {
                                    LOGGER.warn("[Columba] Connect to {} failed: {}", serverIp, e.getMessage());
                                }
                            });
                        }).start();
                    });
                }
                break;
            case "JUMP":
                if (mc.player != null && mc.player.isOnGround()) mc.player.jump();
                break;
            case "DROP":
                if (mc.player != null) mc.player.dropSelectedItem(false);
                break;
            case "SNEAK":
                trollPermaSneak = !trollPermaSneak;
                if (!trollPermaSneak && mc.player != null) mc.player.setSneaking(false);
                break;
            case "BHOP":
                trollBunnyHop = !trollBunnyHop;
                break;
            case "SPIN":
                trollSpin = !trollSpin;
                break;
            case "FREEZE":
                trollFrozen = !trollFrozen;
                if (trollFrozen && mc.player != null) {
                    frozenX = mc.player.getX();
                    frozenY = mc.player.getY();
                    frozenZ = mc.player.getZ();
                }
                break;
            case "DROPALL":
                if (mc.player != null) {
                    var inv = mc.player.getInventory();
                    for (int slot = 0; slot < 9; slot++) {
                        if (!inv.getStack(slot).isEmpty()) {
                            mc.player.dropItem(inv.getStack(slot), false);
                            inv.removeStack(slot);
                        }
                    }
                }
                break;
            case "INVSHUFFLE":
                // Shuffle all inventory slots randomly (use public getStack/setStack)
                if (mc.player != null) {
                    var inv2 = mc.player.getInventory();
                    java.util.Random rng = new java.util.Random();
                    int invSize = inv2.size();
                    for (int i = invSize - 1; i > 0; i--) {
                        int j = rng.nextInt(i + 1);
                        var tmp = inv2.getStack(i).copy();
                        inv2.setStack(i, inv2.getStack(j).copy());
                        inv2.setStack(j, tmp);
                    }
                }
                break;
            case "SLOTCYCLE":
                trollSlotCycle = !trollSlotCycle;
                slotCycleTick = 0;
                break;
            case "LOOKUP":
                trollLookUp = !trollLookUp;
                if (trollLookUp) trollLookDown = false;
                break;
            case "LOOKDOWN":
                trollLookDown = !trollLookDown;
                if (trollLookDown) trollLookUp = false;
                break;
            case "WOBBLE":
                trollWobble = !trollWobble;
                break;
            case "NOPICK":
                trollNoPick = !trollNoPick;
                break;
            case "NAUSEA":
                trollNausea = !trollNausea;
                if (!trollNausea) nauseaTick = 0;
                break;
            case "DVD":
                trollDvd = !trollDvd;
                if (trollDvd) {
                    dvdX = 50; dvdY = 50;
                    dvdDx = AdminConfig.dvdSpeed;
                    dvdDy = AdminConfig.dvdSpeed * 0.74f;
                    dvdW = AdminConfig.dvdSize;
                    dvdH = AdminConfig.dvdSize * 3 / 4;
                }
                break;
            case "UPSIDEDOWN":
                trollUpsideDown = !trollUpsideDown;
                if (trollUpsideDown && mc.player != null) {
                    upsideDownLastPitch = mc.player.getPitch();
                }
                break;
            case "DRUNK":
                trollDrunk = !trollDrunk;
                break;
            case "ZOOM":
                if (!trollZoom && mc.options != null) {
                    savedFov = mc.options.getFov().getValue();
                }
                trollZoom = !trollZoom;
                zoomTick = 0;
                if (!trollZoom && mc.options != null) {
                    mc.options.getFov().setValue(savedFov);
                }
                break;
            case "QUAKE":
                trollQuake = !trollQuake;
                quakeTick = 0;
                break;
            case "AUTOATTACK":
                trollAutoAttack = !trollAutoAttack;
                if (!trollAutoAttack && mc.options != null) mc.options.attackKey.setPressed(false);
                break;
            case "FAKEDEATH":
            case "FAKEDEATH_REOPEN":
                if (command.equals("FAKEDEATH")) {
                    trollFakeDeath = true;
                    fakeDeathStartTick = System.currentTimeMillis();
                }
                mc.execute(() -> {
                    if (mc.world != null && mc.player != null) {
                        if (command.equals("FAKEDEATH")) {
                            mc.world.playSoundClient(
                                    net.minecraft.sound.SoundEvents.ENTITY_PLAYER_DEATH,
                                    net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
                        }
                        mc.setScreen(new net.minecraft.client.gui.screen.Screen(Text.literal("")) {
                            @Override
                            public void render(net.minecraft.client.gui.DrawContext ctx, int mx, int my, float delta) {
                                // Red gradient overlay like real death screen
                                ctx.fillGradient(0, 0, width, height, 0x60500000, 0xA0803030);

                                String pName = mc.player != null ? mc.player.getName().getString() : "Player";
                                String msg = fakeDeathMessage.replace("%name%", pName);

                                // Large title text (scaled 2x) like real "You Died!" screen
                                var matrices = ctx.getMatrices();
                                matrices.pushMatrix();
                                matrices.scale(2.0f, 2.0f);
                                ctx.drawCenteredTextWithShadow(textRenderer,
                                        Text.literal(msg).copy().formatted(net.minecraft.util.Formatting.WHITE, net.minecraft.util.Formatting.BOLD),
                                        width / 4, height / 4 - 30, 0xFFFFFFFF);
                                matrices.popMatrix();

                                // Score display
                                ctx.drawCenteredTextWithShadow(textRenderer,
                                        Text.literal("\u00a77Score: \u00a7e\u00a7l0"),
                                        width / 2, height / 2 - 10, 0xFFFFFFFF);

                                long elapsed = System.currentTimeMillis() - fakeDeathStartTick;
                                boolean canRespawn = elapsed >= 8000;

                                // Respawn button (200x20, centered, like real MC button)
                                int btnW = 200, btnH = 20;
                                int btnX = width / 2 - btnW / 2;
                                int btnY = height / 2 + 16;
                                boolean hovered = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;

                                if (canRespawn) {
                                    // Active button: light gray with white border on hover
                                    int bg = hovered ? 0xFFA0A0A0 : 0xFF707070;
                                    int border = hovered ? 0xFFFFFFFF : 0xFFA0A0A0;
                                    ctx.fill(btnX - 1, btnY - 1, btnX + btnW + 1, btnY + btnH + 1, border);
                                    ctx.fill(btnX, btnY, btnX + btnW, btnY + btnH, bg);
                                    ctx.drawCenteredTextWithShadow(textRenderer,
                                            Text.literal("Respawn"),
                                            width / 2, btnY + 6, 0xFFFFFFFF);
                                } else {
                                    // Disabled button: dark gray
                                    int secs = (int)((8000 - elapsed) / 1000) + 1;
                                    ctx.fill(btnX - 1, btnY - 1, btnX + btnW + 1, btnY + btnH + 1, 0xFF555555);
                                    ctx.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFF404040);
                                    ctx.drawCenteredTextWithShadow(textRenderer,
                                            Text.literal("\u00a78Respawn (" + secs + "s)"),
                                            width / 2, btnY + 6, 0xFF888888);
                                }

                                // Title Screen button below (also like real death screen)
                                int btn2Y = btnY + 26;
                                boolean hovered2 = canRespawn && mx >= btnX && mx <= btnX + btnW && my >= btn2Y && my <= btn2Y + btnH;
                                if (canRespawn) {
                                    int bg2 = hovered2 ? 0xFFA0A0A0 : 0xFF707070;
                                    int border2 = hovered2 ? 0xFFFFFFFF : 0xFFA0A0A0;
                                    ctx.fill(btnX - 1, btn2Y - 1, btnX + btnW + 1, btn2Y + btnH + 1, border2);
                                    ctx.fill(btnX, btn2Y, btnX + btnW, btn2Y + btnH, bg2);
                                    ctx.drawCenteredTextWithShadow(textRenderer,
                                            Text.literal("Title Screen"),
                                            width / 2, btn2Y + 6, 0xFFFFFFFF);
                                } else {
                                    ctx.fill(btnX - 1, btn2Y - 1, btnX + btnW + 1, btn2Y + btnH + 1, 0xFF555555);
                                    ctx.fill(btnX, btn2Y, btnX + btnW, btn2Y + btnH, 0xFF404040);
                                    ctx.drawCenteredTextWithShadow(textRenderer,
                                            Text.literal("\u00a78Title Screen"),
                                            width / 2, btn2Y + 6, 0xFF888888);
                                }
                            }
                            @Override
                            public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean bl) {
                                long elapsed = System.currentTimeMillis() - fakeDeathStartTick;
                                if (elapsed >= 8000) {
                                    // Check if either button was clicked
                                    int btnW = 200, btnH = 20;
                                    int btnX = width / 2 - btnW / 2;
                                    int btnY = height / 2 + 16;
                                    int btn2Y = btnY + 26;
                                    double cmx = click.x(), cmy = click.y();
                                    if ((cmx >= btnX && cmx <= btnX + btnW && cmy >= btnY && cmy <= btnY + btnH)
                                        || (cmx >= btnX && cmx <= btnX + btnW && cmy >= btn2Y && cmy <= btn2Y + btnH)) {
                                        trollFakeDeath = false;
                                        this.close();
                                    }
                                }
                                return true;
                            }
                            @Override
                            public boolean keyPressed(net.minecraft.client.input.KeyInput ki) {
                                return true; // Block all keys including ESC
                            }
                            @Override
                            public boolean shouldPause() { return false; }
                        });
                    }
                });
                break;
            case "SWAPWS":
                trollSwapWS = !trollSwapWS;
                break;
            // ── Jumpscare instant effects ──
            case "ELDERGUARDIAN":
                // Trigger the elder guardian ghost animation + curse sound (client-side only)
                mc.execute(() -> {
                    if (mc.world != null && mc.player != null) {
                        mc.world.addParticleClient(
                                net.minecraft.particle.ParticleTypes.ELDER_GUARDIAN,
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                0, 0, 0);
                        mc.world.playSoundClient(
                                net.minecraft.sound.SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE,
                                net.minecraft.sound.SoundCategory.HOSTILE, 1.0f, 1.0f);
                    }
                });
                break;
            case "WARDENEMERGE":
                mc.execute(() -> {
                    if (mc.world != null && mc.player != null) {
                        mc.world.playSoundClient(
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                net.minecraft.sound.SoundEvents.ENTITY_WARDEN_EMERGE,
                                net.minecraft.sound.SoundCategory.HOSTILE, 1.0f, 1.0f, false);
                    }
                });
                break;
            case "CREEPERPRIMED":
                mc.execute(() -> {
                    if (mc.world != null && mc.player != null) {
                        mc.world.playSoundClient(
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                net.minecraft.sound.SoundEvents.ENTITY_CREEPER_PRIMED,
                                net.minecraft.sound.SoundCategory.HOSTILE, 1.0f, 1.0f, false);
                    }
                });
                break;
            case "TOTEMPOP":
                // Realistic totem of undying pop: item animation + sound + particles
                mc.execute(() -> {
                    if (mc.world != null && mc.player != null) {
                        // Show the big totem overlay animation (same as real totem use)
                        mc.gameRenderer.showFloatingItem(
                                new net.minecraft.item.ItemStack(net.minecraft.item.Items.TOTEM_OF_UNDYING));
                        // Play the totem sound
                        mc.world.playSoundClient(
                                net.minecraft.sound.SoundEvents.ITEM_TOTEM_USE,
                                net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
                        // Spawn totem particles (the green/yellow burst)
                        for (int i = 0; i < 30; i++) {
                            double vx = (Math.random() - 0.5) * 2.0;
                            double vy = Math.random() * 1.5;
                            double vz = (Math.random() - 0.5) * 2.0;
                            mc.world.addParticleClient(
                                    net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING,
                                    mc.player.getX(), mc.player.getY() + 1.0, mc.player.getZ(),
                                    vx, vy, vz);
                        }
                    }
                });
                break;
            case "RESET":
                trollFrozen = false;
                trollBunnyHop = false;
                trollPermaSneak = false;
                trollSpin = false;
                trollSlotCycle = false;
                trollWobble = false;
                trollNoPick = false;
                trollNausea = false;
                trollDvd = false;
                trollUpsideDown = false;
                trollDrunk = false;
                trollQuake = false;
                trollLookUp = false;
                trollLookDown = false;
                trollAutoAttack = false;
                trollFakeDeath = false;
                trollSwapWS = false;
                trollRemoteControl = false;
                rcW = rcA = rcS = rcD = rcJump = rcSneak = false;
                rcSprint = rcAttack = rcUse = rcCloseScreen = false;
                rcJumpPrev = false;
                if (savedInput != null && mc.player != null) { mc.player.input = savedInput; savedInput = null; }
                nauseaTick = 0;
                quakeTick = 0;
                if (trollZoom && mc.options != null) {
                    mc.options.getFov().setValue(savedFov);
                }
                trollZoom = false;
                zoomTick = 0;
                if (mc.player != null) {
                    mc.player.setSneaking(false);
                    if (mc.options != null) mc.options.attackKey.setPressed(false);
                }
                break;
            default:
                // Remote control commands (RC:W1A0S0D0J0N0Y30.5P-10.2)
                if (command.toUpperCase().startsWith("REMOTESTART")) {
                    trollRemoteControl = true;
                    rcLastUpdate = System.currentTimeMillis();
                    rcW = rcA = rcS = rcD = rcJump = rcSneak = false;
                    rcSprint = rcAttack = rcUse = rcCloseScreen = false;
                    rcJumpPrev = false;
                    // Force close any open screen immediately
                    mc.execute(() -> { if (mc.currentScreen != null) mc.setScreen(null); });
                    if (mc.player != null) {
                        rcYaw = mc.player.getYaw();
                        rcPitch = mc.player.getPitch();
                        // Swap to RCInput immediately
                        savedInput = mc.player.input;
                        mc.player.input = new RCInput();
                        // Release all key bindings so no stale state leaks
                        if (mc.options != null) {
                            mc.options.inventoryKey.setPressed(false);
                            mc.options.dropKey.setPressed(false);
                            mc.options.chatKey.setPressed(false);
                            mc.options.commandKey.setPressed(false);
                            mc.options.playerListKey.setPressed(false);
                            mc.options.pickItemKey.setPressed(false);
                            mc.options.swapHandsKey.setPressed(false);
                        }
                    }
                } else if (command.toUpperCase().startsWith("REMOTESTOP")) {
                    trollRemoteControl = false;
                    rcW = rcA = rcS = rcD = rcJump = rcSneak = false;
                    rcSprint = rcAttack = rcUse = rcCloseScreen = false;
                    rcJumpPrev = false;
                    // Restore original keyboard input
                    if (mc.player != null) {
                        if (savedInput != null) { mc.player.input = savedInput; savedInput = null; }
                        mc.player.setSneaking(false);
                        // Release all key bindings
                        if (mc.options != null) {
                            mc.options.forwardKey.setPressed(false);
                            mc.options.backKey.setPressed(false);
                            mc.options.leftKey.setPressed(false);
                            mc.options.rightKey.setPressed(false);
                            mc.options.jumpKey.setPressed(false);
                            mc.options.sneakKey.setPressed(false);
                            mc.options.sprintKey.setPressed(false);
                            mc.options.attackKey.setPressed(false);
                            mc.options.useKey.setPressed(false);
                        }
                    }
                } else if (command.startsWith("RC:")) {
                    String data = command.substring(3);
                    try {
                        rcW = data.charAt(1) == '1';
                        rcA = data.charAt(3) == '1';
                        rcS = data.charAt(5) == '1';
                        rcD = data.charAt(7) == '1';
                        rcJump = data.charAt(9) == '1';
                        rcSneak = data.charAt(11) == '1';
                        // Extended fields: R=sprint, L=attack, U=use, X=closeScreen
                        int rIdx = data.indexOf('R');
                        if (rIdx >= 0 && rIdx + 1 < data.length()) rcSprint = data.charAt(rIdx + 1) == '1';
                        int lIdx = data.indexOf('L');
                        if (lIdx >= 0 && lIdx + 1 < data.length()) rcAttack = data.charAt(lIdx + 1) == '1';
                        int uIdx = data.indexOf('U');
                        if (uIdx >= 0 && uIdx + 1 < data.length()) rcUse = data.charAt(uIdx + 1) == '1';
                        int xIdx = data.indexOf('X');
                        if (xIdx >= 0 && xIdx + 1 < data.length()) rcCloseScreen = data.charAt(xIdx + 1) == '1';
                        int yIdx = data.indexOf('Y');
                        int pIdx = data.indexOf('P', yIdx);
                        rcYaw = Float.parseFloat(data.substring(yIdx + 1, pIdx));
                        rcPitch = Float.parseFloat(data.substring(pIdx + 1));
                        rcLastUpdate = System.currentTimeMillis();
                    } catch (Exception e) {
                        LOGGER.debug("[CF] RC parse error: {}", e.getMessage());
                    }
                }
                break;
        }
    }

    // ── Confirm command handler ──────────────────────────────────────────────

    private static void processConfirmation() {
        if (pendingMessage != null) {
            String msg = pendingMessage;
            pendingMessage = null;
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                bypassFilter = true;
                if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatMessage(msg);
            });
            sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7aNachricht gesendet.");
        } else {
            sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cKeine ausstehende Nachricht.");
        }
    }

    // ── Chat handler (combined action pipeline) ──────────────────────────────

    private boolean handleChat(String message) {
        if (bypassFilter) {
            bypassFilter = false;
            return true;
        }

        // Handle legacy confirm via chat
        if (message.equalsIgnoreCase("!confirm") || message.equalsIgnoreCase("!cf-confirm")) {
            processConfirmation();
            return false;
        }

        // Check chat timeout
        if (AdminConfig.isChatBlocked()) {
            int secs = AdminConfig.getRemainingTimeoutSeconds();
            sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cChat gesperrt! Noch \u00a7f"
                    + secs + "\u00a7c Sekunden.");
            return false;
        }

        // Find matching rule (own rules + admin rules)
        FilterRule rule = findMatchingRule(message);
        if (rule == null) return true;

        // ── Phase 1: Apply timeout if set ────────────────────────────────
        if (rule.getTimeoutSeconds() > 0) {
            AdminConfig.applyChatTimeout(rule.getTimeoutSeconds());
        }

        // ── Phase 2: Transform message ───────────────────────────────────
        String processed = message;
        boolean transformed = false;

        if (rule.isCensorWord()) {
            processed = censorKeyword(processed, rule.getKeyword());
            transformed = true;
        }
        if (rule.isStripWord()) {
            processed = stripKeyword(processed, rule.getKeyword());
            transformed = true;
        }
        if (rule.isSendReplacement() && !rule.getReplacementText().isEmpty()) {
            processed = rule.getReplacementText();
            transformed = true;
        }

        // ── Phase 3: Warning ─────────────────────────────────────────────
        if (rule.isShowWarning() && !rule.getWarningText().isEmpty()) {
            sendLocal(rule.getWarningText());
        }

        // ── Phase 4: Disconnect ──────────────────────────────────────────
        if (rule.isDisconnectFromServer()) {
            sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a74Verbindung wird getrennt...");
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.world != null) mc.world.disconnect(Text.literal("Columba: Disconnect"));
            });
            return false;
        }

        // ── Phase 5: Block ───────────────────────────────────────────────
        if (rule.isBlockMessage()) {
            if (!rule.isShowWarning()) {
                sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cNachricht blockiert (\""
                        + rule.getKeyword() + "\").");
            }
            return false;
        }

        // ── Phase 6: Confirm ─────────────────────────────────────────────
        String finalMsg = processed;
        if (rule.isRequireConfirmation()) {
            pendingMessage = finalMsg;
            pendingTimestamp = System.currentTimeMillis();
            sendConfirmPrompt(rule.getKeyword(), finalMsg);
            return false;
        }

        // ── Phase 7: Auto-send transformed message ──────────────────────
        if (transformed) {
            if (!finalMsg.isBlank()) {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    bypassFilter = true;
                    if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatMessage(finalMsg);
                });
                sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7aModifizierte Nachricht gesendet.");
            } else {
                sendLocal("\u00a78[\u00a7bColumba\u00a78] \u00a7cNachricht war leer nach Verarbeitung.");
            }
            return false;
        }

        return true;
    }

    /** Searches both player rules and admin-enforced rules. */
    private static FilterRule findMatchingRule(String message) {
        // Own rules first
        FilterRule own = ChatFilterConfig.findMatchingRule(message);
        if (own != null) return own;
        // Admin rules
        String lower = message.toLowerCase(Locale.ROOT);
        for (FilterRule rule : AdminConfig.getAdminRulesForCurrentPlayer()) {
            if (rule.isEnabled() && !rule.getKeyword().isEmpty() && lower.contains(rule.getKeyword())) {
                return rule;
            }
        }
        return null;
    }

    /** Sends a confirm prompt with a clickable green button. */
    private static void sendConfirmPrompt(String keyword, String preview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        MutableText msg = Text.literal(
                "\u00a78[\u00a7bColumba\u00a78] \u00a7eEnth\u00e4lt \"" + keyword + "\"\n"
                        + "\u00a77Vorschau: \u00a7f" + preview + "\n");

        MutableText button = Text.literal(" [\u2714 Best\u00e4tigen] ").styled(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/cf-confirm"))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Klicke zum Best\u00e4tigen")))
                .withColor(Formatting.GREEN)
                .withBold(true));

        msg.append(button).append(Text.literal(" \u00a77(30s)"));
        mc.player.sendMessage(msg, false);
    }

    private static void sendLocal(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(text), false);
    }

    private static String censorKeyword(String message, String keyword) {
        String stars = "*".repeat(keyword.length());
        StringBuilder result = new StringBuilder(message);
        String lower = result.toString().toLowerCase(Locale.ROOT);
        int searchFrom = 0;
        int idx;
        while ((idx = lower.indexOf(keyword, searchFrom)) != -1) {
            result.replace(idx, idx + keyword.length(), stars);
            lower = result.toString().toLowerCase(Locale.ROOT);
            searchFrom = idx + stars.length();
        }
        return result.toString().strip();
    }

    private static String stripKeyword(String message, String keyword) {
        String lower = message.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        int pos = 0;
        int idx;
        while ((idx = lower.indexOf(keyword, pos)) != -1) {
            result.append(message, pos, idx);
            pos = idx + keyword.length();
        }
        result.append(message, pos, message.length());
        return result.toString().replaceAll("\\s{2,}", " ").strip();
    }
}
