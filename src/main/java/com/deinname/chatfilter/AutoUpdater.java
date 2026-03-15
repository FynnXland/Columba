package com.deinname.chatfilter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * Automatic updater for Columba.
 * Checks GitHub Releases for new versions and hot-swaps the JAR in the mods folder.
 */
public final class AutoUpdater {

    private static final Logger LOG = LoggerFactory.getLogger("columba-updater");
    private static final String GITHUB_REPO = "FynnXland/Columba";
    private static final String API_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String JAR_PREFIX = "columba-";

    private static volatile String latestVersion = null;
    private static volatile boolean updateReady = false;
    private static volatile String updateMessage = null;

    public static boolean isUpdateReady() { return updateReady; }
    public static String getUpdateMessage() { return updateMessage; }
    public static String getLatestVersion() { return latestVersion; }

    /**
     * Run the update check asynchronously on a daemon thread.
     * Called once during mod initialization.
     */
    public static void checkAsync(String currentVersion) {
        CompletableFuture.runAsync(() -> {
            try {
                check(currentVersion);
            } catch (Exception e) {
                LOG.warn("[Columba] Update check failed: {}", e.getMessage());
            }
        });
    }

    private static void check(String currentVersion) throws Exception {
        // 1) Clean up old Columba JARs from previous updates (keep only current)
        cleanOldJars(currentVersion);

        // 2) Fetch latest release info from GitHub
        HttpURLConnection con = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
        con.setRequestProperty("Accept", "application/vnd.github.v3+json");
        con.setRequestProperty("User-Agent", "Columba-Updater");
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);

        if (con.getResponseCode() != 200) {
            LOG.info("[Columba] Update check: HTTP {}", con.getResponseCode());
            return;
        }

        String body;
        try (InputStream is = con.getInputStream()) {
            body = new String(is.readAllBytes());
        }

        JsonObject release = JsonParser.parseString(body).getAsJsonObject();
        String tagName = release.get("tag_name").getAsString();
        // Strip leading 'v' if present: "v4.1.0" → "4.1.0"
        String remoteVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

        if (!isNewer(remoteVersion, currentVersion)) {
            LOG.info("[Columba] Up to date (v{})", currentVersion);
            return;
        }

        // 3) Find the JAR asset in the release
        JsonArray assets = release.getAsJsonArray("assets");
        String downloadUrl = null;
        String assetName = null;
        for (int i = 0; i < assets.size(); i++) {
            JsonObject asset = assets.get(i).getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (name.endsWith(".jar") && name.startsWith(JAR_PREFIX)) {
                downloadUrl = asset.get("browser_download_url").getAsString();
                assetName = name;
                break;
            }
        }

        if (downloadUrl == null) {
            // Fallback: take first .jar asset
            for (int i = 0; i < assets.size(); i++) {
                JsonObject asset = assets.get(i).getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (name.endsWith(".jar")) {
                    downloadUrl = asset.get("browser_download_url").getAsString();
                    assetName = name;
                    break;
                }
            }
        }

        if (downloadUrl == null) {
            LOG.warn("[Columba] Release v{} has no JAR asset", remoteVersion);
            return;
        }

        latestVersion = remoteVersion;
        LOG.info("[Columba] Update available: v{} → v{}", currentVersion, remoteVersion);

        // 4) Download the new JAR next to the old one (different filename due to version)
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        Path newJar = modsDir.resolve(assetName);
        Path tempJar = modsDir.resolve(assetName + ".tmp");

        // Skip if already downloaded
        if (Files.exists(newJar)) {
            LOG.info("[Columba] Update JAR already present: {}", assetName);
            updateReady = true;
            updateMessage = "\u00a78[\u00a7bColumba\u00a78] \u00a7aUpdate v" + remoteVersion
                    + " bereit! \u00a77Starte Minecraft neu.";
            return;
        }

        downloadFile(downloadUrl, tempJar);

        // 5) Move temp → final (no rename of running JAR needed — on next start
        //    cleanOldJars() will delete the old version)
        Files.move(tempJar, newJar, StandardCopyOption.REPLACE_EXISTING);

        updateReady = true;
        updateMessage = "\u00a78[\u00a7bColumba\u00a78] \u00a7aUpdate v" + remoteVersion
                + " heruntergeladen! \u00a77Starte Minecraft neu, um das Update zu aktivieren.";
        LOG.info("[Columba] Update v{} downloaded → {}", remoteVersion, newJar.getFileName());
    }

    /**
     * On startup: delete all older columba-*.jar files, keeping only the one matching currentVersion.
     * This cleans up after a previous update where the new JAR was placed next to the old one.
     * Windows prevents renaming/deleting a JAR while it's loaded, so we only delete JARs that
     * are NOT the currently running version (i.e. leftover from previous versions).
     */
    private static void cleanOldJars(String currentVersion) {
        try {
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            if (!Files.isDirectory(modsDir)) return;
            String keepName = (JAR_PREFIX + currentVersion + ".jar").toLowerCase();
            try (var stream = Files.list(modsDir)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return (n.startsWith(JAR_PREFIX) && n.endsWith(".jar") && !n.equals(keepName))
                            || n.endsWith(".jar.old") || n.endsWith(".jar.tmp");
                }).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                        LOG.info("[Columba] Deleted old file: {}", p.getFileName());
                    } catch (IOException e) {
                        LOG.debug("[Columba] Could not delete {}: {}", p.getFileName(), e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            LOG.debug("[Columba] cleanOldJars error: {}", e.getMessage());
        }
    }

    private static void downloadFile(String url, Path dest) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        con.setRequestProperty("User-Agent", "Columba-Updater");
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);

        // GitHub may redirect; handle 3xx manually if needed
        int code = con.getResponseCode();
        if (code == 301 || code == 302) {
            String loc = con.getHeaderField("Location");
            con.disconnect();
            con = (HttpURLConnection) URI.create(loc).toURL().openConnection();
            con.setRequestProperty("User-Agent", "Columba-Updater");
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            code = con.getResponseCode();
        }

        if (code != 200) throw new IOException("Download failed: HTTP " + code);

        try (InputStream is = con.getInputStream(); OutputStream os = Files.newOutputStream(dest)) {
            is.transferTo(os);
        }
    }

    /**
     * Compare semantic versions. Returns true if remote is newer than current.
     * Handles formats like "4.0.0", "4.1.0-beta", etc.
     */
    static boolean isNewer(String remote, String current) {
        try {
            int[] r = parseVersion(remote);
            int[] c = parseVersion(current);
            for (int i = 0; i < 3; i++) {
                if (r[i] > c[i]) return true;
                if (r[i] < c[i]) return false;
            }
            // Same numeric version: pre-release current < stable remote
            boolean remotePrerelease = remote.contains("-");
            boolean currentPrerelease = current.contains("-");
            if (currentPrerelease && !remotePrerelease) return true;
            return false;
        } catch (Exception e) {
            return !remote.equals(current);
        }
    }

    private static int[] parseVersion(String v) {
        // Strip pre-release suffix
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        String[] parts = v.split("\\.");
        int[] nums = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            nums[i] = Integer.parseInt(parts[i]);
        }
        return nums;
    }
}
