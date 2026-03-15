package com.deinname.chatfilter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Embedded HTTP relay server with SSE streaming — runs inside the admin's Minecraft client.
 * Replaces the external relay-server.js. Only started when the admin is detected.
 *
 * Endpoints:
 *   GET  /stream/{player}?key=SECRET   → SSE stream (real-time messages)
 *   POST /push/{player}                → deliver message (X-CF-Key header)
 *   GET  /poll/{player}                → poll queued messages (X-CF-Key)
 *   GET  /test?key=SECRET              → health check
 *   GET  /status                       → online players (X-CF-Key)
 */
public final class EmbeddedRelay {

    private static final int DEFAULT_PORT = 3579;
    private static volatile HttpServer server;
    private static volatile boolean running = false;
    private static volatile int activePort = DEFAULT_PORT;
    private static String secret = "changeme";

    // SSE listeners: playerName → Set<HttpExchange>
    private static final ConcurrentHashMap<String, Set<HttpExchange>> listeners = new ConcurrentHashMap<>();
    // Offline message queue: playerName → [{msg, ts}]
    private static final ConcurrentHashMap<String, Queue<QueuedMsg>> queue = new ConcurrentHashMap<>();
    private static final long TTL_MS = 120_000;
    private static final int MAX_QUEUE = 200;

    private static ScheduledExecutorService scheduler;

    private record QueuedMsg(String msg, long ts) {}

    private EmbeddedRelay() {}

    public static boolean isRunning() { return running; }
    public static int getPort() { return activePort; }
    public static String getSecret() { return secret; }

    public static void setSecret(String s) { secret = s != null && !s.isBlank() ? s : "changeme"; }

