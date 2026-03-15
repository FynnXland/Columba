package com.deinname.chatfilter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Dual-mode relay: Custom relay server (SSE+HTTP, primary) + ntfy.sh (fallback).
 * Custom mode: SSE stream to receive, HTTP POST to push — no rate limits on own server.
 * ntfy mode:   SSE stream to receive, HTTP POST to push — ntfy.sh free tier limits.
 */
public final class RelaySync {

    public enum RelayMode { NTFY, CUSTOM }

    private static final String DEFAULT_NTFY_BASE = "https://ntfy.sh/";
    private static final String TOPIC_PREFIX = "columba-";
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("chatfilter_relay.json");

    // ── Config (persisted) ───────────────────────────────────────────────────
    private static volatile RelayMode mode = RelayMode.NTFY;
    private static volatile String ntfyBase = DEFAULT_NTFY_BASE;
    private static volatile String customUrl = "";  // e.g. https://abc.ngrok-free.app
    private static volatile String customSecret = "";

    // ── Connection status ────────────────────────────────────────────────────
    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RATE_LIMITED, ERROR }
    private static volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private static volatile String connectionError = "";
    private static volatile long lastSuccessfulPush = 0;
    private static volatile long lastSuccessfulStream = 0;

    // ── Rate limiter (ntfy only): token bucket ──────────────────────────────
    private static final int RATE_BUCKET_MAX = 8;
    private static final long RATE_REFILL_MS = 1000;
    private static final AtomicInteger rateBucket = new AtomicInteger(RATE_BUCKET_MAX);
    private static final AtomicLong lastRefill = new AtomicLong(System.currentTimeMillis());

    // ── Circuit breaker (ntfy only) ─────────────────────────────────────────
    private static final int CB_THRESHOLD = 5;
    private static final long CB_WINDOW_MS = 60_000;
    private static final long CB_PAUSE_MS = 10_000;
    private static volatile int cbFailCount = 0;
    private static volatile long cbFirstFailTime = 0;
    private static volatile long cbPauseUntil = 0;

    // ── Status dedup ────────────────────────────────────────────────────────
    private static final ConcurrentHashMap<String, Long> lastStatusPush = new ConcurrentHashMap<>();
    private static final long STATUS_DEDUP_MS = 3_000;

    // ── Relay URL broadcast throttle (admin → non-admin, once per 5min per player) ─
    private static final ConcurrentHashMap<String, Long> lastRelayUrlPush = new ConcurrentHashMap<>();
    private static final long RELAY_URL_PUSH_INTERVAL_MS = 300_000; // 5 minutes

    // ── Debug log: last N relay events ───────────────────────────────────────
    private static final int DEBUG_LOG_MAX = 100;
    private static final java.util.Deque<String> debugLog = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static volatile boolean debugEnabled = true;
    private static volatile String lastLogEntry = "";
    private static volatile int lastLogRepeatCount = 0;

    public static boolean isDebugEnabled() { return debugEnabled; }
    public static void setDebugEnabled(boolean b) { debugEnabled = b; }
    public static java.util.List<String> getDebugLog() { return new java.util.ArrayList<>(debugLog); }
    public static void clearDebugLog() { debugLog.clear(); }

    private static void logDebug(String entry) {
        if (!debugEnabled) return;
        // Strip timestamp-like prefixes for dedup comparison
        String coreEntry = entry.replaceAll("\u00a7.", "").strip();
        String lastCore = lastLogEntry.replaceAll("\u00a7.", "").strip();
        if (coreEntry.equals(lastCore)) {
            lastLogRepeatCount++;
            // Update the last entry with repeat count instead of spamming
            if (!debugLog.isEmpty()) debugLog.pollLast();
            long ms = System.currentTimeMillis();
            long sec = (ms / 1000) % 86400;
            String time = String.format("%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
            debugLog.addLast(time + " " + entry + " \u00a78(x" + (lastLogRepeatCount + 1) + ")");
            return;
        }
        lastLogEntry = entry;
        lastLogRepeatCount = 0;
        long ms = System.currentTimeMillis();
        long sec = (ms / 1000) % 86400;
        String time = String.format("%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
        debugLog.addLast(time + " " + entry);
        while (debugLog.size() > DEBUG_LOG_MAX) debugLog.pollFirst();
    }

    // ── Priority push queue ─────────────────────────────────────────────────
    private static final PriorityBlockingQueue<PushTask> pushQueue = new PriorityBlockingQueue<>(64);
    private static volatile Thread pushWorkerThread;

    private static final class PushTask implements Comparable<PushTask> {
        final int priority;
        final String playerName;
        final String message;
        final boolean showFeedback;
        final long timestamp;
        PushTask(int priority, String playerName, String message, boolean showFeedback) {
            this.priority = priority;
            this.playerName = playerName;
            this.message = message;
            this.showFeedback = showFeedback;
            this.timestamp = System.nanoTime();
        }
        @Override public int compareTo(PushTask o) {
            int cmp = Integer.compare(this.priority, o.priority);
            return cmp != 0 ? cmp : Long.compare(this.timestamp, o.timestamp);
        }
    }

    // ── Backoff ──────────────────────────────────────────────────────────────
    private static final long BACKOFF_INITIAL_MS = 3000;
    private static final long BACKOFF_MAX_MS = 30_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    // ── HTTP client ─────────────────────────────────────────────────────────
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "CF-HTTP");
                t.setDaemon(true);
                return t;
            }))
            .build();

    private static volatile Thread streamThread;
    private static volatile Thread ntfyBridgeThread; // secondary ntfy listener when admin uses CUSTOM
    private static Consumer<String> messageHandler;

    private RelaySync() {}

    // ── Public API ──────────────────────────────────────────────────────────

    public static boolean isEnabled() { return true; }
    public static ConnectionState getConnectionState() { return connectionState; }
    public static String getConnectionError() { return connectionError; }
    public static long getLastSuccessfulPush() { return lastSuccessfulPush; }
    public static long getLastSuccessfulStream() { return lastSuccessfulStream; }

    public static RelayMode getMode() { return mode; }
    public static String getNtfyBase() { return ntfyBase; }
    public static String getCustomUrl() { return customUrl; }
    public static String getCustomSecret() { return customSecret; }

    public static String getRelayBase() {
        return mode == RelayMode.CUSTOM ? customUrl : ntfyBase;
    }

    public static void resetCircuitBreaker() {
        cbFailCount = 0; cbPauseUntil = 0; cbFirstFailTime = 0;
        rateBucket.set(RATE_BUCKET_MAX);
        connectionError = "";
        if (connectionState == ConnectionState.RATE_LIMITED || connectionState == ConnectionState.ERROR)
            connectionState = ConnectionState.DISCONNECTED;
    }

    public static void setMode(RelayMode m) { mode = m; save(); }
    public static void setNtfyBase(String url) {
        if (url == null || url.isBlank()) url = DEFAULT_NTFY_BASE;
        if (!url.endsWith("/")) url += "/";
        ntfyBase = url; resetCircuitBreaker(); save();
    }
    public static void setCustomUrl(String url) {
        if (url != null) url = url.strip();
        customUrl = url != null ? url : ""; save();
    }
    public static void setCustomSecret(String s) { customSecret = s != null ? s : ""; save(); }

    public static String getOwnTopic() {
        String name = AdminConfig.getCurrentUsername().toLowerCase();
        return name.isEmpty() ? "" : TOPIC_PREFIX + name;
    }

    // ── Rate limiter (ntfy only) ────────────────────────────────────────────

    private static boolean tryAcquireToken() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefill.get();
        if (elapsed >= RATE_REFILL_MS) {
            int add = (int)(elapsed / RATE_REFILL_MS);
            lastRefill.set(now);
            rateBucket.updateAndGet(v -> Math.min(RATE_BUCKET_MAX, v + add));
        }
        return rateBucket.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0;
    }

    private static boolean isCircuitOpen() {
        if (System.currentTimeMillis() < cbPauseUntil) { connectionState = ConnectionState.RATE_LIMITED; return true; }
        if (cbPauseUntil > 0 && System.currentTimeMillis() >= cbPauseUntil) { cbPauseUntil = 0; cbFailCount = 0; }
        return false;
    }

    private static void recordFailure() {
        long now = System.currentTimeMillis();
        if (cbFailCount == 0 || now - cbFirstFailTime > CB_WINDOW_MS) { cbFirstFailTime = now; cbFailCount = 1; }
        else cbFailCount++;
        if (cbFailCount >= CB_THRESHOLD) {
            cbPauseUntil = now + CB_PAUSE_MS;
            connectionState = ConnectionState.RATE_LIMITED;
        }
    }

    private static void recordSuccess() { cbFailCount = 0; cbPauseUntil = 0; lastSuccessfulPush = System.currentTimeMillis(); }

    // ── Push (queued) ───────────────────────────────────────────────────────

    public static void push(String playerName, String message) { push(playerName, message, false); }
    public static void push(String playerName, String message, boolean showFeedback) { enqueue(2, playerName, message, showFeedback); }

    private static void enqueue(int priority, String playerName, String message, boolean showFeedback) {
        // Drop stale RC/STATUS from queue to prevent flooding when relay is slow
        if (message.contains("T:RC:") || message.contains("ST:")) {
            pushQueue.removeIf(t -> t.playerName.equals(playerName.toLowerCase())
                    && ((message.contains("T:RC:") && t.message.contains("T:RC:"))
                        || (message.contains("ST:") && t.message.contains("ST:"))));
        }
        // Cap queue size: drop oldest low-priority items if too large
        while (pushQueue.size() > 50) {
            PushTask dropped = null;
            for (PushTask t : pushQueue) {
                if (t.priority >= 2) { dropped = t; break; }
            }
            if (dropped != null) pushQueue.remove(dropped);
            else break;
        }
        pushQueue.offer(new PushTask(priority, playerName.toLowerCase(), message, showFeedback));
        ensurePushWorker();
    }

    private static synchronized void ensurePushWorker() {
        if (pushWorkerThread != null && pushWorkerThread.isAlive()) return;
        pushWorkerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    PushTask task = pushQueue.poll(10, TimeUnit.SECONDS);
                    if (task == null) continue;
                    // Drop tasks older than 15 seconds (stale RC/status)
                    long ageMs = (System.nanoTime() - task.timestamp) / 1_000_000;
                    if (ageMs > 15_000 && task.priority >= 2) {
                        logDebug("\u00a78\u2716 DROPPED stale [" + msgLabel(task.message) + "] " + task.playerName);
                        continue;
                    }
                    if (mode == RelayMode.CUSTOM) {
                        executeCustomPush(task);
                        Thread.sleep(50); // small gap to avoid flooding
                    } else {
                        if (task.priority == 0) { executeNtfyPush(task); continue; }
                        if (isCircuitOpen()) continue;
                        int wait = 0;
                        while (!tryAcquireToken()) { Thread.sleep(300); if (++wait > 10) break; }
                        executeNtfyPush(task);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }, "CF-PushWorker");
        pushWorkerThread.setDaemon(true);
        pushWorkerThread.start();
    }

    // ── Custom relay push: POST /push/{player} ──────────────────────────────

    private static String customBase() {
        String base = customUrl;
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://" + base;
        if (!base.endsWith("/")) base += "/";
        return base;
    }

    private static void executeCustomPush(PushTask task) {
        String label = msgLabel(task.message);
        logDebug("\u00a7e\u2192 PUSH " + task.playerName + " [" + label + "] p" + task.priority);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(customBase() + "push/" + task.playerName))
                    .header("Content-Type", "text/plain")
                    .header("X-CF-Key", customSecret)
                    .header("ngrok-skip-browser-warning", "true")
                    .POST(HttpRequest.BodyPublishers.ofString(task.message, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() == 200) {
                            recordSuccess();
                            logDebug("\u00a7a\u2714 OK " + task.playerName + " [" + label + "]");
                            if (task.showFeedback)
                                inGameFeedback("\u00a78[\u00a7aRelay\u00a78] \u00a7a\u2714 \u00a77" + task.playerName);
                        } else {
                            connectionError = "HTTP " + resp.statusCode();
                            logDebug("\u00a7c\u2716 FAIL " + task.playerName + " HTTP " + resp.statusCode());
                            if (task.showFeedback)
                                inGameFeedback("\u00a78[\u00a7cRelay\u00a78] \u00a7c" + connectionError);
                        }
                    })
                    .exceptionally(e -> {
                        connectionError = e.getMessage() != null ? e.getMessage() : "Error";
                        logDebug("\u00a7c\u2716 ERR " + task.playerName + " " + connectionError);
                        if (task.showFeedback)
                            inGameFeedback("\u00a78[\u00a7cRelay\u00a78] \u00a7c" + connectionError);
                        return null;
                    });
        } catch (Exception e) {
            logDebug("\u00a7c\u2716 EXCEPTION " + task.playerName + " " + e.getMessage());
            if (task.showFeedback)
                inGameFeedback("\u00a78[\u00a7cRelay\u00a78] \u00a7cPush fehlgeschlagen!");
        }
    }

    // ── ntfy push ───────────────────────────────────────────────────────────

    private static void executeNtfyPush(PushTask task) {
        String topic = TOPIC_PREFIX + task.playerName;
        String label = msgLabel(task.message);
        logDebug("\u00a7e\u2192 NTFY " + task.playerName + " [" + label + "] p" + task.priority);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ntfyBase + topic))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(task.message, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() == 429) {
                            cbPauseUntil = System.currentTimeMillis() + CB_PAUSE_MS;
                            connectionState = ConnectionState.RATE_LIMITED;
                            connectionError = "429 Rate Limited";
                            logDebug("\u00a7c\u26a0 429 RATE LIMITED " + task.playerName);
                            if (task.showFeedback) inGameFeedback("\u00a78[\u00a7cRelay\u00a78] \u00a7cRate-limited");
                        } else if (resp.statusCode() != 200) {
                            recordFailure();
                            logDebug("\u00a7c\u2716 NTFY FAIL " + task.playerName + " HTTP " + resp.statusCode());
                            if (task.showFeedback) inGameFeedback("\u00a78[\u00a7cRelay\u00a78] \u00a7cHTTP " + resp.statusCode());
                        } else {
                            recordSuccess();
                            logDebug("\u00a7a\u2714 NTFY OK " + task.playerName + " [" + label + "]");
                            if (task.showFeedback) inGameFeedback("\u00a78[\u00a7aRelay\u00a78] \u00a7a\u2714 \u00a77" + task.playerName);
                        }
                    })
                    .exceptionally(e -> { recordFailure(); logDebug("\u00a7c\u2716 NTFY ERR " + task.playerName); return null; });
        } catch (Exception e) { recordFailure(); logDebug("\u00a7c\u2716 NTFY EXC " + task.playerName); }
    }

    private static void inGameFeedback(String msg) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        mc.execute(() -> { if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal(msg), false); });
    }

    /** Extract a short label from a CF protocol message for debug display. */
    private static String msgLabel(String msg) {
        if (msg == null) return "?";
        if (msg.contains("T:FAKECHAT")) return "FAKECHAT";
        if (msg.contains("T:RC:")) return "RC";
        if (msg.contains("T:RATE:")) return "RATE";
        if (msg.contains("T:")) {
            int i = msg.indexOf("T:") + 2;
            int end = msg.indexOf('#', i);
            if (end < 0) end = msg.indexOf(']', i);
            return end > i ? msg.substring(i, Math.min(end, i + 15)) : "TROLL";
        }
        if (msg.contains("PING:")) return "PING";
        if (msg.contains("PONG:")) return "PONG";
        if (msg.contains("RU:")) return "RELAY_URL";
        if (msg.contains("ST:")) return "STATUS";
        if (msg.contains("CF:") && msg.contains("/")) return "SYNC";
        return msg.length() > 15 ? msg.substring(0, 15) : msg;
    }

    // ── High-level push methods ─────────────────────────────────────────────

    public static void pushPlayerData(String playerName) {
        String encoded = AdminConfig.encodePlayerData(playerName);
        if (encoded == null) return;
        String sid = Integer.toHexString((int)(System.currentTimeMillis() & 0xFFFF));
        int maxChunk = 3500;
        int total = (encoded.length() + maxChunk - 1) / maxChunk;
        for (int i = 0; i < total; i++) {
            int s = i * maxChunk, e2 = Math.min(s + maxChunk, encoded.length());
            enqueue(1, playerName, AdminConfig.CF_MARKER + sid + ":" + (i+1) + "/" + total + "]" + encoded.substring(s, e2), false);
        }
    }

    public static void pushTroll(String playerName, String command) {
        enqueue(0, playerName, AdminConfig.CF_MARKER + "T:" + command + "]", true);
    }

    public static void pushPing(String playerName, String sender) {
        enqueue(1, playerName, AdminConfig.CF_MARKER + "PING:" + sender + "]", false);
    }

    public static void pushPong(String target, String sender) {
        String serverAddr = "";
        try {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.getCurrentServerEntry() != null) serverAddr = mc.getCurrentServerEntry().address;
        } catch (Exception ignored) {}
        String payload = serverAddr.isEmpty() ? sender : sender + "|" + serverAddr;
        enqueue(1, target, AdminConfig.CF_MARKER + "PONG:" + payload + "]", false);
    }

    public static void pushStatus(String target, String statusJson) {
        String key = target.toLowerCase();
        long now = System.currentTimeMillis();
        Long last = lastStatusPush.get(key);
        if (last != null && now - last < STATUS_DEDUP_MS) return;
        lastStatusPush.put(key, now);
        enqueue(2, target, AdminConfig.CF_MARKER + "ST:" + statusJson + "]", false);
    }

    /** Admin: broadcast relay URL to a player via ntfy.sh (so non-admin auto-discovers custom relay). */
    public static void pushRelayUrlViaNtfy(String playerName) {
        if (mode != RelayMode.CUSTOM || customUrl.isEmpty()) return;
        String key = playerName.toLowerCase();
        long now = System.currentTimeMillis();
        Long last = lastRelayUrlPush.get(key);
        if (last != null && now - last < RELAY_URL_PUSH_INTERVAL_MS) return;
        lastRelayUrlPush.put(key, now);
        String payload = AdminConfig.CF_MARKER + "RU:" + customUrl + "|" + customSecret + "]";
        // Always push via ntfy.sh regardless of current mode
        String topic = TOPIC_PREFIX + key;
        logDebug("\u00a7d\u2192 RELAY_URL \u2192 " + playerName + " via ntfy");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ntfyBase + topic))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() == 200) logDebug("\u00a7a\u2714 RELAY_URL OK " + playerName);
                        else logDebug("\u00a7c\u2716 RELAY_URL FAIL " + playerName + " HTTP " + resp.statusCode());
                    })
                    .exceptionally(e -> { logDebug("\u00a7c\u2716 RELAY_URL ERR " + playerName); return null; });
        } catch (Exception ignored) {}
    }

    // ── Receive: SSE streaming (works for both modes) ───────────────────────

    private static final Object LOCK = new Object();

    public static void startPolling(Consumer<String> handler) {
        synchronized (LOCK) {
            messageHandler = handler;
            stopPolling();
            if (handler == null) return;

            streamThread = new Thread(() -> {
                long backoff = BACKOFF_INITIAL_MS;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        connectionState = ConnectionState.CONNECTING;
                        if (mode == RelayMode.CUSTOM) doCustomStream();
                        else doNtfyStream();
                        backoff = BACKOFF_INITIAL_MS;
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) break;
                        connectionState = ConnectionState.DISCONNECTED;
                        ChatFilterMod.LOGGER.debug("[Relay] Stream disconnected, retry in {}ms: {}", backoff, e.getMessage());
                    }
                    try {
                        Thread.sleep(backoff);
                        backoff = Math.min((long)(backoff * BACKOFF_MULTIPLIER), BACKOFF_MAX_MS);
                    } catch (InterruptedException e) { break; }
                }
                connectionState = ConnectionState.DISCONNECTED;
            }, "CF-Stream");
            streamThread.setDaemon(true);
            streamThread.start();

            // Admin in CUSTOM mode: also listen on ntfy.sh to receive from non-configured clients
            if (AdminConfig.isAdmin() && mode == RelayMode.CUSTOM) {
                startNtfyBridge(handler);
            }
        }
    }

    /** Start secondary ntfy.sh listener for admin — receives from clients still using ntfy. */
    private static void startNtfyBridge(Consumer<String> handler) {
        stopNtfyBridge();
        ntfyBridgeThread = new Thread(() -> {
            long backoff = BACKOFF_INITIAL_MS;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    doNtfyStream();
                    backoff = BACKOFF_INITIAL_MS;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    ChatFilterMod.LOGGER.debug("[Relay] ntfy bridge disconnected, retry in {}ms", backoff);
                }
                try {
                    Thread.sleep(backoff);
                    backoff = Math.min((long)(backoff * BACKOFF_MULTIPLIER), BACKOFF_MAX_MS);
                } catch (InterruptedException e) { break; }
            }
        }, "CF-NtfyBridge");
        ntfyBridgeThread.setDaemon(true);
        ntfyBridgeThread.start();
        logDebug("\u00a7d\u25cf ntfy Bridge gestartet (Admin dual-stream)");
    }

    private static void stopNtfyBridge() {
        if (ntfyBridgeThread != null) { ntfyBridgeThread.interrupt(); ntfyBridgeThread = null; }
    }

    public static void stopPolling() {
        synchronized (LOCK) {
            if (streamThread != null) { streamThread.interrupt(); streamThread = null; }
            stopNtfyBridge();
            connectionState = ConnectionState.DISCONNECTED;
        }
    }

    // ── Custom relay SSE: GET /stream/{player}?key=secret ───────────────────

    private static void doCustomStream() throws Exception {
        String player = AdminConfig.getCurrentUsername().toLowerCase();
        if (player.isEmpty()) { Thread.sleep(5000); return; }

        String streamUrl = customBase() + "stream/" + player + "?key=" + customSecret;
        ChatFilterMod.LOGGER.info("[Relay] Custom SSE connecting: {}", streamUrl.replaceAll("key=[^&]+", "key=***"));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(streamUrl))
                .header("ngrok-skip-browser-warning", "true")
                .header("User-Agent", "Columba/4.0")
                .GET()
                .timeout(Duration.ofMinutes(30))
                .build();

        HttpResponse<java.io.InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 403) {
            connectionState = ConnectionState.ERROR;
            connectionError = "403 Falscher Key";
            Thread.sleep(10_000); return;
        }
        if (resp.statusCode() != 200) {
            connectionState = ConnectionState.ERROR;
            connectionError = "HTTP " + resp.statusCode();
            Thread.sleep(5000); return;
        }

        connectionState = ConnectionState.CONNECTED;
        connectionError = "";
        lastSuccessfulStream = System.currentTimeMillis();
        logDebug("\u00a7a\u25cf SSE CONNECTED (" + RelayMode.CUSTOM + ")");
        ChatFilterMod.LOGGER.info("[Relay] Custom SSE connected as '{}'", player);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) break;
                lastSuccessfulStream = System.currentTimeMillis();
                line = line.strip();
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (line.startsWith("data: ")) {
                    String json = line.substring(6).strip();
                    try {
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        if (obj.has("message")) {
                            String msg = obj.get("message").getAsString();
                            if (messageHandler != null && !msg.isBlank()) {
                                logDebug("\u00a7b\u2190 RECV [" + msgLabel(msg) + "]");
                                messageHandler.accept(msg);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        connectionState = ConnectionState.DISCONNECTED;
    }

    // ── ntfy SSE stream ─────────────────────────────────────────────────────

    private static void doNtfyStream() throws Exception {
        String player = AdminConfig.getCurrentUsername().toLowerCase();
        if (player.isEmpty()) { Thread.sleep(5000); return; }
        String topic = TOPIC_PREFIX + player;
        long since = System.currentTimeMillis() / 1000;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ntfyBase + topic + "/json?since=" + since))
                .GET().timeout(Duration.ofMinutes(30)).build();

        HttpResponse<java.io.InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 429) {
            connectionState = ConnectionState.RATE_LIMITED; connectionError = "429";
            Thread.sleep(30_000 + (long)(Math.random() * 10_000)); return;
        }
        if (resp.statusCode() != 200) {
            connectionState = ConnectionState.ERROR; connectionError = "HTTP " + resp.statusCode();
            Thread.sleep(5000); return;
        }

        connectionState = ConnectionState.CONNECTED; connectionError = "";
        lastSuccessfulStream = System.currentTimeMillis();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) break;
                lastSuccessfulStream = System.currentTimeMillis();
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                    if (!obj.has("message")) continue;
                    String msg = obj.get("message").getAsString();
                    if (messageHandler != null && !msg.isBlank()) {
                        logDebug("\u00a7b\u2190 NTFY RECV [" + msgLabel(msg) + "]");
                        messageHandler.accept(msg);
                    }
                } catch (Exception ignored) {}
            }
        }
        connectionState = ConnectionState.DISCONNECTED;
    }

    /** Test connection: custom mode calls /test?key=..., ntfy posts to test topic. */
    public static int testConnection() {
        try {
            HttpRequest req;
            if (mode == RelayMode.CUSTOM) {
                req = HttpRequest.newBuilder()
                        .uri(URI.create(customBase() + "test?key=" + customSecret))
                        .header("ngrok-skip-browser-warning", "true")
                        .header("User-Agent", "Columba/4.0")
                        .GET().timeout(Duration.ofSeconds(5)).build();
            } else {
                req = HttpRequest.newBuilder()
                        .uri(URI.create(ntfyBase + TOPIC_PREFIX + "test"))
                        .POST(HttpRequest.BodyPublishers.ofString("ping"))
                        .timeout(Duration.ofSeconds(5)).build();
            }
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            connectionError = e.getMessage() != null ? e.getMessage() : "Error";
            return -1;
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public static void load() {
        if (!Files.exists(CONFIG_FILE)) return;
        try {
            String json = Files.readString(CONFIG_FILE);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("mode")) {
                String m = obj.get("mode").getAsString();
                if ("WEBSOCKET".equals(m)) mode = RelayMode.CUSTOM; // migrate
                else try { mode = RelayMode.valueOf(m); } catch (Exception ignored) {}
            }
            if (obj.has("ntfyBase")) {
                String url = obj.get("ntfyBase").getAsString();
                if (!url.isBlank()) { if (!url.endsWith("/")) url += "/"; ntfyBase = url; }
            }
            if (obj.has("customUrl")) customUrl = obj.get("customUrl").getAsString();
            if (obj.has("wsUrl")) customUrl = obj.get("wsUrl").getAsString();
            if (obj.has("customSecret")) customSecret = obj.get("customSecret").getAsString();
            if (obj.has("wsSecret")) customSecret = obj.get("wsSecret").getAsString();
        } catch (Exception e) {
            ChatFilterMod.LOGGER.debug("[Relay] Config load error: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("mode", mode.name());
            obj.addProperty("ntfyBase", ntfyBase);
            obj.addProperty("customUrl", customUrl);
            obj.addProperty("customSecret", customSecret);
            Files.writeString(CONFIG_FILE, obj.toString());
        } catch (Exception e) {
            ChatFilterMod.LOGGER.debug("[Relay] Config save error: {}", e.getMessage());
        }
    }
}
