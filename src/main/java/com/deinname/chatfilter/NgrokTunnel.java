package com.deinname.chatfilter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Manages an ngrok tunnel to expose the embedded relay server.
 * Starts ngrok as a subprocess and reads the public URL from ngrok's local API.
 */
public final class NgrokTunnel {

    private static volatile Process ngrokProcess;
    private static volatile String publicUrl = "";
    private static volatile boolean running = false;
    private static volatile String lastError = "";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private NgrokTunnel() {}

    public static boolean isRunning() { return running; }
    public static String getPublicUrl() { return publicUrl; }
    public static String getLastError() { return lastError; }

    /**
     * Start ngrok tunnel for the given port.
     * Runs in background thread — call getPublicUrl() after a few seconds.
     */
    public static synchronized void start(int port) {
        if (running) return;
        lastError = "";
        publicUrl = "";

        new Thread(() -> {
            try {
                // Check if ngrok is already running by querying its API
                String existing = queryNgrokApi();
                if (existing != null && !existing.isEmpty()) {
                    publicUrl = existing;
                    running = true;
                    ChatFilterMod.LOGGER.info("[NgrokTunnel] Found existing tunnel: {}", publicUrl);
                    return;
                }

                // Start ngrok process
                ProcessBuilder pb = new ProcessBuilder("ngrok", "http", String.valueOf(port), "--log", "stderr");
                pb.redirectErrorStream(true);
                ngrokProcess = pb.start();
                running = true;
                ChatFilterMod.LOGGER.info("[NgrokTunnel] Starting ngrok for port {}", port);

                // Wait for ngrok to initialize, then query its API
                for (int attempt = 0; attempt < 15; attempt++) {
                    Thread.sleep(2000);
                    String url = queryNgrokApi();
                    if (url != null && !url.isEmpty()) {
                        publicUrl = url;
                        ChatFilterMod.LOGGER.info("[NgrokTunnel] Public URL: {}", publicUrl);
                        return;
                    }
                }

                lastError = "ngrok gestartet, aber keine URL gefunden";
                ChatFilterMod.LOGGER.warn("[NgrokTunnel] {}", lastError);
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "ngrok Fehler";
                running = false;
                ChatFilterMod.LOGGER.warn("[NgrokTunnel] Failed to start: {}", lastError);
            }
        }, "CF-Ngrok-Start").start();
    }

    /** Stop the ngrok tunnel. */
    public static synchronized void stop() {
        running = false;
        publicUrl = "";
        if (ngrokProcess != null) {
            ngrokProcess.destroyForcibly();
            ngrokProcess = null;
            ChatFilterMod.LOGGER.info("[NgrokTunnel] Stopped");
        }
    }

    /**
     * Query ngrok's local API to get the public HTTPS tunnel URL.
     * ngrok runs a local web interface on port 4040 by default.
     */
    private static String queryNgrokApi() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:4040/api/tunnels"))
                    .GET().timeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            var tunnels = root.getAsJsonArray("tunnels");
            if (tunnels == null || tunnels.isEmpty()) return null;

            // Prefer HTTPS tunnel
            for (var tunnel : tunnels) {
                JsonObject t = tunnel.getAsJsonObject();
                String proto = t.has("proto") ? t.get("proto").getAsString() : "";
                String url = t.has("public_url") ? t.get("public_url").getAsString() : "";
                if ("https".equals(proto) && !url.isEmpty()) return url;
            }
            // Fallback to first tunnel
            JsonObject first = tunnels.get(0).getAsJsonObject();
            return first.has("public_url") ? first.get("public_url").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if ngrok is installed and accessible. */
    public static boolean isNgrokInstalled() {
        try {
            Process p = new ProcessBuilder("ngrok", "version").start();
            boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
