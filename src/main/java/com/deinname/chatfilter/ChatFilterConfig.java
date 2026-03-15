package com.deinname.chatfilter;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persistent configuration for Columba.
 * Stores filter rules as JSON (version 2 format) in the Fabric config directory.
 * Backward-compatible with the old plain word-list format.
 */
public final class ChatFilterConfig {

    public static final int MAX_WORD_LENGTH = 64;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR  = FabricLoader.getInstance().getConfigDir();
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("chatfilter.json");
    private static final Path EXPORT_FILE = CONFIG_DIR.resolve("chatfilter_shared.json");

    private static List<FilterRule> rules = new ArrayList<>();
    private static String themeName = "OCEAN";
    private static int customAccentR = 59;
    private static int customAccentG = 130;
    private static int customAccentB = 246;

    // ── General Settings ─────────────────────────────────────────────────────
    private static boolean showTimeoutOverlay = true;
    private static boolean showPingMessages = true;
    private static boolean showSyncNotifications = true;

    private ChatFilterConfig() {}

    // ── Public API ───────────────────────────────────────────────────────────

    public static String getThemeName() { return themeName; }
    public static void setThemeName(String name) { themeName = name; save(); }

    public static int getCustomAccentR() { return customAccentR; }
    public static int getCustomAccentG() { return customAccentG; }
    public static int getCustomAccentB() { return customAccentB; }
    public static void setCustomAccent(int r, int g, int b) {
        customAccentR = r; customAccentG = g; customAccentB = b; save();
    }

    public static List<FilterRule> getRules() { return rules; }

    // ── Settings API ─────────────────────────────────────────────────────────
    public static boolean isShowTimeoutOverlay() { return showTimeoutOverlay; }
    public static void setShowTimeoutOverlay(boolean v) { showTimeoutOverlay = v; save(); }
    public static boolean isShowPingMessages() { return showPingMessages; }
    public static void setShowPingMessages(boolean v) { showPingMessages = v; save(); }
    public static boolean isShowSyncNotifications() { return showSyncNotifications; }
    public static void setShowSyncNotifications(boolean v) { showSyncNotifications = v; save(); }

    public static void setRules(List<FilterRule> newRules) {
        rules = new ArrayList<>(newRules);
        save();
    }

    public static String tryAddRule(String rawWord) {
        String word = rawWord == null ? "" : rawWord.strip().toLowerCase(Locale.ROOT);
        if (word.isEmpty()) return "Wort darf nicht leer sein.";
        if (word.length() > MAX_WORD_LENGTH) return "Zu lang (max. " + MAX_WORD_LENGTH + " Zeichen).";
        for (FilterRule r : rules) {
            if (r.getKeyword().equals(word)) return "\"" + word + "\" ist bereits vorhanden.";
        }
        rules.add(new FilterRule(word));
        save();
        return null;
    }

    /** Finds the first enabled rule whose keyword is contained in the message. */
    public static FilterRule findMatchingRule(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (FilterRule rule : rules) {
            if (rule.isEnabled() && !rule.getKeyword().isEmpty() && lower.contains(rule.getKeyword())) {
                return rule;
            }
        }
        return null;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static void load() {
        if (!Files.exists(CONFIG_FILE)) {
            rules.add(new FilterRule("spam"));
            save();
            return;
        }
        try {
            String json = Files.readString(CONFIG_FILE);
            JsonElement elem = JsonParser.parseString(json);
            if (elem.isJsonObject() && elem.getAsJsonObject().has("version")) {
                ConfigData data = GSON.fromJson(json, ConfigData.class);
                if (data != null && data.rules != null) rules = new ArrayList<>(data.rules);
                if (data != null && data.theme != null) themeName = data.theme;
                if (data != null) {
                    customAccentR = data.customAccentR;
                    customAccentG = data.customAccentG;
                    customAccentB = data.customAccentB;
                    showTimeoutOverlay = data.showTimeoutOverlay;
                    showPingMessages = data.showPingMessages;
                    showSyncNotifications = data.showSyncNotifications;
                }
            } else if (elem.isJsonArray()) {
                // Migrate old format (plain string list)
                Type listType = new TypeToken<List<String>>() {}.getType();
                List<String> words = GSON.fromJson(json, listType);
                rules = new ArrayList<>();
                if (words != null) for (String w : words) rules.add(new FilterRule(w));
                save();
            }
        } catch (Exception e) {
            ChatFilterMod.LOGGER.error("[Columba] Config load failed: {}", e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            ConfigData data = new ConfigData();
            data.version = 2;
            data.rules = new ArrayList<>(rules);
            data.theme = themeName;
            data.customAccentR = customAccentR;
            data.customAccentG = customAccentG;
            data.customAccentB = customAccentB;
            data.showTimeoutOverlay = showTimeoutOverlay;
            data.showPingMessages = showPingMessages;
            data.showSyncNotifications = showSyncNotifications;
            Files.writeString(CONFIG_FILE, GSON.toJson(data));
        } catch (IOException e) {
            ChatFilterMod.LOGGER.error("[Columba] Config save failed: {}", e.getMessage());
        }
    }

    // ── Import / Export ──────────────────────────────────────────────────────

    public static Path getExportPath() { return EXPORT_FILE; }

    public static Path exportRules() {
        try {
            ConfigData data = new ConfigData();
            data.version = 2;
            data.rules = new ArrayList<>(rules);
            Files.writeString(EXPORT_FILE, GSON.toJson(data));
            return EXPORT_FILE;
        } catch (IOException e) {
            ChatFilterMod.LOGGER.error("[Columba] Export failed: {}", e.getMessage());
            return null;
        }
    }

    /** Reads the export file without modifying internal state. Returns null if file missing. */
    public static List<FilterRule> readExportFile() {
        if (!Files.exists(EXPORT_FILE)) return null;
        try {
            String json = Files.readString(EXPORT_FILE);
            ConfigData data = GSON.fromJson(json, ConfigData.class);
            return data != null && data.rules != null ? data.rules : new ArrayList<>();
        } catch (Exception e) {
            ChatFilterMod.LOGGER.error("[Columba] Export read failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static class ConfigData {
        int version = 2;
        List<FilterRule> rules = new ArrayList<>();
        String theme = "OCEAN";
        int customAccentR = 59;
        int customAccentG = 130;
        int customAccentB = 246;
        boolean showTimeoutOverlay = true;
        boolean showPingMessages = true;
        boolean showSyncNotifications = true;
    }
}
