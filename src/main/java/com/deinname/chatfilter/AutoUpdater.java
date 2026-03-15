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
import java.util.ArrayList;
import java.util.List;
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

        // 4) Determine all mods directories to update (root + subfolders like NRC version dirs)
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path rootModsDir = gameDir.resolve("mods");
        List<Path> modsDirs = collectModsDirs(rootModsDir);

        // Also try to detect actual location of running JAR via FabricLoader
        Path runningJarDir = detectRunningJarDir();
        if (runningJarDir != null && !modsDirs.contains(runningJarDir)) {
            modsDirs.add(0, runningJarDir);
        }

        boolean anyDownloaded = false;
        for (Path modsDir : modsDirs) {
            if (!containsColumbaJar(modsDir)) continue;

            Path newJar = modsDir.resolve(assetName);
            Path tempJar = modsDir.resolve(assetName + ".tmp");

            // Skip if already downloaded
            if (Files.exists(newJar)) {
                LOG.info("[Columba] Update JAR already present in {}: {}", modsDir, assetName);
                anyDownloaded = true;
                continue;
            }

            downloadFile(downloadUrl, tempJar);
            Files.move(tempJar, newJar, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("[Columba] Downloaded update to {}/{}", modsDir.getFileName(), assetName);
            anyDownloaded = true;

            // Disable old JARs in this directory
            disableOldJars(modsDir, assetName);
        }

        // Fallback: if we didn't find any columba JARs anywhere, install to root mods dir
        if (!anyDownloaded) {
            Path newJar = rootModsDir.resolve(assetName);
            Path tempJar = rootModsDir.resolve(assetName + ".tmp");
            if (!Files.exists(newJar)) {
                downloadFile(downloadUrl, tempJar);
                Files.move(tempJar, newJar, StandardCopyOption.REPLACE_EXISTING);
                disableOldJars(rootModsDir, assetName);
                LOG.info("[Columba] Downloaded update to root mods: {}", assetName);
            }
        }

        updateReady = true;
        updateMessage = "\u00a78[\u00a7bColumba\u00a78] \u00a7aUpdate v" + remoteVersion
                + " heruntergeladen! \u00a77Starte Minecraft neu, um das Update zu aktivieren.";
        LOG.info("[Columba] Update v{} downloaded → {}", remoteVersion, assetName);
    }

    /**
     * On startup: delete Columba JARs that are OLDER than the current version.
     * Scans root mods dir AND all subdirectories (for NRC-style version folders).
     */
    private static void cleanOldJars(String currentVersion) {
        try {
            Path rootModsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            if (!Files.isDirectory(rootModsDir)) return;

            List<Path> dirs = collectModsDirs(rootModsDir);
            // Also include running JAR dir
            Path runningJarDir = detectRunningJarDir();
            if (runningJarDir != null && !dirs.contains(runningJarDir)) {
                dirs.add(runningJarDir);
            }

            for (Path dir : dirs) {
                cleanOldJarsInDir(dir, currentVersion);
            }
        } catch (IOException e) {
            LOG.debug("[Columba] cleanOldJars error: {}", e.getMessage());
        }
    }

    private static void cleanOldJarsInDir(Path dir, String currentVersion) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                // Always clean temp, .old, and .disabled files
                if (n.startsWith(JAR_PREFIX) && (n.endsWith(".jar.old") || n.endsWith(".jar.tmp") || n.endsWith(".jar.disabled")))
                    return true;
                // Only consider columba JARs
                if (!n.startsWith(JAR_PREFIX) || !n.endsWith(".jar")) return false;
                String ver = n.substring(JAR_PREFIX.length(), n.length() - 4);
                return isNewer(currentVersion, ver);
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    LOG.info("[Columba] Deleted old file: {}/{}", dir.getFileName(), p.getFileName());
                } catch (IOException e) {
                    LOG.debug("[Columba] Could not delete {}: {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.debug("[Columba] cleanOldJarsInDir error for {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Disable old columba JARs in a directory (rename to .disabled).
     */
    private static void disableOldJars(Path dir, String newAssetName) {
        final String newAssetLower = newAssetName.toLowerCase();
        try (var ls = Files.list(dir)) {
            ls.filter(p -> {
                String fn = p.getFileName().toString().toLowerCase();
                return fn.startsWith(JAR_PREFIX) && fn.endsWith(".jar")
                        && !fn.equals(newAssetLower);
            }).forEach(p -> {
                try {
                    Path disabled = p.resolveSibling(p.getFileName() + ".disabled");
                    Files.move(p, disabled, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("[Columba] Disabled old JAR: {}/{}", dir.getFileName(), disabled.getFileName());
                } catch (IOException ex) {
                    LOG.debug("[Columba] Could not disable {}: {}", p.getFileName(), ex.getMessage());
                }
            });
        } catch (IOException ex) {
            LOG.debug("[Columba] Error disabling old JARs in {}: {}", dir, ex.getMessage());
        }
    }

    /**
     * Collect the root mods dir + all immediate subdirectories (NRC version folders).
     */
    private static List<Path> collectModsDirs(Path rootModsDir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        dirs.add(rootModsDir);
        if (Files.isDirectory(rootModsDir)) {
            try (var sub = Files.list(rootModsDir)) {
                sub.filter(Files::isDirectory).forEach(dirs::add);
            }
        }
        return dirs;
    }

    /**
     * Detect the directory of the currently running Columba JAR via FabricLoader.
     */
    private static Path detectRunningJarDir() {
        try {
            var container = FabricLoader.getInstance().getModContainer("columba");
            if (container.isPresent()) {
                List<Path> paths = container.get().getOrigin().getPaths();
                if (!paths.isEmpty()) {
                    Path jarPath = paths.get(0);
                    Path parent = jarPath.getParent();
                    LOG.info("[Columba] Running JAR detected at: {}", jarPath);
                    return parent;
                }
            }
        } catch (Exception e) {
            LOG.debug("[Columba] Could not detect running JAR dir: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if a directory contains any Columba JAR.
     */
    private static boolean containsColumbaJar(Path dir) {
        try (var ls = Files.list(dir)) {
            return ls.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.startsWith(JAR_PREFIX) && (n.endsWith(".jar") || n.endsWith(".jar.disabled"));
            });
        } catch (IOException e) {
            return false;
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
