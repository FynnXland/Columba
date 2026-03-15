package com.deinname.chatfilter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.InflaterInputStream;

/**
 * Fetches player UUIDs and 8×8 face pixel data from Mojang/mc-heads APIs.
 * Uses a minimal PNG decoder (no java.awt, no NativeImage) to avoid
 * headless-AWT and private-access issues in Minecraft's environment.
 * Pixels are rendered via ctx.fill() – no texture registration needed.
 */
public final class PlayerHeadCache {

    /** Cached 8×8 head pixel arrays (64 ARGB ints). */
    private static final Map<String, int[]> headPixels = new ConcurrentHashMap<>();
    /** UUIDs (formatted with dashes). */
    private static final Map<String, String> uuids = new ConcurrentHashMap<>();
    /** In-progress requests. */
    private static final Set<String> fetching = ConcurrentHashMap.newKeySet();
    /** Players that failed to load (don't retry). */
    private static final Set<String> failed = ConcurrentHashMap.newKeySet();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "CF-Head");
                t.setDaemon(true);
                return t;
            });

    private PlayerHeadCache() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns 8×8 ARGB pixel array for the player's face, or null if not loaded. */
    public static int[] getHeadPixels(String playerName) {
        return headPixels.get(playerName.toLowerCase());
    }

    /** Returns cached UUID, or null if not yet known. */
    public static String getUuid(String playerName) {
        return uuids.get(playerName.toLowerCase());
    }

    /** True if a fetch is currently in progress. */
    public static boolean isLoading(String playerName) {
        return fetching.contains(playerName.toLowerCase());
    }

    /**
     * Start async fetch for this player if not already done.
     * @param onReady callback invoked once data is ready (may be null).
     */
    public static void fetchAsync(String playerName, Runnable onReady) {
        String key = playerName.toLowerCase();
        if (headPixels.containsKey(key) || failed.contains(key) || !fetching.add(key)) return;

        POOL.submit(() -> {
            try {
                // 1. Resolve UUID from Mojang
                String uuid = fetchUuid(playerName);
                if (uuid == null) {
                    failed.add(key);
                    fetching.remove(key);
                    return;
                }
                uuids.put(key, uuid);

                // 2. Download face image as raw PNG bytes (try mc-heads, fallback to crafatar)
                int[] pixels = null;
                String[] urls = {
                    "https://mc-heads.net/avatar/" + uuid + "/8",
                    "https://crafatar.com/avatars/" + uuid + "?size=8&overlay"
                };
                for (String url : urls) {
                    try {
                        HttpRequest imgReq = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("User-Agent", "Columba/4.0")
                                .GET().timeout(Duration.ofSeconds(8)).build();
                        HttpResponse<byte[]> imgResp =
                                HTTP.send(imgReq, HttpResponse.BodyHandlers.ofByteArray());
                        if (imgResp.statusCode() == 200 && imgResp.body().length > 50) {
                            pixels = decodePng8x8(imgResp.body());
                            if (pixels != null) break;
                        }
                    } catch (Exception ignored) {}
                }

                if (pixels == null) {
                    ChatFilterMod.LOGGER.warn("[CF] Head PNG decode failed for {}", playerName);
                    failed.add(key);
                    fetching.remove(key);
                    return;
                }

                headPixels.put(key, pixels);
                ChatFilterMod.LOGGER.info("[CF] Head loaded: {} (uuid: {})", playerName, uuid.substring(0, 8));
                if (onReady != null) onReady.run();
            } catch (Exception e) {
                ChatFilterMod.LOGGER.warn("[CF] Head fetch failed for {}: {}", playerName, e.getMessage());
                failed.add(key);
            } finally {
                fetching.remove(key);
            }
        });
    }

    /** Prefetch for all known players. */
    public static void prefetchAll(Iterable<String> playerNames, Runnable onAnyReady) {
        for (String name : playerNames) fetchAsync(name, onAnyReady);
    }

    // ── Minimal PNG decoder (8×8 RGB/RGBA only) ──────────────────────────────

    /**
     * Decode a small PNG file into an 8×8 ARGB pixel array.
     * Supports color types 2 (RGB) and 6 (RGBA), bit depth 8.
     * This avoids any java.awt or MC NativeImage dependency.
     */
    private static int[] decodePng8x8(byte[] pngData) {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(pngData));
            // Skip PNG signature (8 bytes)
            dis.skipBytes(8);

            int w = 0, h = 0, colorType = 0;
            byte[] idatData = null;

            // Read chunks
            while (dis.available() > 0) {
                int chunkLen = dis.readInt();
                byte[] typeBytes = new byte[4];
                dis.readFully(typeBytes);
                String type = new String(typeBytes);

                if ("IHDR".equals(type)) {
                    w = dis.readInt();
                    h = dis.readInt();
                    int bitDepth = dis.readUnsignedByte();
                    colorType = dis.readUnsignedByte();
                    dis.skipBytes(chunkLen - 10); // rest of IHDR
                } else if ("IDAT".equals(type)) {
                    byte[] chunk = new byte[chunkLen];
                    dis.readFully(chunk);
                    if (idatData == null) {
                        idatData = chunk;
                    } else {
                        // Concatenate IDAT chunks
                        byte[] combined = new byte[idatData.length + chunk.length];
                        System.arraycopy(idatData, 0, combined, 0, idatData.length);
                        System.arraycopy(chunk, 0, combined, idatData.length, chunk.length);
                        idatData = combined;
                    }
                } else {
                    dis.skipBytes(chunkLen);
                }
                dis.readInt(); // CRC
            }

            if (idatData == null || w == 0 || h == 0) return null;

            // Decompress IDAT
            InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(idatData));
            int bpp = (colorType == 6) ? 4 : 3; // RGBA or RGB
            int rowBytes = 1 + w * bpp; // +1 for filter byte
            byte[] raw = inflater.readAllBytes();
            inflater.close();

            if (raw.length < h * rowBytes) return null;

            // Unfilter + extract pixels (sub-sampled to 8×8)
            byte[][] rows = new byte[h][w * bpp];
            for (int y = 0; y < h; y++) {
                int offset = y * rowBytes;
                int filter = raw[offset] & 0xFF;
                byte[] row = new byte[w * bpp];
                System.arraycopy(raw, offset + 1, row, 0, w * bpp);

                // Apply PNG row filter
                if (filter == 1) { // Sub
                    for (int i = bpp; i < row.length; i++)
                        row[i] = (byte)(row[i] + row[i - bpp]);
                } else if (filter == 2 && y > 0) { // Up
                    for (int i = 0; i < row.length; i++)
                        row[i] = (byte)(row[i] + rows[y - 1][i]);
                } else if (filter == 3) { // Average
                    for (int i = 0; i < row.length; i++) {
                        int a = (i >= bpp) ? (row[i - bpp] & 0xFF) : 0;
                        int b = (y > 0) ? (rows[y - 1][i] & 0xFF) : 0;
                        row[i] = (byte)(row[i] + (a + b) / 2);
                    }
                } else if (filter == 4) { // Paeth
                    for (int i = 0; i < row.length; i++) {
                        int a = (i >= bpp) ? (row[i - bpp] & 0xFF) : 0;
                        int b = (y > 0) ? (rows[y - 1][i] & 0xFF) : 0;
                        int c = (i >= bpp && y > 0) ? (rows[y - 1][i - bpp] & 0xFF) : 0;
                        row[i] = (byte)(row[i] + paeth(a, b, c));
                    }
                }
                rows[y] = row;
            }

            // Sample to 8×8
            int[] pixels = new int[64];
            float sx = w / 8f, sy = h / 8f;
            for (int py = 0; py < 8; py++) {
                for (int px = 0; px < 8; px++) {
                    int ix = Math.min((int)(px * sx), w - 1);
                    int iy = Math.min((int)(py * sy), h - 1);
                    int off = ix * bpp;
                    int r = rows[iy][off] & 0xFF;
                    int g = rows[iy][off + 1] & 0xFF;
                    int b = rows[iy][off + 2] & 0xFF;
                    int a = (bpp == 4) ? (rows[iy][off + 3] & 0xFF) : 255;
                    pixels[py * 8 + px] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            return pixels;
        } catch (Exception e) {
            ChatFilterMod.LOGGER.debug("[CF] PNG decode error: {}", e.getMessage());
            return null;
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static String fetchUuid(String playerName) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                    .header("User-Agent", "Columba/4.0")
                    .GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            int idIdx = body.indexOf("\"id\":");
            if (idIdx < 0) return null;
            int start = body.indexOf('"', idIdx + 5) + 1;
            int end   = body.indexOf('"', start);
            String raw = body.substring(start, end);
            if (raw.length() != 32) return null;
            return raw.substring(0,8) + "-" + raw.substring(8,12) + "-"
                 + raw.substring(12,16) + "-" + raw.substring(16,20) + "-"
                 + raw.substring(20);
        } catch (Exception e) { return null; }
    }
}
