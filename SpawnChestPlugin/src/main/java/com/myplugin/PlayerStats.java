package com.myplugin;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PlayerStats {
    private final SpawnChestPlugin plugin;
    private final LanguageManager lang;
    private final File statsFolder;
    private final Map<UUID, PlayerData> cachedStats = new HashMap<>();
    
    public PlayerStats(SpawnChestPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.statsFolder = new File(plugin.getDataFolder(), "stats");
        if (!statsFolder.exists()) {
            statsFolder.mkdirs();
        }
    }
    
    public static class PlayerData {
        public String playerName;
        public int chestsOpened = 0;
        public int commonOpened = 0;
        public int rareOpened = 0;
        public int legendaryOpened = 0;
        public int legendaryItemsFound = 0;
        public int guardiansKilled = 0;
        public int applesUsed = 0;
        public Set<String> unlockedAchievements = new HashSet<>();
        public long firstJoin = System.currentTimeMillis();
        public long lastSeen = System.currentTimeMillis();
        
        public int getTotalScore() {
            return chestsOpened + (legendaryItemsFound * 5) + (guardiansKilled / 5);
        }
    }
    
    public PlayerData getStats(UUID playerId, String playerName) {
        if (cachedStats.containsKey(playerId)) {
            PlayerData data = cachedStats.get(playerId);
            data.playerName = playerName; // Update name in case it changed
            return data;
        }
        
        PlayerData data = loadFromFile(playerId);
        if (data == null) {
            data = new PlayerData();
            data.playerName = playerName;
            data.firstJoin = System.currentTimeMillis();
        }
        data.playerName = playerName;
        cachedStats.put(playerId, data);
        return data;
    }
    
    public void saveStats(UUID playerId) {
        PlayerData data = cachedStats.get(playerId);
        if (data == null) return;
        
        File file = new File(statsFolder, playerId.toString() + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        
        config.set("player-name", data.playerName);
        config.set("chests-opened", data.chestsOpened);
        config.set("common-opened", data.commonOpened);
        config.set("rare-opened", data.rareOpened);
        config.set("legendary-opened", data.legendaryOpened);
        config.set("legendary-items-found", data.legendaryItemsFound);
        config.set("guardians-killed", data.guardiansKilled);
        config.set("apples-used", data.applesUsed);
        config.set("achievements", new ArrayList<>(data.unlockedAchievements));
        config.set("first-join", data.firstJoin);
        config.set("last-seen", data.lastSeen);
        
        try {
            config.save(file);
        } catch (IOException e) {
            String message = lang.getMessage("system.stats-save-error",
                "%player%", playerId.toString(),
                "%error%", e.getMessage());
            plugin.getLogger().warning(message);
        }
    }
    
    private PlayerData loadFromFile(UUID playerId) {
        File file = new File(statsFolder, playerId.toString() + ".yml");
        if (!file.exists()) return null;
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        PlayerData data = new PlayerData();
        
        data.playerName = config.getString("player-name", lang.getMessage("system.player-unknown"));
        data.chestsOpened = config.getInt("chests-opened", 0);
        data.commonOpened = config.getInt("common-opened", 0);
        data.rareOpened = config.getInt("rare-opened", 0);
        data.legendaryOpened = config.getInt("legendary-opened", 0);
        data.legendaryItemsFound = config.getInt("legendary-items-found", 0);
        data.guardiansKilled = config.getInt("guardians-killed", 0);
        data.applesUsed = config.getInt("apples-used", 0);
        data.unlockedAchievements = new HashSet<>(config.getStringList("achievements"));
        data.firstJoin = config.getLong("first-join", System.currentTimeMillis());
        data.lastSeen = config.getLong("last-seen", System.currentTimeMillis());
        
        return data;
    }
    
    public void saveAll() {
        for (UUID playerId : cachedStats.keySet()) {
            saveStats(playerId);
        }
        String message = lang.getMessage("system.stats-saved",
            "%count%", String.valueOf(cachedStats.size()));
        plugin.getLogger().info(message);
    }
    
    // Increment methods
    public void incrementChestsOpened(UUID playerId, String playerName, String tier) {
        PlayerData data = getStats(playerId, playerName);
        data.chestsOpened++;
        data.lastSeen = System.currentTimeMillis();
        
        switch (tier.toLowerCase()) {
            case "common": 
                data.commonOpened++; 
                break;
            case "rare": 
                data.rareOpened++; 
                break;
            case "legendary": 
                data.legendaryOpened++; 
                break;
        }
        
        saveStats(playerId);
    }
    
    public void incrementLegendaryFound(UUID playerId, String playerName) {
        PlayerData data = getStats(playerId, playerName);
        data.legendaryItemsFound++;
        data.lastSeen = System.currentTimeMillis();
        saveStats(playerId);
    }
    
    public void incrementGuardiansKilled(UUID playerId, String playerName) {
        PlayerData data = getStats(playerId, playerName);
        data.guardiansKilled++;
        data.lastSeen = System.currentTimeMillis();
        saveStats(playerId);
    }
    
    public void incrementApplesUsed(UUID playerId, String playerName) {
        PlayerData data = getStats(playerId, playerName);
        data.applesUsed++;
        data.lastSeen = System.currentTimeMillis();
        saveStats(playerId);
    }
    
    public boolean unlockAchievement(UUID playerId, String playerName, String achievementId) {
        PlayerData data = getStats(playerId, playerName);
        if (data.unlockedAchievements.contains(achievementId)) {
            return false; // Already unlocked
        }
        data.unlockedAchievements.add(achievementId);
        saveStats(playerId);
        return true; // Newly unlocked
    }
    
    public boolean hasAchievement(UUID playerId, String playerName, String achievementId) {
        PlayerData data = getStats(playerId, playerName);
        return data.unlockedAchievements.contains(achievementId);
    }
    
    // Leaderboard
    public List<Map.Entry<String, Integer>> getLeaderboard(int limit) {
        Map<String, Integer> scores = new HashMap<>();
        
        // Load all stats from files
        File[] files = statsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return new ArrayList<>();
        
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String name = config.getString("player-name", lang.getMessage("system.player-unknown"));
            int score = config.getInt("chests-opened", 0);
            scores.put(name, score);
        }
        
        // Sort by score (descending)
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        // Return top N
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }
    
    // Get player rank
    public int getPlayerRank(UUID playerId, String playerName) {
        List<Map.Entry<String, Integer>> leaderboard = getLeaderboard(Integer.MAX_VALUE);
        PlayerData data = getStats(playerId, playerName);
        
        int rank = 1;
        for (Map.Entry<String, Integer> entry : leaderboard) {
            if (entry.getKey().equals(playerName)) {
                return rank;
            }
            rank++;
        }
        return -1; // Not found
    }
    
    // Clear cache for a player (useful when they disconnect)
    public void clearCache(UUID playerId) {
        cachedStats.remove(playerId);
    }
    
    // Get all cached player IDs
    public Set<UUID> getCachedPlayers() {
        return new HashSet<>(cachedStats.keySet());
    }
}