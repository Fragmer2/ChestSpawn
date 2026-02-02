package com.myplugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages language files for the plugin
 * Supports: en, ru, ua, es, de, fr, zh, pt, pl, it
 */
public class LanguageManager {
    
    private final JavaPlugin plugin;
    private YamlConfiguration langConfig;
    private String currentLang;
    
    // Cache for frequently accessed messages (performance optimization)
    private final Map<String, String> messageCache = new HashMap<>();
    private final Map<String, List<String>> listCache = new HashMap<>();
    
    // Available languages
    public static final List<String> AVAILABLE_LANGUAGES = Arrays.asList(
        "en",  // English
        "ru",  // Russian (Русский)
        "ua",  // Ukrainian (Українська)
        "es",  // Spanish (Español)
        "de",  // German (Deutsch)
        "fr",  // French (Français)
        "zh",  // Chinese (中文)
        "pt",  // Portuguese (Português)
        "pl",  // Polish (Polski)
        "it"   // Italian (Italiano)
    );
    
    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadLanguage();
    }
    
    /**
     * Load language from config setting
     */
    public void loadLanguage() {
        // Clear caches
        messageCache.clear();
        listCache.clear();
        
        String lang = plugin.getConfig().getString("language", "en").toLowerCase();
        
        // Validate language
        if (!AVAILABLE_LANGUAGES.contains(lang)) {
            plugin.getLogger().warning("Unknown language '" + lang + "', falling back to English (en)");
            lang = "en";
        }
        
        this.currentLang = lang;
        
        // Create lang folder if needed
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        
        // Save all default language files
        saveDefaultLanguages(langFolder);
        
        // Load selected language
        File langFile = new File(langFolder, lang + ".yml");
        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
            plugin.getLogger().info("Loaded language: " + getLanguageName(lang) + " (" + lang + ")");
        } else {
            plugin.getLogger().severe("Language file not found: " + lang + ".yml");
            // Fallback to English
            langFile = new File(langFolder, "en.yml");
            if (langFile.exists()) {
                langConfig = YamlConfiguration.loadConfiguration(langFile);
                plugin.getLogger().warning("Falling back to English");
            } else {
                langConfig = new YamlConfiguration();
                plugin.getLogger().severe("No language files available!");
            }
        }
    }
    
    /**
     * Save all default language files to lang folder
     */
    private void saveDefaultLanguages(File langFolder) {
        for (String lang : AVAILABLE_LANGUAGES) {
            File langFile = new File(langFolder, lang + ".yml");
            if (!langFile.exists()) {
                // Try to save from resources
                try {
                    InputStream in = plugin.getResource("lang/" + lang + ".yml");
                    if (in != null) {
                        saveResource(in, langFile);
                        plugin.getLogger().info("Created language file: " + lang + ".yml");
                    } else {
                        // If resource not found and it's English, create a minimal file
                        if (lang.equals("en")) {
                            plugin.getLogger().warning("Could not find embedded " + lang + ".yml, creating empty file");
                            langFile.createNewFile();
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not create language file " + lang + ".yml: " + e.getMessage());
                }
            }
        }
    }
    
    private void saveResource(InputStream in, File outFile) {
        try (OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + outFile.getName() + ": " + e.getMessage());
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Get a message from the language file with replacements
     * Example: getMessage("commands.next-chest", "%minutes%", "5", "%seconds%", "30")
     */
    public String getMessage(String path, String... replacements) {
        // Try cache first (only for messages without replacements)
        if (replacements.length == 0 && messageCache.containsKey(path)) {
            return messageCache.get(path);
        }
        
        String message = langConfig.getString(path);
        
        // Fallback to path if not found
        if (message == null) {
            String fallback = "§c[Missing: " + path + "]";
            plugin.getLogger().warning("Missing translation key: " + path + " in language: " + currentLang);
            return fallback;
        }
        
        // Apply replacements
        for (int i = 0; i < replacements.length - 1; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        
        // Cache if no replacements
        if (replacements.length == 0) {
            messageCache.put(path, message);
        }
        
        return message;
    }
    
    /**
     * Get a list of strings from the language file (for item lore)
     * Example: getMessageList("items.summoner-apple.lore")
     */
    public List<String> getMessageList(String path, String... replacements) {
        // Try cache first (only for lists without replacements)
        if (replacements.length == 0 && listCache.containsKey(path)) {
            return listCache.get(path);
        }
        
        List<String> messages = langConfig.getStringList(path);
        
        if (messages.isEmpty()) {
            plugin.getLogger().warning("Missing translation list: " + path + " in language: " + currentLang);
            return Arrays.asList("§c[Missing: " + path + "]");
        }
        
        // Apply replacements to each line
        for (int i = 0; i < messages.size(); i++) {
            String msg = messages.get(i);
            for (int j = 0; j < replacements.length - 1; j += 2) {
                msg = msg.replace(replacements[j], replacements[j + 1]);
            }
            messages.set(i, msg);
        }
        
        // Cache if no replacements
        if (replacements.length == 0) {
            listCache.put(path, messages);
        }
        
        return messages;
    }
    
    /**
     * Check if a path exists in the language file
     */
    public boolean hasMessage(String path) {
        return langConfig.contains(path);
    }
    
    /**
     * Get current language code
     */
    public String getCurrentLanguage() {
        return currentLang;
    }
    
    /**
     * Get the raw YamlConfiguration for advanced usage
     */
    public YamlConfiguration getConfig() {
        return langConfig;
    }
    
    /**
     * Get human-readable language name
     */
    public static String getLanguageName(String code) {
        switch (code.toLowerCase()) {
            case "en": return "English";
            case "ru": return "Русский";
            case "ua": return "Українська";
            case "es": return "Español";
            case "de": return "Deutsch";
            case "fr": return "Français";
            case "zh": return "中文";
            case "pt": return "Português";
            case "pl": return "Polski";
            case "it": return "Italiano";
            default: return code;
        }
    }
    
    /**
     * Reload language file
     */
    public void reload() {
        loadLanguage();
    }
    
    /**
     * Get available languages as formatted string
     */
    public static String getAvailableLanguagesString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AVAILABLE_LANGUAGES.size(); i++) {
            String code = AVAILABLE_LANGUAGES.get(i);
            sb.append(code).append(" (").append(getLanguageName(code)).append(")");
            if (i < AVAILABLE_LANGUAGES.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }
    
    /**
     * Clear message cache (useful after reloading config)
     */
    public void clearCache() {
        messageCache.clear();
        listCache.clear();
    }
    
    /**
     * Get cache statistics for debugging
     */
    public String getCacheStats() {
        return "Message cache: " + messageCache.size() + " entries, List cache: " + listCache.size() + " entries";
    }
}