    /** Start the embedded relay server on the given port. */
    public static synchronized void start(int port, String key) {
        if (running) return;
        secret = key != null && !key.isBlank() ? key : "changeme";
        activePort = port;

        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "CF-Relay");
                t.setDaemon(true);
                return t;
            }));

            server.createContext("/stream", EmbeddedRelay::handleStream);
            server.createContext("/push", EmbeddedRelay::handlePush);
            server.createContext("/poll", EmbeddedRelay::handlePoll);
            server.createContext("/test", EmbeddedRelay::handleTest);
            server.createContext("/status", EmbeddedRelay::handleStatus);

            server.start();
            running = true;

            // Keepalive + cleanup scheduler
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CF-Relay-Sched");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(EmbeddedRelay::keepalive, 20, 20, TimeUnit.SECONDS);
            scheduler.scheduleAtFixedRate(EmbeddedRelay::cleanExpired, 15, 15, TimeUnit.SECONDS);

            ChatFilterMod.LOGGER.info("[EmbeddedRelay] Started on port {} (key={})", port, secret);
        } catch (Exception e) {
            ChatFilterMod.LOGGER.error("[EmbeddedRelay] Failed to start: {}", e.getMessage());
        }
    }

    /** Stop the embedded relay server. */
    public static synchronized void stop() {
        if (!running) return;
        running = false;
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        // Close all SSE connections
        for (Set<HttpExchange> set : listeners.values()) {
            for (HttpExchange ex : set) {
                try { ex.getResponseBody().close(); } catch (Exception ignored) {}
            }
        }
        listeners.clear();
        if (server != null) { server.stop(0); server = null; }
        ChatFilterMod.LOGGER.info("[EmbeddedRelay] Stopped");
    }

    // ── Deliver message to player ────────────────────────────────────────────

    private static void deliver(String player, String message) {
        String key = player.toLowerCase();
        Set<HttpExchange> sseSet = listeners.get(key);
        if (sseSet != null && !sseSet.isEmpty()) {
            String data = "data: {\"message\":" + escapeJson(message) + "}\n\n";
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            Iterator<HttpExchange> it = sseSet.iterator();
            while (it.hasNext()) {
                HttpExchange ex = it.next();
                try {
                    ex.getResponseBody().write(bytes);
                    ex.getResponseBody().flush();
                } catch (Exception e) {
                    it.remove();
                }
            }
            ChatFilterMod.LOGGER.debug("[EmbeddedRelay] SSE → {} ({} conn)", key, sseSet.size());
        } else {
            // Queue for poll fallback
            queue.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>())
                    .offer(new QueuedMsg(message, System.currentTimeMillis()));
            Queue<QueuedMsg> q = queue.get(key);
            while (q != null && q.size() > MAX_QUEUE) q.poll();
        }
    }

    private static String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // ── SSE stream: GET /stream/{player}?key=SECRET ──────────────────────────

    private static void handleStream(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { respond(ex, 405, "Method Not Allowed"); return; }

        String keyParam = getQueryParam(ex, "key");
        if (!secret.equals(keyParam)) { respond(ex, 403, "Forbidden"); return; }

        String player = extractPlayer(ex.getRequestURI().getPath(), "/stream/");
        if (player.isEmpty()) { respond(ex, 400, "Bad Request"); return; }

        // SSE headers
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0); // chunked

        OutputStream out = ex.getResponseBody();
        out.write(":connected\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Register
        listeners.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(ex);
        ChatFilterMod.LOGGER.info("[EmbeddedRelay] SSE + {} ({})", player, listeners.get(player).size());

        // Deliver queued messages
        Queue<QueuedMsg> q = queue.remove(player);
        if (q != null) {
            for (QueuedMsg m : q) {
                String data = "data: {\"message\":" + escapeJson(m.msg) + "}\n\n";
                try { out.write(data.getBytes(StandardCharsets.UTF_8)); out.flush(); }
                catch (Exception e) { break; }
            }
        }

        // Keep connection open — the HttpServer handles this via the thread pool.
        // We block this thread until the client disconnects.
        try {
            while (running) {
                Thread.sleep(5000);
                // Check if still connected by writing a comment
                try { out.write(":ping\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); }
                catch (Exception e) { break; }
            }
        } catch (InterruptedException ignored) {}

        // Cleanup
        Set<HttpExchange> set = listeners.get(player);
        if (set != null) {
            set.remove(ex);
            if (set.isEmpty()) listeners.remove(player);
        }
        ChatFilterMod.LOGGER.debug("[EmbeddedRelay] SSE - {}", player);
        try { out.close(); } catch (Exception ignored) {}
    }

    // ── Push: POST /push/{player} ────────────────────────────────────────────

    private static void handlePush(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { respond(ex, 405, "Method Not Allowed"); return; }
        if (!checkKey(ex)) { respond(ex, 403, "Forbidden"); return; }

        String player = extractPlayer(ex.getRequestURI().getPath(), "/push/");
        if (player.isEmpty()) { respond(ex, 400, "Bad Request"); return; }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        deliver(player, body);
        respond(ex, 200, "OK");
    }

    // ── Poll: GET /poll/{player} ─────────────────────────────────────────────

    private static void handlePoll(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { respond(ex, 405, "Method Not Allowed"); return; }
        if (!checkKey(ex)) { respond(ex, 403, "Forbidden"); return; }

        String player = extractPlayer(ex.getRequestURI().getPath(), "/poll/");
        if (player.isEmpty()) { respond(ex, 400, "Bad Request"); return; }

        Queue<QueuedMsg> q = queue.remove(player);
        if (q == null || q.isEmpty()) { respond(ex, 200, ""); return; }

        StringBuilder sb = new StringBuilder();
        for (QueuedMsg m : q) { if (!sb.isEmpty()) sb.append('\n'); sb.append(m.msg); }
        respond(ex, 200, sb.toString());
    }

    // ── Test: GET /test?key=SECRET ───────────────────────────────────────────

    private static void handleTest(HttpExchange ex) throws IOException {
        String keyParam = getQueryParam(ex, "key");
        if (!secret.equals(keyParam) && !checkKey(ex)) { respond(ex, 403, "Forbidden"); return; }

        Set<String> online = new HashSet<>();
        for (Map.Entry<String, Set<HttpExchange>> e : listeners.entrySet()) {
            if (!e.getValue().isEmpty()) online.add(e.getKey());
        }
        String json = "{\"ok\":true,\"online\":" + online + "}";
        ex.getResponseHeaders().set("Content-Type", "application/json");
        respond(ex, 200, json);
    }

    // ── Status: GET /status ──────────────────────────────────────────────────

    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!checkKey(ex)) { respond(ex, 403, "Forbidden"); return; }
        Set<String> online = new HashSet<>();
        for (Map.Entry<String, Set<HttpExchange>> e : listeners.entrySet()) {
            if (!e.getValue().isEmpty()) online.add(e.getKey());
        }
        String json = "{\"online\":" + online + ",\"queued\":" + queue.keySet() + "}";
        ex.getResponseHeaders().set("Content-Type", "application/json");
        respond(ex, 200, json);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean checkKey(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("X-CF-Key");
        if (secret.equals(h)) return true;
        // Also check query param
        return secret.equals(getQueryParam(ex, "key"));
    }

    private static String getQueryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return "";
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return "";
    }

    private static String extractPlayer(String path, String prefix) {
        if (!path.startsWith(prefix)) return "";
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String player = slash >= 0 ? rest.substring(0, slash) : rest;
        return java.net.URLDecoder.decode(player, StandardCharsets.UTF_8).toLowerCase();
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private static void keepalive() {
        byte[] ping = ":keepalive\n\n".getBytes(StandardCharsets.UTF_8);
        for (Set<HttpExchange> set : listeners.values()) {
            Iterator<HttpExchange> it = set.iterator();
            while (it.hasNext()) {
                try {
                    HttpExchange ex = it.next();
                    ex.getResponseBody().write(ping);
                    ex.getResponseBody().flush();
                } catch (Exception e) { it.remove(); }
            }
        }
    }

    private static void cleanExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Queue<QueuedMsg>> e : queue.entrySet()) {
            e.getValue().removeIf(m -> now - m.ts > TTL_MS);
            if (e.getValue().isEmpty()) queue.remove(e.getKey());
        }
    }
}
