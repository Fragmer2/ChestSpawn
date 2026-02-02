package com.myplugin;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class SpawnChestPlugin extends JavaPlugin implements Listener, TabCompleter {
    
    private volatile boolean spawnInProgress = false;
    private volatile long spawnStartTime = 0;
    private long lastSpawnTime = 0L;
    private long spawnInterval;
    private final Set<Long> warningTimes = new HashSet<>(Arrays.asList(24000L, 12000L, 6000L, 3600L, 1200L));
    private final Set<Long> warned = new HashSet<>();
    private final Set<Integer> lastSecondsWarned = new HashSet<>();
    private final Random random = new Random();
    
    // Active chests tracking (for disappearing and effects)
    private final Map<String, ActiveChest> activeChests = new HashMap<>();
    
    // Cooldown maps
    private final Map<UUID, Long> swordCooldown = new HashMap<>();
    private final Map<UUID, Long> axeCooldown = new HashMap<>();
    private final Map<UUID, Long> hammerCooldown = new HashMap<>();
    private final Map<UUID, Long> bowCooldown = new HashMap<>();
    private final Map<UUID, Long> shovelCooldown = new HashMap<>();
    private final Map<UUID, Long> pickaxeCooldown = new HashMap<>();
    private final Map<UUID, Long> treeCutCooldown = new HashMap<>();
    private final Map<UUID, Long> tridentCooldown = new HashMap<>();
    
    private File dataFile;
    
    // Statistics system
    private PlayerStats playerStats;
    
    // Custom loot system
    private CustomLootManager customLootManager;
    
    // Language system
    private LanguageManager langManager;
    
    // Version adapter for multi-version support
    private VersionAdapter versionAdapter;
    
    // NamespacedKeys
    private NamespacedKey LEGENDARY_SWORD_KEY;
    private NamespacedKey MASTER_PICKAXE_KEY;
    private NamespacedKey PHOENIX_FEATHER_KEY;
    private NamespacedKey GUARDIAN_BOW_KEY;
    private NamespacedKey TITAN_AXE_KEY;
    private NamespacedKey VOID_SHOVEL_KEY;
    private NamespacedKey STORM_HAMMER_KEY;
    private NamespacedKey WISDOM_BOOK_KEY;
    private NamespacedKey POSEIDON_TRIDENT_KEY;
    private NamespacedKey SUMMONER_APPLE_KEY;
    private NamespacedKey GUARDIAN_MOB_KEY;
    private NamespacedKey CUSTOM_POTION_KEY;
    
    // Particle cache
    private Particle cachedHappyVillager;
    private Particle cachedRain;
    
 // ==========================================
    // LANGUAGE/MESSAGE HELPER METHODS
    // ==========================================
    
    /**
     * Get a message from language file with placeholders replaced
     */
    private String getMessage(String path, String... replacements) {
        if (langManager == null) {
            return "§c[Lang not loaded: " + path + "]";
        }
        return langManager.getMessage(path, replacements);
    }
    
    /**
     * Get a list of strings from language file (for item lore)
     */
    private List<String> getMessageList(String path, String... replacements) {
        if (langManager == null) {
            return Arrays.asList("§c[Lang not loaded: " + path + "]");
        }
        return langManager.getMessageList(path, replacements);
    }
    
    /**
     * Get lore as List<String> for items
     */
    private List<String> getLoreList(String path, String... replacements) {
        return getMessageList(path, replacements);
    }
    
    /**
     * Broadcast a message to all players
     */
    private void broadcastMessage(String path, String... replacements) {
        String message = getMessage(path, replacements);
        Bukkit.broadcastMessage(message);
    }
    
    /**
     * Send a message to a player
     */
    private void sendMessage(CommandSender sender, String path, String... replacements) {
        sender.sendMessage(getMessage(path, replacements));
    }
    
    /**
     * Get tier display name from config
     */
    private String getTierName(ChestTier tier) {
        String tierKey = tier.name().toLowerCase();
        return getMessage("tiers." + tierKey + ".name");
    }
    
    /**
     * Get guardian name for tier from config
     */
    private String getGuardianName(ChestTier tier) {
        String tierKey = tier.name().toLowerCase();
        return getMessage("tiers." + tierKey + ".guardian-name");
    }
    
    public VersionAdapter getVersionAdapter() {
        return versionAdapter;
    }
    
    public PlayerStats getPlayerStats() {
        return playerStats;
    }
    
    public LanguageManager getLanguageManager() {
        return langManager;
    }
    
    // Active chest data class
    public static class ActiveChest {
        public Location location;
        public ChestTier tier;
        public long spawnTime;
        public boolean opened = false;
        public String chestKey;
        
        public ActiveChest(Location loc, ChestTier tier) {
            this.location = loc;
            this.tier = tier;
            this.spawnTime = System.currentTimeMillis();
            this.chestKey = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        }
    }
    
    // Chest Tiers
    public enum ChestTier {
        COMMON("common", "§fCommon Chest", "§7Contains basic items"),
        RARE("rare", "§9Rare Chest", "§7Contains valuable items"),
        LEGENDARY("legendary", "§6Legendary Chest", "§7Contains epic treasures!");
        
        public final String configKey;
        public final String displayName;
        public final String description;
        
        ChestTier(String configKey, String displayName, String description) {
            this.configKey = configKey;
            this.displayName = displayName;
            this.description = description;
        }
    }

    // ==================== LIFECYCLE ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        
        this.spawnInterval = getConfig().getLong("settings.spawn-interval-seconds", 600L) * 1000L;
        
        // Initialize keys
        LEGENDARY_SWORD_KEY = new NamespacedKey(this, "legendary_sword");
        MASTER_PICKAXE_KEY = new NamespacedKey(this, "master_pickaxe");
        PHOENIX_FEATHER_KEY = new NamespacedKey(this, "phoenix_feather");
        GUARDIAN_BOW_KEY = new NamespacedKey(this, "guardian_bow");
        TITAN_AXE_KEY = new NamespacedKey(this, "titan_axe");
        VOID_SHOVEL_KEY = new NamespacedKey(this, "void_shovel");
        STORM_HAMMER_KEY = new NamespacedKey(this, "storm_hammer");
        WISDOM_BOOK_KEY = new NamespacedKey(this, "wisdom_book");
        POSEIDON_TRIDENT_KEY = new NamespacedKey(this, "poseidon_trident");
        SUMMONER_APPLE_KEY = new NamespacedKey(this, "summoner_apple");
        GUARDIAN_MOB_KEY = new NamespacedKey(this, "chest_guardian");
        CUSTOM_POTION_KEY = new NamespacedKey(this, "custom_potion");
        
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "timer.txt");
        
        // Initialize language manager FIRST (before version adapter)
        langManager = new LanguageManager(this);
        
        // Initialize version adapter (needs langManager)
        versionAdapter = new VersionAdapter(this, langManager);
        
        // Check minimum version
        if (versionAdapter.isBelow(1, 18)) {
            getLogger().severe(getMessage("system.plugin-version-too-old"));
            getLogger().severe(getMessage("system.plugin-version-detected", 
                "%version%", versionAdapter.getVersionDisplay()));
            getLogger().severe(getMessage("system.plugin-version-update"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Initialize particle cache
        cachedHappyVillager = resolveParticle("HAPPY_VILLAGER", "VILLAGER_HAPPY", Particle.ENCHANTMENT_TABLE);
        cachedRain = resolveParticle("RAIN", "WATER_DROP", Particle.DRIP_WATER);
        
        // Initialize stats system (needs langManager)
        playerStats = new PlayerStats(this);
        customLootManager = new CustomLootManager(this);
        
        loadTimer();
        
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Register tab completers
        if (getCommand("chestconfig") != null) getCommand("chestconfig").setTabCompleter(this);
        if (getCommand("togglelegendary") != null) getCommand("togglelegendary").setTabCompleter(this);
        if (getCommand("togglefeature") != null) getCommand("togglefeature").setTabCompleter(this);
        if (getCommand("giveapple") != null) getCommand("giveapple").setTabCompleter(this);
        
        // Start tasks
        new ChestTimer().runTaskTimer(this, 0L, 20L);
        new PhoenixPassiveTask().runTaskTimer(this, 0L, 40L);
        new PoseidonRainTask().runTaskTimer(this, 0L, 10L);
        new ProximitySoundTask().runTaskTimer(this, 0L, getConfig().getLong("proximity-sounds.interval-ticks", 40L));
        new ChestEffectsTask().runTaskTimer(this, 0L, 5L);
        new ChestDisappearTask().runTaskTimer(this, 0L, 20L);
        
        getLogger().info(getMessage("system.plugin-enabled"));
    }

    @Override
    public void onDisable() {
        saveTimer();
        if (playerStats != null) {
            playerStats.saveAll();
        }
        getLogger().info(getMessage("system.plugin-disabled"));
    }

    // ==================== CONFIG HELPERS ====================
    
    private boolean isFeatureEnabled(String path) {
        return getConfig().getBoolean(path, true);
    }
    
    private void setFeature(String path, boolean value) {
        getConfig().set(path, value);
        saveConfig();
    }
    
    private void setConfigValue(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
    }
    
    // Feature getters
    private boolean isGuardiansEnabled() {
        return isFeatureEnabled("features.guardians.enabled");
    }
    
    private boolean isParticlesEnabled() {
        return isFeatureEnabled("features.effects.particles");
    }
    
    private boolean isSoundsEnabled() {
        return isFeatureEnabled("features.effects.sounds");
    }
    
    private boolean isLightningEnabled() {
        return isFeatureEnabled("features.effects.lightning-on-legendary");
    }
    
    private boolean isPvpProtection() {
        return isFeatureEnabled("abilities.pvp-protection");
    }
    
    private boolean isLegendaryItemsEnabled() {
        return isFeatureEnabled("legendary-items.enabled");
    }
    
    private boolean isStatsEnabled() {
        return isFeatureEnabled("statistics.enabled");
    }
    
    private boolean isItemEnabled(String itemKey) {
        return isLegendaryItemsEnabled() && isFeatureEnabled("legendary-items." + itemKey + ".enabled");
    }
    
    private boolean canDropInChest(String itemKey) {
        return getConfig().getBoolean("legendary-items." + itemKey + ".drop-in-chests", true);
    }
    
    private boolean isWorldEnabled(String worldName) {
        List<String> enabledWorlds = getConfig().getStringList("settings.enabled-worlds");
        return enabledWorlds.isEmpty() || enabledWorlds.contains(worldName);
    }
    
    // Cooldown getters from config
    private long getSwordCooldown() {
        return getConfig().getLong("legendary-items.dragon-slayer-sword.cooldown-ms", 3000L);
    }
    
    private long getPickaxeCooldown() {
        return getConfig().getLong("legendary-items.master-pickaxe.cooldown-ms", 1000L);
    }
    
    private long getAxeCooldown() {
        return getConfig().getLong("legendary-items.titan-axe.combat-cooldown-ms", 4000L);
    }
    
    private long getTreeCooldown() {
        return getConfig().getLong("legendary-items.titan-axe.tree-cooldown-ms", 6000L);
    }
    
    private long getShovelCooldown() {
        return getConfig().getLong("legendary-items.void-shovel.cooldown-ms", 5000L);
    }
    
    private long getHammerCooldown() {
        return getConfig().getLong("legendary-items.storm-hammer.cooldown-ms", 8000L);
    }
    
    private long getBowCooldown() {
        return getConfig().getLong("legendary-items.guardian-bow.cooldown-ms", 2000L);
    }
    
    private long getTridentCooldown() {
        return getConfig().getLong("legendary-items.poseidon-trident.cooldown-ms", 10000L);
    }
    
    // Damage getters from config
    private double getSwordBonusDamage() {
        return getConfig().getDouble("legendary-items.dragon-slayer-sword.bonus-damage", 4.0);
    }
    
    private double getAxeBonusDamage() {
        return getConfig().getDouble("legendary-items.titan-axe.bonus-damage", 3.0);
    }
    
    private double getHammerBonusDamage() {
        return getConfig().getDouble("legendary-items.storm-hammer.bonus-damage", 5.0);
    }
    
    private double getHammerAreaDamage() {
        return getConfig().getDouble("legendary-items.storm-hammer.area-damage-amount", 4.0);
    }
    
    private double getTridentLightningDamage() {
        return getConfig().getDouble("legendary-items.poseidon-trident.lightning-damage", 6.0);
    }
    
    private int getGuardianCount(ChestTier tier) {
        return getConfig().getInt("features.guardians." + tier.configKey + "-count", 1);
    }

    // ==================== TIMER PERSISTENCE ====================
    
    private void loadTimer() {
        if (dataFile.exists()) {
            try (FileReader reader = new FileReader(dataFile)) {
                Scanner scanner = new Scanner(reader);
                if (scanner.hasNextLong()) {
                    lastSpawnTime = scanner.nextLong();
                    getLogger().info(getMessage("system.timer-loaded", 
                        "%time%", String.valueOf(lastSpawnTime)));
                } else {
                    // First run - spawn soon (30 seconds)
                    lastSpawnTime = System.currentTimeMillis() - spawnInterval + 30000L;
                    getLogger().info(getMessage("system.timer-first-run"));
                }
            } catch (IOException e) {
                getLogger().warning(getMessage("system.timer-load-error", 
                    "%error%", e.getMessage()));
                lastSpawnTime = System.currentTimeMillis() - spawnInterval + 30000L;
            }
        } else {
            // First run - spawn soon (30 seconds)
            lastSpawnTime = System.currentTimeMillis() - spawnInterval + 30000L;
            getLogger().info(getMessage("system.timer-first-run"));
        }
    }
    
    private void saveTimer() {
        try (FileWriter writer = new FileWriter(dataFile)) {
            writer.write(String.valueOf(lastSpawnTime));
        } catch (IOException e) {
            getLogger().warning(getMessage("system.timer-save-error", 
                "%error%", e.getMessage()));
        }
    }
    
 // ==================== CHEST SPAWNING ====================

    private void spawnChest() {
        spawnChest(true, null);
    }
    
    // Main spawn method - can be called with or without timer reset
    private void spawnChest(boolean resetTimer, World specificWorld) {
        // Check for stuck spawn (timeout after 30 seconds)
        if (spawnInProgress) {
            long elapsed = System.currentTimeMillis() - spawnStartTime;
            if (elapsed > 30000) {
                getLogger().warning(getMessage("system.chest-spawn-stuck", 
                    "%seconds%", String.valueOf(elapsed/1000)));
                spawnInProgress = false;
            } else {
                getLogger().warning(getMessage("system.chest-spawn-started", 
                    "%elapsed%", String.valueOf(elapsed/1000)));
                return;
            }
        }
        spawnInProgress = true;
        spawnStartTime = System.currentTimeMillis();
        
        World world;
        
        if (specificWorld != null) {
            world = specificWorld;
        } else {
            // Get random enabled world
            List<String> enabledWorlds = getConfig().getStringList("settings.enabled-worlds");
            if (enabledWorlds.isEmpty()) {
                world = Bukkit.getWorld("world");
            } else {
                String worldName = enabledWorlds.get(random.nextInt(enabledWorlds.size()));
                world = Bukkit.getWorld(worldName);
            }
        }
        
        if (world == null) {
            world = Bukkit.getWorld("world");
        }
        
        if (world == null || !isWorldEnabled(world.getName())) {
            getLogger().warning(getMessage("system.chest-invalid-world"));
            spawnInProgress = false;
            return;
        }

        // Generate random location and load chunk async
        findAndSpawnChestAsync(world, resetTimer, 0);
    }
    
    // Async chest spawning with retry
    private void findAndSpawnChestAsync(World world, boolean resetTimer, int attempt) {
        if (attempt >= 15) {
            getLogger().warning(getMessage("system.chest-spawn-failed-attempts", 
                "%attempts%", "15"));
            spawnInProgress = false; // IMPORTANT: Reset flag!
            return;
        }
        
        int minDist = getConfig().getInt("settings.spawn-zone.min-distance", 400);
        int maxDist = getConfig().getInt("settings.spawn-zone.max-distance", 2000);
        int minDistFromCenter = getConfig().getInt("settings.spawn-zone.min-distance-from-center", 100);
        
        // Reduce distance on retries but NEVER go below min-distance-from-center
        if (attempt > 5) {
            minDist = Math.max(minDistFromCenter, 100);
            maxDist = Math.max(minDistFromCenter + 200, 500);
        }
        if (attempt > 10) {
            minDist = Math.max(minDistFromCenter, 50);
            maxDist = Math.max(minDistFromCenter + 100, 300);
        }
        
        // Use world spawn as center, but calculate distance from 0,0 for overworld
        Location center = world.getSpawnLocation();
        int centerX = 0; // Always use 0,0 as center for distance calculation
        int centerZ = 0;
        
        double distance = minDist + random.nextDouble() * (maxDist - minDist);
        double angle = random.nextDouble() * 2 * Math.PI;
        int x = centerX + (int)(Math.cos(angle) * distance);
        int z = centerZ + (int)(Math.sin(angle) * distance);
       
        // Ensure we're at least min-distance-from-center away from 0,0
        double distFromCenter = Math.sqrt(x * x + z * z);
        if (distFromCenter < minDistFromCenter) {
            // Push the point outward
            double scale = minDistFromCenter / distFromCenter;
            x = (int)(x * scale);
            z = (int)(z * scale);
        }
        
        final int finalX = x;
        final int finalZ = z;
        
        getLogger().info(getMessage("system.chest-spawn-attempt",
            "%attempt%", String.valueOf(attempt + 1),
            "%x%", String.valueOf(x),
            "%z%", String.valueOf(z),
            "%dist%", String.valueOf((int)distance)));
        
        // Load chunk asynchronously (version-safe)
        versionAdapter.getChunkAtAsync(world, x >> 4, z >> 4, chunk -> {
            // Process on main thread
            Bukkit.getScheduler().runTask(this, () -> {
                int minHeight = getConfig().getInt("settings.spawn-zone.min-height", 50);
                int maxHeight = getConfig().getInt("settings.spawn-zone.max-height", 200);
                boolean buryInGround = getConfig().getBoolean("settings.spawn-zone.bury-in-ground", false);
                
                Location loc = getValidSpawnLocation(world, finalX, finalZ, minHeight, maxHeight, buryInGround);
                
                if (loc != null) {
                    getLogger().info(getMessage("system.chest-location-found",
                        "%x%", String.valueOf(loc.getBlockX()),
                        "%y%", String.valueOf(loc.getBlockY()),
                        "%z%", String.valueOf(loc.getBlockZ())));
                    spawnChestAtLocation(loc, resetTimer);
                } else {
                    // Try again with new location
                    findAndSpawnChestAsync(world, resetTimer, attempt + 1);
                }
            });
        });
    }
    
    // Get valid spawn location at X,Z coordinates
    private Location getValidSpawnLocation(World world, int x, int z, int minHeight, int maxHeight, boolean buryInGround) {
        int y = getHighestSolidBlock(world, x, z);
        
        // Check height limits
        if (y < minHeight || y > maxHeight) {
            return null;
        }
        
        // Check surface type - don't spawn on water, lava, leaves etc
        Material surface = world.getBlockAt(x, y, z).getType();
        if (!isValidSurface(surface)) {
            return null;
        }
        
        // Return location - spawnChestAtLocation will calculate exact Y
        return new Location(world, x, y, z);
    }
    
    // Find the highest solid block at X,Z (returns Y of the solid block itself)
    private int getHighestSolidBlock(World world, int x, int z) {
        int startY = world.getMaxHeight() - 1;
        
        // First find any non-air block
        for (int y = startY; y > 0; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (!type.isAir()) {
                startY = y + 10;
                break;
            }
        }
        
        // Now find the actual highest SOLID ground block
        for (int y = Math.min(startY, world.getMaxHeight() - 1); y > 0; y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            
            if (type.isAir()) continue;
            if (type == Material.WATER || type == Material.LAVA) continue;
            
            // Version-safe vegetation check (using String comparison)
            String typeName = type.name();
            if (typeName.contains("LEAVES")) continue;
            if (typeName.contains("LOG") && !typeName.contains("STRIPPED")) continue;
            if (typeName.contains("VINE")) continue;
            if (typeName.contains("SNOW") && type != Material.SNOW_BLOCK) continue;
            if (typeName.contains("CARPET")) continue;
            if (typeName.contains("FLOWER")) continue;
            if (typeName.contains("TALL_") || typeName.contains("LARGE_")) continue;
            
            // Grass check (SHORT_GRASS in 1.20.3+, GRASS in older versions)
            if (typeName.equals("GRASS") || typeName.equals("SHORT_GRASS") || typeName.equals("TALL_GRASS")) continue;
            
            if (typeName.contains("FERN")) continue;
            if (typeName.contains("BUSH")) continue;
            if (typeName.contains("SAPLING")) continue;
            if (typeName.contains("MUSHROOM") && !typeName.contains("BLOCK")) continue;
            if (typeName.contains("KELP") || typeName.contains("SEAGRASS")) continue;
            if (typeName.contains("CORAL") && !typeName.contains("BLOCK")) continue;
            
            // This is a solid ground block
            if (type.isSolid()) {
                return y;
            }
        }
        
        getLogger().warning(getMessage("system.chest-highest-solid-not-found",
            "%x%", String.valueOf(x),
            "%z%", String.valueOf(z)));
        return 64;
    }
    
    // Check if surface is valid for chest spawn
    private boolean isValidSurface(Material surface) {
        if (!surface.isSolid()) return false;
        if (surface == Material.BEDROCK) return false;
        if (surface == Material.BARRIER) return false;
        if (surface == Material.LAVA) return false;
        if (surface.toString().contains("LEAVES")) return false;
        if (surface.toString().contains("ICE")) return false;
        if (surface.toString().contains("WATER")) return false;
        if (surface.toString().contains("CACTUS")) return false;
        if (surface.toString().contains("MAGMA")) return false;
        return true;
    }

    private void spawnChestAtLocation(Location location, boolean resetTimer) {
        if (location == null || location.getWorld() == null) {
            getLogger().warning(getMessage("system.chest-invalid-world"));
            spawnInProgress = false;
            return;
        }
        
        World world = location.getWorld();
        ChestTier tier = determineChestTier();
        
        int x = location.getBlockX();
        int z = location.getBlockZ();
        
        // Ensure chunk is loaded
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            world.loadChunk(chunkX, chunkZ);
        }
        
        // ALWAYS find the highest solid block and spawn ON TOP of it
        int groundY = getHighestSolidBlock(world, x, z);
        boolean buryInGround = getConfig().getBoolean("settings.spawn-zone.bury-in-ground", false);
        
        // Chest Y position: on ground (groundY+1) or buried (groundY, replacing ground block)
        int chestY = buryInGround ? groundY : groundY + 1;
        
        getLogger().info(getMessage("system.chest-ground-level",
            "%x%", String.valueOf(x),
            "%y%", String.valueOf(chestY),
            "%z%", String.valueOf(z),
            "%ground%", String.valueOf(groundY)));
        
        Block chestBlock = world.getBlockAt(x, chestY, z);
        
        // Force clear space - replace ANY block with air first, then chest
        chestBlock.setType(Material.AIR, false);
        world.getBlockAt(x, chestY + 1, z).setType(Material.AIR, false); // Clear above for opening
        
        // Set chest
        chestBlock.setType(Material.CHEST, false);
        
        // Final location for effects/guardians (centered on block)
        Location chestLoc = new Location(world, x + 0.5, chestY, z + 0.5);
        
        // Schedule loot filling for next tick to ensure block state updates
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                Block block = world.getBlockAt(x, chestY, z);
                
                // Double check it's a chest, force if not
                if (block.getType() != Material.CHEST) {
                    getLogger().warning(getMessage("system.chest-block-not-chest"));
                    block.setType(Material.CHEST, false);
                }
                
                // Get state and fill with loot
                org.bukkit.block.BlockState state = block.getState();
                if (state instanceof Chest) {
                    Chest chest = (Chest) state;
                    fillChestWithLoot(chest.getBlockInventory(), tier);
                    getLogger().info(getMessage("system.chest-filled",
                        "%x%", String.valueOf(x),
                        "%y%", String.valueOf(chestY),
                        "%z%", String.valueOf(z)));
                } else {
                    getLogger().warning(getMessage("system.chest-state-error",
                        "%type%", state.getClass().getSimpleName()));
                }
                
                spawnGuardians(chestLoc, tier);
                createSpawnEffects(chestLoc, tier);
                
                // Broadcast with actual chest position (not +1, broadcastChestSpawn adds +1)
                broadcastChestSpawn(chestLoc, tier);
                
                // Track active chest
                ActiveChest activeChest = new ActiveChest(chestLoc, tier);
                activeChests.put(activeChest.chestKey, activeChest);
                
            } catch (Exception e) {
                getLogger().severe(getMessage("system.chest-spawn-error",
                    "%error%", e.getMessage()));
                e.printStackTrace();
            }
            
            if (resetTimer) {
                lastSpawnTime = System.currentTimeMillis();
                saveTimer();
            }
            spawnInProgress = false;
            
        }, 2L); // 2 tick delay for safety
    }
    
    private ChestTier determineChestTier() {
        double roll = random.nextDouble();
        double cumulative = 0.0;
        
        double commonChance = getConfig().getDouble("settings.chest-chances.common", 0.50);
        double rareChance = getConfig().getDouble("settings.chest-chances.rare", 0.35);
        
        cumulative += commonChance;
        if (roll <= cumulative) return ChestTier.COMMON;
        
        cumulative += rareChance;
        if (roll <= cumulative) return ChestTier.RARE;
        
        return ChestTier.LEGENDARY;
    }
    
    private void fillChestWithLoot(Inventory inventory, ChestTier tier) {
        List<ItemStack> loot;
        
        // Check if custom loot is enabled for this tier
        String tierName = tier.name().toLowerCase();
        if (customLootManager != null && customLootManager.isCustomLootEnabled(tierName)) {
            // Use custom loot (1/3 of saved items, randomly selected)
            loot = customLootManager.getRandomCustomLoot(tierName);
            getLogger().info(getMessage("system.custom-loot-using",
                "%tier%", tierName,
                "%count%", String.valueOf(loot.size())));
        } else {
            // Use default generated loot
            loot = generateLoot(tier);
            Collections.shuffle(loot);
        }
        
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots);
        
        int itemsToPlace;
        if (customLootManager != null && customLootManager.isCustomLootEnabled(tierName)) {
            // For custom loot, place all selected items
            itemsToPlace = loot.size();
        } else {
            // For default loot, limit items
            itemsToPlace = Math.min(loot.size(), 
                tier == ChestTier.LEGENDARY ? 20 : 
                tier == ChestTier.RARE ? 15 : 12);
        }
        
        for (int i = 0; i < itemsToPlace && i < slots.size(); i++) {
            inventory.setItem(slots.get(i), loot.get(i));
        }
    }
    
    private List<ItemStack> generateLoot(ChestTier tier) {
        List<ItemStack> loot = new ArrayList<>();
        
        if (isFeatureEnabled("loot.basic-items.enabled")) {
            loot.addAll(getBasicItems(tier));
        }
        if (isFeatureEnabled("loot.weapons-and-armor.enabled")) {
            loot.addAll(getWeaponsAndArmor(tier));
        }
        if (isFeatureEnabled("loot.tools.enabled")) {
            loot.addAll(getTools(tier));
        }
        if (isFeatureEnabled("loot.potions.enabled")) {
            loot.addAll(getPotions(tier));
        }
        if (isFeatureEnabled("loot.food.enabled")) {
            loot.addAll(getFood(tier));
        }
        
        // Custom potions
        if (isFeatureEnabled("custom-potions.enabled")) {
            loot.addAll(getCustomPotions(tier));
        }
        
        if ((tier == ChestTier.RARE || tier == ChestTier.LEGENDARY) && isFeatureEnabled("loot.unique-items.enabled")) {
            loot.addAll(getUniqueItems(tier));
        }
        
        if (tier == ChestTier.LEGENDARY) {
            loot.addAll(getLegendaryItems());
            
            // Summoner Apple chance
            if (isFeatureEnabled("summoner-apple.enabled") && 
                getConfig().getBoolean("summoner-apple.drop-in-chests", true)) {
                double dropChance = getConfig().getDouble("summoner-apple.drop-chance", 0.05);
                if (random.nextDouble() < dropChance) {
                    loot.add(createSummonerApple());
                }
            }
        }
        
        return loot;
    }
    
// ==================== LOOT GENERATION ====================
    
    private List<ItemStack> getBasicItems(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        
        // Базовые материалы (доступны во всех версиях)
        List<Material> basics = new ArrayList<>(Arrays.asList(
            Material.DIAMOND, Material.EMERALD, Material.IRON_INGOT, Material.GOLD_INGOT,
            Material.NETHERITE_SCRAP, Material.ANCIENT_DEBRIS, Material.EXPERIENCE_BOTTLE,
            Material.ENDER_PEARL, Material.NAME_TAG, Material.SADDLE, Material.TOTEM_OF_UNDYING,
            Material.LAPIS_LAZULI, Material.REDSTONE, Material.COAL, Material.COPPER_INGOT,
            Material.BLAZE_ROD, Material.ENDER_EYE, Material.SLIME_BALL, Material.SHULKER_SHELL,
            Material.PRISMARINE_SHARD, Material.PRISMARINE_CRYSTALS, Material.RABBIT_FOOT,
            Material.GHAST_TEAR, Material.MAGMA_CREAM, Material.NETHER_STAR, Material.DRAGON_BREATH,
            Material.PHANTOM_MEMBRANE, Material.HEART_OF_THE_SEA, Material.NAUTILUS_SHELL
        ));
        
        // Добавляем материалы из новых версий, если они доступны
        addMaterialIfExists(basics, "ECHO_SHARD");           // MC 1.19+
        addMaterialIfExists(basics, "RECOVERY_COMPASS");     // MC 1.19+
        addMaterialIfExists(basics, "DISC_FRAGMENT_5");      // MC 1.19+
        
        int count = getConfig().getInt("loot.basic-items." + tier.configKey + "-count", 
            tier == ChestTier.LEGENDARY ? 8 : tier == ChestTier.RARE ? 5 : 3);
        
        for (int i = 0; i < count; i++) {
            Material mat = basics.get(random.nextInt(basics.size()));
            int amount = tier == ChestTier.LEGENDARY ? 3 + random.nextInt(5) : 
                        tier == ChestTier.RARE ? 2 + random.nextInt(3) : 1 + random.nextInt(2);
            items.add(new ItemStack(mat, Math.min(amount, mat.getMaxStackSize())));
        }
        
        return items;
    }

    /**
     * Add material to list if it exists in this MC version
     */
    private void addMaterialIfExists(List<Material> list, String materialName) {
        try {
            Material mat = Material.valueOf(materialName);
            list.add(mat);
        } catch (IllegalArgumentException e) {
            // Material doesn't exist in this version, skip
        }
    }
    
    private List<ItemStack> getWeaponsAndArmor(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        
        Material[] weapons = {
            Material.DIAMOND_SWORD, Material.IRON_SWORD, Material.GOLDEN_SWORD,
            Material.NETHERITE_SWORD, Material.BOW, Material.CROSSBOW, Material.TRIDENT,
            Material.DIAMOND_AXE, Material.IRON_AXE, Material.NETHERITE_AXE
        };
        
        Material[] armor = {
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
            Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS
        };
        
        boolean shouldEnchant = (tier == ChestTier.RARE && isFeatureEnabled("loot.weapons-and-armor.enchant-in-rare")) ||
                               (tier == ChestTier.LEGENDARY && isFeatureEnabled("loot.weapons-and-armor.enchant-in-legendary"));
        
        for (int i = 0; i < (tier == ChestTier.LEGENDARY ? 3 : 2); i++) {
            Material weapon = weapons[random.nextInt(weapons.length)];
            ItemStack item = new ItemStack(weapon);
            if (shouldEnchant) {
                item = enchantItem(item, tier);
            }
            items.add(item);
        }
        
        for (int i = 0; i < (tier == ChestTier.LEGENDARY ? 4 : tier == ChestTier.RARE ? 2 : 1); i++) {
            Material armorPiece = armor[random.nextInt(armor.length)];
            ItemStack item = new ItemStack(armorPiece);
            if (shouldEnchant) {
                item = enchantItem(item, tier);
            }
            items.add(item);
        }
        
        return items;
    }
    
    private List<ItemStack> getTools(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        Material[] tools = {
            Material.DIAMOND_PICKAXE, Material.IRON_PICKAXE, Material.GOLDEN_PICKAXE, Material.NETHERITE_PICKAXE,
            Material.DIAMOND_SHOVEL, Material.IRON_SHOVEL, Material.GOLDEN_SHOVEL, Material.NETHERITE_SHOVEL,
            Material.DIAMOND_HOE, Material.IRON_HOE, Material.GOLDEN_HOE, Material.NETHERITE_HOE,
            Material.FISHING_ROD, Material.SHEARS, Material.FLINT_AND_STEEL, Material.COMPASS, Material.CLOCK,
            Material.SPYGLASS, Material.LEAD, Material.CARROT_ON_A_STICK, Material.WARPED_FUNGUS_ON_A_STICK
        };
        
        double enchantChance = getConfig().getDouble("loot.tools.enchant-chance", 0.7);
        
        int count = tier == ChestTier.LEGENDARY ? 4 : tier == ChestTier.RARE ? 3 : 2;
        for (int i = 0; i < count; i++) {
            Material tool = tools[random.nextInt(tools.length)];
            ItemStack item = new ItemStack(tool);
            if (tier != ChestTier.COMMON && random.nextDouble() < enchantChance) {
                item = enchantItem(item, tier);
            }
            items.add(item);
        }
        
        return items;
    }
    
    private List<ItemStack> getPotions(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        
        items.add(createPotion(PotionEffectType.SPEED, 3600, 1));
        items.add(createPotion(PotionEffectType.INCREASE_DAMAGE, 3600, 1));
        items.add(createPotion(PotionEffectType.REGENERATION, 900, 1));
        items.add(createPotion(PotionEffectType.FIRE_RESISTANCE, 3600, 0));
        items.add(createPotion(PotionEffectType.NIGHT_VISION, 3600, 0));
        
        if (tier == ChestTier.RARE || tier == ChestTier.LEGENDARY) {
            items.add(createPotion(PotionEffectType.INCREASE_DAMAGE, 1800, 2));
            items.add(createPotion(PotionEffectType.SPEED, 1800, 2));
            items.add(createPotion(PotionEffectType.JUMP, 3600, 2));
        }
        
        if (tier == ChestTier.LEGENDARY) {
            items.add(createPotion(PotionEffectType.REGENERATION, 400, 2));
            items.add(createPotion(PotionEffectType.ABSORPTION, 2400, 1));
        }
        
        return items;
    }
    
    private List<ItemStack> getCustomPotions(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        
        // Strength III
        if (isFeatureEnabled("custom-potions.potions.strength-3.enabled")) {
            boolean canDrop = (tier == ChestTier.RARE && getConfig().getBoolean("custom-potions.potions.strength-3.drop-in-rare", true)) ||
                             (tier == ChestTier.LEGENDARY && getConfig().getBoolean("custom-potions.potions.strength-3.drop-in-legendary", true));
            if (canDrop) {
                items.add(createCustomPotion(
                    getMessage("potions.strength-3.name"),
                    PotionEffectType.INCREASE_DAMAGE, 2, 
                    getConfig().getInt("custom-potions.potions.strength-3.duration-seconds", 120) * 20));
            }
        }
        
        // Haste II
        if (isFeatureEnabled("custom-potions.potions.haste-2.enabled")) {
            boolean canDrop = (tier == ChestTier.RARE && getConfig().getBoolean("custom-potions.potions.haste-2.drop-in-rare", true)) ||
                             (tier == ChestTier.LEGENDARY && getConfig().getBoolean("custom-potions.potions.haste-2.drop-in-legendary", true));
            if (canDrop) {
                items.add(createCustomPotion(
                    getMessage("potions.haste-2.name"),
                    PotionEffectType.FAST_DIGGING, 1,
                    getConfig().getInt("custom-potions.potions.haste-2.duration-seconds", 180) * 20));
            }
        }
        
        // Luck II
        if (tier == ChestTier.LEGENDARY && isFeatureEnabled("custom-potions.potions.luck-2.enabled")) {
            items.add(createCustomPotion(
                getMessage("potions.luck-2.name"),
                PotionEffectType.LUCK, 1,
                getConfig().getInt("custom-potions.potions.luck-2.duration-seconds", 300) * 20));
        }
        
        // Dolphin's Grace
        if (isFeatureEnabled("custom-potions.potions.dolphins-grace.enabled")) {
            boolean canDrop = (tier == ChestTier.RARE && getConfig().getBoolean("custom-potions.potions.dolphins-grace.drop-in-rare", true)) ||
                             (tier == ChestTier.LEGENDARY && getConfig().getBoolean("custom-potions.potions.dolphins-grace.drop-in-legendary", true));
            if (canDrop) {
                items.add(createCustomPotion(
                    getMessage("potions.dolphins-grace.name"),
                    PotionEffectType.DOLPHINS_GRACE, 0,
                    getConfig().getInt("custom-potions.potions.dolphins-grace.duration-seconds", 180) * 20));
            }
        }
        
        // Turtle Master Extended (multi-effect)
        if (tier == ChestTier.LEGENDARY && isFeatureEnabled("custom-potions.potions.turtle-master-extended.enabled")) {
            ItemStack potion = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) potion.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(getMessage("potions.turtle-master.name"));
                meta.addCustomEffect(new PotionEffect(PotionEffectType.SLOW, 1200, 3), true);
                meta.addCustomEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 1200, 2), true);
                meta.getPersistentDataContainer().set(CUSTOM_POTION_KEY, PersistentDataType.STRING, "turtle_master");
                meta.setLore(getMessageList("potions.turtle-master.lore"));
                potion.setItemMeta(meta);
            }
            items.add(potion);
        }
        
        // Wither Resistance (multi-effect)
        if (tier == ChestTier.LEGENDARY && isFeatureEnabled("custom-potions.potions.wither-resistance.enabled")) {
            ItemStack potion = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) potion.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(getMessage("potions.wither-resistance.name"));
                meta.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION, 600, 1), true);
                meta.addCustomEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 1200, 1), true);
                meta.getPersistentDataContainer().set(CUSTOM_POTION_KEY, PersistentDataType.STRING, "wither_resistance");
                meta.setLore(getMessageList("potions.wither-resistance.lore"));
                potion.setItemMeta(meta);
            }
            items.add(potion);
        }
        
        return items;
    }
    
    private ItemStack createCustomPotion(String name, PotionEffectType effect, int amplifier, int duration) {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.addCustomEffect(new PotionEffect(effect, duration, amplifier), true);
            meta.getPersistentDataContainer().set(CUSTOM_POTION_KEY, PersistentDataType.STRING, effect.getKey().getKey());
            meta.setLore(Arrays.asList(
                "§7" + effect.getKey().getKey() + " " + (amplifier + 1),
                "§7Duration: " + (duration / 20) + "s",
                "§6Custom Potion"
            ));
            potion.setItemMeta(meta);
        }
        return potion;
    }
    
    private List<ItemStack> getFood(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        Material[] foods = {
            Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE, Material.GOLDEN_CARROT,
            Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN,
            Material.COOKED_SALMON, Material.COOKED_COD, Material.BREAD, Material.CAKE,
            Material.PUMPKIN_PIE, Material.COOKIE, Material.HONEY_BOTTLE, Material.SUSPICIOUS_STEW
        };
        
        int count = tier == ChestTier.LEGENDARY ? 6 : tier == ChestTier.RARE ? 4 : 2;
        for (int i = 0; i < count; i++) {
            Material food = foods[random.nextInt(foods.length)];
            int amount = random.nextInt(3) + 1;
            items.add(new ItemStack(food, Math.min(amount, food.getMaxStackSize())));
        }
        
        return items;
    }
    
    private List<ItemStack> getUniqueItems(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        
        items.add(new ItemStack(Material.BEACON, 1));
        items.add(new ItemStack(Material.CONDUIT, 1));
        items.add(new ItemStack(Material.ELYTRA, 1));
        items.add(new ItemStack(Material.MUSIC_DISC_PIGSTEP, 1));
        items.add(new ItemStack(Material.SPAWNER, 1));
        items.add(new ItemStack(Material.SPONGE, random.nextInt(5) + 1));
        items.add(new ItemStack(Material.WET_SPONGE, random.nextInt(3) + 1));
        items.add(new ItemStack(Material.OBSIDIAN, random.nextInt(10) + 5));
        items.add(new ItemStack(Material.END_STONE, random.nextInt(20) + 10));
        items.add(new ItemStack(Material.NETHER_STAR, 1));
        
        return items;
    }
    
    private List<ItemStack> getLegendaryItems() {
        List<ItemStack> items = new ArrayList<>();
        
        if (!isLegendaryItemsEnabled()) {
            return items;
        }
        
        if (isItemEnabled("dragon-slayer-sword") && canDropInChest("dragon-slayer-sword")) {
            items.add(createLegendarySword());
        }
        if (isItemEnabled("master-pickaxe") && canDropInChest("master-pickaxe")) {
            items.add(createMasterPickaxe());
        }
        if (isItemEnabled("titan-axe") && canDropInChest("titan-axe")) {
            items.add(createTitanAxe());
        }
        if (isItemEnabled("void-shovel") && canDropInChest("void-shovel")) {
            items.add(createVoidShovel());
        }
        if (isItemEnabled("storm-hammer") && canDropInChest("storm-hammer")) {
            items.add(createStormHammer());
        }
        if (isItemEnabled("guardian-bow") && canDropInChest("guardian-bow")) {
            items.add(createGuardianBow());
        }
        if (isItemEnabled("wisdom-book") && canDropInChest("wisdom-book")) {
            items.add(createWisdomBook());
        }
        if (isItemEnabled("phoenix-feather") && canDropInChest("phoenix-feather")) {
            items.add(createPhoenixFeather());
        }
        if (isItemEnabled("poseidon-trident") && canDropInChest("poseidon-trident")) {
            items.add(createPoseidonTrident());
        }
        
        return items;
    }

    private ItemStack enchantItem(ItemStack item, ChestTier tier) {
        String itemType = item.getType().toString();
        
        int commonMax = getConfig().getInt("enchantments.common-max-level", 3);
        int rareMax = getConfig().getInt("enchantments.rare-max-level", 5);
        int legendaryMax = getConfig().getInt("enchantments.legendary-max-level", 6);
        
        int maxLevel = tier == ChestTier.LEGENDARY ? legendaryMax : tier == ChestTier.RARE ? rareMax : commonMax;
        
        if (itemType.contains("SWORD")) {
            item.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.DURABILITY, random.nextInt(5) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, random.nextInt(2) + 1);
                item.addUnsafeEnchantment(Enchantment.LOOT_BONUS_MOBS, random.nextInt(4) + 1);
                try {
                    item.addUnsafeEnchantment(Enchantment.SWEEPING_EDGE, random.nextInt(3) + 1);
                } catch (NoSuchFieldError e) {
                    // Old version without sweeping edge
                }
            }
        } else if (itemType.contains("BOW")) {
            item.addUnsafeEnchantment(Enchantment.ARROW_DAMAGE, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.ARROW_KNOCKBACK, random.nextInt(2) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.ARROW_FIRE, 1);
                if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.ARROW_INFINITE, 1);
            }
        } else if (itemType.contains("PICKAXE")) {
            item.addUnsafeEnchantment(Enchantment.DIG_SPEED, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.LOOT_BONUS_BLOCKS, random.nextInt(4) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.DURABILITY, random.nextInt(5) + 1);
                item.addUnsafeEnchantment(Enchantment.MENDING, 1);
            }
        } else if (itemType.contains("AXE")) {
            item.addUnsafeEnchantment(Enchantment.DIG_SPEED, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, random.nextInt(maxLevel) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.DURABILITY, random.nextInt(5) + 1);
                item.addUnsafeEnchantment(Enchantment.LOOT_BONUS_BLOCKS, random.nextInt(3) + 1);
            }
        } else if (itemType.contains("SHOVEL")) {
            item.addUnsafeEnchantment(Enchantment.DIG_SPEED, random.nextInt(maxLevel) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.DURABILITY, random.nextInt(5) + 1);
                item.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1);
            }
        } else if (itemType.contains("HELMET") || itemType.contains("CHESTPLATE") || 
                   itemType.contains("LEGGINGS") || itemType.contains("BOOTS")) {
            item.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.DURABILITY, random.nextInt(5) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.MENDING, 1);
                if (itemType.contains("BOOTS")) {
                    item.addUnsafeEnchantment(Enchantment.PROTECTION_FALL, random.nextInt(5) + 1);
                    item.addUnsafeEnchantment(Enchantment.DEPTH_STRIDER, random.nextInt(3) + 1);
                }
                if (itemType.contains("HELMET")) {
                    item.addUnsafeEnchantment(Enchantment.WATER_WORKER, 1);
                    item.addUnsafeEnchantment(Enchantment.OXYGEN, random.nextInt(4) + 1);
                }
            }
        }
        
        return item;
    }
    
    private ItemStack createPotion(PotionEffectType effect, int duration, int amplifier) {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        if (meta != null) {
            meta.addCustomEffect(new PotionEffect(effect, duration, amplifier), true);
            potion.setItemMeta(meta);
        }
        return potion;
    }

    // ==================== LEGENDARY ITEMS CREATION ====================

    private ItemStack createLegendarySword() {
        ItemStack sword = new ItemStack(versionAdapter.getBestSwordMaterial());
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.dragon-slayer-sword.name"));
            meta.setLore(getLoreList("items.dragon-slayer-sword.lore",
                "%damage%", String.valueOf(getSwordBonusDamage()),
                "%cooldown%", String.valueOf(getSwordCooldown() / 1000)));
            
            meta.addEnchant(Enchantment.DAMAGE_ALL, 6, true);
            meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
            meta.addEnchant(Enchantment.DURABILITY, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.addEnchant(Enchantment.LOOT_BONUS_MOBS, 4, true);
            
            try {
                meta.addEnchant(Enchantment.SWEEPING_EDGE, 4, true);
            } catch (NoSuchFieldError e) {
                // Old version, skip sweeping
            }
            
            meta.getPersistentDataContainer().set(LEGENDARY_SWORD_KEY, PersistentDataType.STRING, "true");
            sword.setItemMeta(meta);
        }
        return sword;
    }

    private ItemStack createMasterPickaxe() {
        ItemStack pickaxe = new ItemStack(versionAdapter.getBestPickaxeMaterial());
        ItemMeta meta = pickaxe.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.master-pickaxe.name"));
            meta.setLore(getLoreList("items.master-pickaxe.lore",
                "%cooldown%", String.valueOf(getPickaxeCooldown() / 1000)));
            
            meta.addEnchant(Enchantment.DIG_SPEED, 6, true);
            meta.addEnchant(Enchantment.LOOT_BONUS_BLOCKS, 4, true);
            meta.addEnchant(Enchantment.DURABILITY, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            
            meta.getPersistentDataContainer().set(MASTER_PICKAXE_KEY, PersistentDataType.STRING, "true");
            pickaxe.setItemMeta(meta);
        }
        return pickaxe;
    }

    private ItemStack createTitanAxe() {
        ItemStack axe = new ItemStack(versionAdapter.getBestAxeMaterial());
        ItemMeta meta = axe.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.titan-axe.name"));
            meta.setLore(getLoreList("items.titan-axe.lore",
                "%damage%", String.valueOf(getAxeBonusDamage()),
                "%tree_cooldown%", String.valueOf(getTreeCooldown() / 1000),
                "%combat_cooldown%", String.valueOf(getAxeCooldown() / 1000)));
            
            meta.addEnchant(Enchantment.DIG_SPEED, 6, true);
            meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
            meta.addEnchant(Enchantment.DURABILITY, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            
            meta.getPersistentDataContainer().set(TITAN_AXE_KEY, PersistentDataType.STRING, "true");
            axe.setItemMeta(meta);
        }
        return axe;
    }

    private ItemStack createVoidShovel() {
        ItemStack shovel = new ItemStack(versionAdapter.getBestShovelMaterial());
        ItemMeta meta = shovel.getItemMeta();
        if (meta != null) {
            int areaSize = getConfig().getInt("legendary-items.void-shovel.area-size", 3);
            meta.setDisplayName(getMessage("items.void-shovel.name"));
            meta.setLore(getLoreList("items.void-shovel.lore",
                "%size%", String.valueOf(areaSize),
                "%cooldown%", String.valueOf(getShovelCooldown() / 1000)));
            
            meta.addEnchant(Enchantment.DIG_SPEED, 7, true);
            meta.addEnchant(Enchantment.DURABILITY, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.addEnchant(Enchantment.SILK_TOUCH, 1, true);
            
            meta.getPersistentDataContainer().set(VOID_SHOVEL_KEY, PersistentDataType.STRING, "true");
            shovel.setItemMeta(meta);
        }
        return shovel;
    }

    private ItemStack createStormHammer() {
        ItemStack hammer = new ItemStack(versionAdapter.getBestAxeMaterial());
        ItemMeta meta = hammer.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.storm-hammer.name"));
            meta.setLore(getLoreList("items.storm-hammer.lore",
                "%damage%", String.valueOf(getHammerBonusDamage()),
                "%cooldown%", String.valueOf(getHammerCooldown() / 1000)));
            
            meta.addEnchant(Enchantment.DAMAGE_ALL, 6, true);
            meta.addEnchant(Enchantment.DURABILITY, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.addEnchant(Enchantment.KNOCKBACK, 3, true);
            
            meta.getPersistentDataContainer().set(STORM_HAMMER_KEY, PersistentDataType.STRING, "true");
            hammer.setItemMeta(meta);
        }
        return hammer;
    }

    private ItemStack createGuardianBow() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.guardian-bow.name"));
            meta.setLore(getLoreList("items.guardian-bow.lore",
                "%cooldown%", String.valueOf(getBowCooldown() / 1000)));
            
            meta.addEnchant(Enchantment.ARROW_DAMAGE, 6, true);
            meta.addEnchant(Enchantment.ARROW_INFINITE, 1, true);
            meta.addEnchant(Enchantment.ARROW_KNOCKBACK, 2, true);
            meta.addEnchant(Enchantment.ARROW_FIRE, 1, true);
            meta.addEnchant(Enchantment.DURABILITY, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            
            meta.getPersistentDataContainer().set(GUARDIAN_BOW_KEY, PersistentDataType.STRING, "true");
            bow.setItemMeta(meta);
        }
        return bow;
    }

    private ItemStack createWisdomBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.wisdom-book.name"));
            meta.setLore(getLoreList("items.wisdom-book.lore"));
            
            meta.addStoredEnchant(Enchantment.MENDING, 1, true);
            meta.addStoredEnchant(Enchantment.DURABILITY, 3, true);
            meta.addStoredEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 4, true);
            meta.addStoredEnchant(Enchantment.DAMAGE_ALL, 5, true);
            meta.addStoredEnchant(Enchantment.DIG_SPEED, 5, true);
            meta.addStoredEnchant(Enchantment.LOOT_BONUS_BLOCKS, 3, true);
            meta.addStoredEnchant(Enchantment.LOOT_BONUS_MOBS, 3, true);
            
            meta.getPersistentDataContainer().set(WISDOM_BOOK_KEY, PersistentDataType.STRING, "true");
            book.setItemMeta(meta);
        }
        return book;
    }

    private ItemStack createPhoenixFeather() {
        ItemStack feather = new ItemStack(Material.FEATHER);
        ItemMeta meta = feather.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.phoenix-feather.name"));
            meta.setLore(getLoreList("items.phoenix-feather.lore",
                "%cooldown%", String.valueOf(getConfig().getInt("legendary-items.phoenix-feather.cooldown-ms", 300000) / 1000)));
            
            meta.getPersistentDataContainer().set(PHOENIX_FEATHER_KEY, PersistentDataType.STRING, "true");
            feather.setItemMeta(meta);
        }
        return feather;
    }

    private ItemStack createPoseidonTrident() {
        ItemStack trident = new ItemStack(Material.TRIDENT);
        ItemMeta meta = trident.getItemMeta();
        if (meta != null) {
            int rainRadius = getConfig().getInt("legendary-items.poseidon-trident.rain-radius", 5);
            meta.setDisplayName(getMessage("items.poseidon-trident.name"));
            meta.setLore(getLoreList("items.poseidon-trident.lore",
                "%radius%", String.valueOf(rainRadius),
                "%damage%", String.valueOf(getTridentLightningDamage()),
                "%cooldown%", String.valueOf(getTridentCooldown() / 1000)));
            
            meta.addEnchant(Enchantment.IMPALING, 5, true);
            meta.addEnchant(Enchantment.LOYALTY, 3, true);
            meta.addEnchant(Enchantment.CHANNELING, 1, true);
            meta.addEnchant(Enchantment.DURABILITY, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.addEnchant(Enchantment.RIPTIDE, 3, true);
            
            meta.getPersistentDataContainer().set(POSEIDON_TRIDENT_KEY, PersistentDataType.STRING, "true");
            trident.setItemMeta(meta);
        }
        return trident;
    }

    private ItemStack createSummonerApple() {
        ItemStack apple = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta meta = apple.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(getMessage("items.summoner-apple.name"));
            meta.setLore(getLoreList("items.summoner-apple.lore"));
            meta.getPersistentDataContainer().set(SUMMONER_APPLE_KEY, PersistentDataType.STRING, "true");
            apple.setItemMeta(meta);
        }
        return apple;
    }
    
// ==================== EVENT HANDLERS ====================
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (isStatsEnabled()) {
            Player player = event.getPlayer();
            playerStats.getStats(player.getUniqueId(), player.getName());
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (isStatsEnabled()) {
            playerStats.saveStats(event.getPlayer().getUniqueId());
        }
    }
    
    @EventHandler
    public void onPlayerEat(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        
        // Summoner Apple
        if (hasCustomKey(item, SUMMONER_APPLE_KEY)) {
            event.setCancelled(true);
            
            // Remove one apple
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
            
            // Spawn chest without resetting timer
            broadcastMessage("broadcasts.apple-used", "%player%", player.getName());
            
            if (isStatsEnabled()) {
                playerStats.incrementApplesUsed(player.getUniqueId(), player.getName());
            }
            
            // Effects
            if (isSoundsEnabled()) {
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 1.0f, 1.0f);
            }
            if (isParticlesEnabled()) {
                player.getWorld().spawnParticle(Particle.TOTEM, player.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.1);
            }
            
            // Spawn chest in player's world
            spawnChest(false, player.getWorld());
        }
    }
    
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        
        Player attacker = (Player) event.getDamager();
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        UUID playerId = attacker.getUniqueId();
        
        // PvP Protection check
        if (isPvpProtection() && event.getEntity() instanceof Player) {
            return;
        }
        
        if (!(event.getEntity() instanceof LivingEntity)) return;
        LivingEntity target = (LivingEntity) event.getEntity();
        
        // Dragon Slayer Sword
        if (isLegendarySword(weapon) && isItemEnabled("dragon-slayer-sword")) {
            long cooldown = getSwordCooldown();
            
            if (isOnCooldown(swordCooldown, playerId, cooldown)) {
                if (isFeatureEnabled("abilities.show-cooldown-messages")) {
                    long remaining = getRemainingCooldown(swordCooldown, playerId, cooldown);
                    sendMessage(attacker, "cooldowns.sword-recharging", "%time%", formatCooldown(remaining));
                }
                return;
            }
            
            event.setDamage(event.getDamage() + getSwordBonusDamage());
            setCooldown(swordCooldown, playerId);
            
            int fireTicks = getConfig().getInt("legendary-items.dragon-slayer-sword.fire-duration-ticks", 100);
            target.setFireTicks(fireTicks);
            
            if (isParticlesEnabled()) {
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation(), 8, 0.3, 0.3, 0.3, 0.05);
            }
            if (isSoundsEnabled()) {
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_BLAZE_HURT, 0.8f, 1.0f);
            }
            
            if (isFeatureEnabled("abilities.show-activation-messages")) {
                sendMessage(attacker, "abilities.sword-activated-cooldown", 
                    "%cooldown%", String.valueOf(cooldown / 1000));
            }
        }
        
        // Titan Axe
        if (isTitanAxe(weapon) && isItemEnabled("titan-axe")) {
            long cooldown = getAxeCooldown();
            
            if (isOnCooldown(axeCooldown, playerId, cooldown)) {
                if (isFeatureEnabled("abilities.show-cooldown-messages")) {
                    long remaining = getRemainingCooldown(axeCooldown, playerId, cooldown);
                    sendMessage(attacker, "cooldowns.axe-recharging", "%time%", formatCooldown(remaining));
                }
                return;
            }
            
            event.setDamage(event.getDamage() + getAxeBonusDamage());
            setCooldown(axeCooldown, playerId);
            
            if (isParticlesEnabled()) {
                target.getWorld().spawnParticle(Particle.BLOCK_CRACK, target.getLocation(), 8, 0.3, 0.3, 0.3, 0.1, Material.STONE.createBlockData());
            }
            if (isSoundsEnabled()) {
                target.getWorld().playSound(target.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.0f);
            }
            
            if (isFeatureEnabled("abilities.show-activation-messages")) {
                sendMessage(attacker, "abilities.axe-combat-cooldown", 
                    "%cooldown%", String.valueOf(cooldown / 1000));
            }
        }
        
        // Storm Hammer
        if (isStormHammer(weapon) && isItemEnabled("storm-hammer")) {
            long cooldown = getHammerCooldown();
            
            if (isOnCooldown(hammerCooldown, playerId, cooldown)) {
                if (isFeatureEnabled("abilities.show-cooldown-messages")) {
                    long remaining = getRemainingCooldown(hammerCooldown, playerId, cooldown);
                    sendMessage(attacker, "cooldowns.hammer-recharging", "%time%", formatCooldown(remaining));
                }
                return;
            }
            
            event.setDamage(event.getDamage() + getHammerBonusDamage());
            setCooldown(hammerCooldown, playerId);
            
            Location loc = target.getLocation();
            
            // Lightning effect
            if (getConfig().getBoolean("legendary-items.storm-hammer.lightning-effect", true)) {
                target.getWorld().strikeLightningEffect(loc);
            }
            
            // Area damage
            if (getConfig().getBoolean("legendary-items.storm-hammer.area-damage", true)) {
                double radius = getConfig().getDouble("legendary-items.storm-hammer.area-damage-radius", 3);
                double areaDamage = getHammerAreaDamage();
                
                for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
                    if (!(entity instanceof LivingEntity)) continue;
                    LivingEntity nearby = (LivingEntity) entity;
                    
                    if (nearby != attacker && nearby != target) {
                        if (isPvpProtection() && nearby instanceof Player) continue;
                        
                        double newHealth = nearby.getHealth() - areaDamage;
                        nearby.setHealth(Math.max(0.1, newHealth));
                    }
                }
            }
            
            if (isParticlesEnabled()) {
                target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, loc, 15, 1, 1, 1, 0.1);
            }
            if (isSoundsEnabled()) {
                target.getWorld().playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.0f);
            }
            
            if (isFeatureEnabled("abilities.show-activation-messages")) {
                sendMessage(attacker, "abilities.hammer-activated-cooldown", 
                    "%cooldown%", String.valueOf(cooldown / 1000));
            }
        }
        
        // Poseidon Trident
        if (isPoseidonTrident(weapon) && isItemEnabled("poseidon-trident")) {
            if (getConfig().getBoolean("legendary-items.poseidon-trident.lightning-on-hit", true)) {
                long cooldown = getTridentCooldown();
                
                if (!isOnCooldown(tridentCooldown, playerId, cooldown)) {
                    setCooldown(tridentCooldown, playerId);
                    
                    target.getWorld().strikeLightningEffect(target.getLocation());
                    target.damage(getTridentLightningDamage(), attacker);
                    
                    if (isParticlesEnabled()) {
                        target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, target.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
                    }
                    
                    if (isFeatureEnabled("abilities.show-activation-messages")) {
                        sendMessage(attacker, "abilities.trident-lightning-cooldown", 
                            "%cooldown%", String.valueOf(cooldown / 1000));
                    }
                } else if (isFeatureEnabled("abilities.show-cooldown-messages")) {
                    long remaining = getRemainingCooldown(tridentCooldown, playerId, cooldown);
                    sendMessage(attacker, "cooldowns.trident-recharging", "%time%", formatCooldown(remaining));
                }
            }
        }
    }
    
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        
        // Check if it's a guardian
        if (entity.getPersistentDataContainer().has(GUARDIAN_MOB_KEY, PersistentDataType.STRING)) {
            if (killer != null && isStatsEnabled()) {
                playerStats.incrementGuardiansKilled(killer.getUniqueId(), killer.getName());
                checkAchievements(killer);
            }
        }
    }
    
    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        
        Player shooter = (Player) event.getEntity();
        ItemStack bow = event.getBow();
        UUID playerId = shooter.getUniqueId();
        
        if (bow == null || !isGuardianBow(bow) || !isItemEnabled("guardian-bow")) return;
        
        long cooldown = getBowCooldown();
        
        if (isOnCooldown(bowCooldown, playerId, cooldown)) {
            if (isFeatureEnabled("abilities.show-cooldown-messages")) {
                long remaining = getRemainingCooldown(bowCooldown, playerId, cooldown);
                sendMessage(shooter, "cooldowns.bow-recharging", "%time%", formatCooldown(remaining));
            }
            event.setCancelled(true);
            return;
        }
        
        setCooldown(bowCooldown, playerId);
        
        // Double shot
        if (getConfig().getBoolean("legendary-items.guardian-bow.double-shot", true)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Arrow arrow = shooter.launchProjectile(Arrow.class);
                    arrow.setVelocity(event.getProjectile().getVelocity().clone().rotateAroundY(Math.toRadians(10)));
                    arrow.setCritical(true);
                    arrow.setFireTicks(100);
                    
                    // Homing arrows
                    if (getConfig().getBoolean("legendary-items.guardian-bow.homing-arrows", true)) {
                        double homingRange = getConfig().getDouble("legendary-items.guardian-bow.homing-range", 5.0);
                        new ArrowHomingTask(arrow, shooter, homingRange).runTaskTimer(SpawnChestPlugin.this, 1L, 1L);
                    }
                }
            }.runTaskLater(this, 1L);
        }
        
        if (isFeatureEnabled("abilities.show-activation-messages")) {
            sendMessage(shooter, "abilities.bow-double-shot-cooldown", 
                "%cooldown%", String.valueOf(cooldown / 1000));
        }
    }
    
    @EventHandler 
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        Block block = event.getBlock();
        UUID playerId = player.getUniqueId();
        
        // Master Pickaxe
        if (isMasterPickaxe(tool) && isItemEnabled("master-pickaxe")) {
            long cooldown = getPickaxeCooldown();
            
            if (isOnCooldown(pickaxeCooldown, playerId, cooldown)) {
                return;
            }
            
            Material blockType = block.getType();
            
            if (isOre(blockType)) {
                setCooldown(pickaxeCooldown, playerId);
                
                // Auto-smelt
                if (getConfig().getBoolean("legendary-items.master-pickaxe.auto-smelt", true)) {
                    event.setDropItems(false);
                    
                    ItemStack smelted = getSmeltedVersion(blockType);
                    if (smelted != null) {
                        double doubleChance = getConfig().getDouble("legendary-items.master-pickaxe.double-drop-chance", 0.5);
                        int amount = random.nextDouble() < doubleChance ? 2 : 1;
                        smelted.setAmount(amount);
                        block.getWorld().dropItemNaturally(block.getLocation(), smelted);
                        
                        if (isParticlesEnabled()) {
                            block.getWorld().spawnParticle(Particle.FLAME, block.getLocation().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2, 0.05);
                        }
                    } else {
                        Collection<ItemStack> normalDrops = block.getDrops(tool);
                        for (ItemStack drop : normalDrops) {
                            double doubleChance = getConfig().getDouble("legendary-items.master-pickaxe.double-drop-chance", 0.5);
                            int amount = drop.getAmount() * (random.nextDouble() < doubleChance ? 2 : 1);
                            drop.setAmount(Math.min(amount, drop.getType().getMaxStackSize()));
                            block.getWorld().dropItemNaturally(block.getLocation(), drop);
                        }
                    }
                }
            }
        }
        
        // Titan Axe - tree felling
        if (isTitanAxe(tool) && isItemEnabled("titan-axe") && isLog(block.getType())) {
            if (!getConfig().getBoolean("legendary-items.titan-axe.tree-felling", true)) return;
            
            long cooldown = getTreeCooldown();
            
            if (isOnCooldown(treeCutCooldown, playerId, cooldown)) {
                if (isFeatureEnabled("abilities.show-cooldown-messages")) {
                    long remaining = getRemainingCooldown(treeCutCooldown, playerId, cooldown);
                    sendMessage(player, "cooldowns.tree-cutting-recharging", "%time%", formatCooldown(remaining));
                }
                return;
            }
            
            setCooldown(treeCutCooldown, playerId);
            cutDownTree(block, player, tool);
            
            if (isFeatureEnabled("abilities.show-activation-messages")) {
                sendMessage(player, "abilities.axe-tree-felled-cooldown", 
                    "%cooldown%", String.valueOf(cooldown / 1000));
            }
        }
        
        // Void Shovel
        if (isVoidShovel(tool) && isItemEnabled("void-shovel")) {
            if (!getConfig().getBoolean("legendary-items.void-shovel.area-dig-enabled", true)) return;
            
            if (player.isSneaking()) {
                long cooldown = getShovelCooldown();
                
                if (isOnCooldown(shovelCooldown, playerId, cooldown)) {
                    if (isFeatureEnabled("abilities.show-cooldown-messages")) {
                        long remaining = getRemainingCooldown(shovelCooldown, playerId, cooldown);
                        sendMessage(player, "cooldowns.shovel-recharging", "%time%", formatCooldown(remaining));
                    }
                    return;
                }
                
                setCooldown(shovelCooldown, playerId);
                
                int areaSize = getConfig().getInt("legendary-items.void-shovel.area-size", 3);
                int radius = areaSize / 2;
                int blocksDestroyed = 0;
                List<ItemStack> allDrops = new ArrayList<>();
                
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        for (int y = 0; y <= 1; y++) {
                            if (x == 0 && y == 0 && z == 0) continue;
                            
                            Block nearby = block.getRelative(x, y, z);
                            
                            if (nearby.getType().isSolid() && 
                                nearby.getType() != Material.BEDROCK &&
                                canDigWithShovel(nearby.getType())) {
                                
                                Collection<ItemStack> drops = nearby.getDrops(tool);
                                allDrops.addAll(drops);
                                nearby.setType(Material.AIR);
                                blocksDestroyed++;
                            }
                        }
                    }
                }
                
                // Auto-collect
                if (getConfig().getBoolean("legendary-items.void-shovel.auto-collect", true)) {
                    for (ItemStack drop : allDrops) {
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(drop);
                        for (ItemStack leftoverItem : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                        }
                    }
                } else {
                    for (ItemStack drop : allDrops) {
                        block.getWorld().dropItemNaturally(block.getLocation(), drop);
                    }
                }
                
                if (isParticlesEnabled()) {
                    block.getWorld().spawnParticle(Particle.PORTAL, block.getLocation(), 20, 1, 1, 1, 0.1);
                }
                if (isSoundsEnabled()) {
                    block.getWorld().playSound(block.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1.0f, 0.8f);
                }
                
                if (blocksDestroyed > 0 && isFeatureEnabled("abilities.show-activation-messages")) {
                    sendMessage(player, "abilities.shovel-excavate-full", 
                        "%blocks%", String.valueOf(blocksDestroyed));
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!isItemEnabled("phoenix-feather")) return;
        if (!getConfig().getBoolean("legendary-items.phoenix-feather.resurrection", true)) return;
        
        Player player = (Player) event.getEntity();
        
        if (hasPhoenixFeather(player) && player.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            
            // Full heal
            if (getConfig().getBoolean("legendary-items.phoenix-feather.full-heal-on-resurrect", true)) {
                try {
                    double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                    player.setHealth(maxHealth);
                } catch (Exception e) {
                    player.setHealth(20.0);
                }
            }
            
            player.setFireTicks(0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 2));
            
            removePhoenixFeather(player);
            
            Location loc = player.getLocation();
            if (isParticlesEnabled()) {
                player.getWorld().spawnParticle(Particle.DRAGON_BREATH, loc, 50, 2, 2, 2, 0.1);
            }
            if (isSoundsEnabled()) {
                player.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 1.0f);
            }
            
            sendMessage(player, "abilities.phoenix-resurrect-message");
            
            if (isFeatureEnabled("features.broadcasts.resurrection-announcement")) {
                broadcastMessage("abilities.phoenix-resurrect-broadcast", "%player%", player.getName());
            }
        }
    }
    
    @EventHandler
    public void onChestOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest)) return;
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        Chest chest = (Chest) event.getInventory().getHolder();
        Location chestLoc = chest.getLocation();
        String chestKey = chestLoc.getBlockX() + "," + chestLoc.getBlockY() + "," + chestLoc.getBlockZ();

        ActiveChest activeChest = activeChests.get(chestKey);
        if (activeChest != null && !activeChest.opened) {
            activeChest.opened = true;
            
            sendMessage(player, "chest.first-opener");
            
            // Update statistics
            if (isStatsEnabled()) {
                playerStats.incrementChestsOpened(player.getUniqueId(), player.getName(), activeChest.tier.configKey);
                
                // Check for legendary items in chest
                for (ItemStack item : event.getInventory().getContents()) {
                    if (item != null && isLegendaryItem(item)) {
                        playerStats.incrementLegendaryFound(player.getUniqueId(), player.getName());
                    }
                }
                
                checkAchievements(player);
            }
        }
    }
    
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        
        if (item == null || !item.hasItemMeta()) return;
        
        if (hasCustomKey(item, WISDOM_BOOK_KEY) && isItemEnabled("wisdom-book")) {
            if (event.getAction().toString().contains("RIGHT_CLICK")) {
                event.setCancelled(true);
                applyWisdomBookToInventory(player, item);
            }
        }
    }
    
    // ==================== CUSTOM LOOT GUI HANDLERS ====================
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        
        // Get title using legacy method
        String title = event.getView().getTitle();
        
        // Main menu handling
        if (title.contains(getMessage("gui.custom-loot-title"))) {
            event.setCancelled(true);
            
            int slot = event.getRawSlot();
            if (slot == 11) {
                customLootManager.openEditMenu(player, "common");
            } else if (slot == 13) {
                customLootManager.openEditMenu(player, "rare");
            } else if (slot == 15) {
                customLootManager.openEditMenu(player, "legendary");
            }
            return;
        }
        
        // Edit menu handling
        String editCommon = getMessage("gui.edit-common");
        String editRare = getMessage("gui.edit-rare");
        String editLegendary = getMessage("gui.edit-legendary");
        
        if (title.contains(editCommon) || title.contains(editRare) || title.contains(editLegendary)) {
            int slot = event.getRawSlot();
            
            if (slot >= 45 && slot <= 53) {
                event.setCancelled(true);
                
                String tier = null;
                if (title.contains(editCommon)) tier = "common";
                else if (title.contains(editRare)) tier = "rare";
                else if (title.contains(editLegendary)) tier = "legendary";
                
                if (slot == 48 && tier != null) {
                    customLootManager.saveCustomLoot(tier, event.getInventory());
                    player.closeInventory();
                    customLootManager.openMainMenu(player);
                }
                
                if (slot == 49) {
                    player.closeInventory();
                    customLootManager.openMainMenu(player);
                }
                
                if (slot == 50 && tier != null) {
                    customLootManager.resetCustomLoot(tier);
                    player.closeInventory();
                    customLootManager.openMainMenu(player);
                }
            }
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        
        // Clear editing state
        customLootManager.clearEditingState(player.getUniqueId());
    }
    
// ==================== ACHIEVEMENTS ====================
    
    private void checkAchievements(Player player) {
        if (!isStatsEnabled() || !isFeatureEnabled("statistics.achievements.enabled")) return;
        
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        PlayerStats.PlayerData data = playerStats.getStats(playerId, playerName);
        
        // First Chest
        if (data.chestsOpened >= 1 && playerStats.unlockAchievement(playerId, playerName, "first-chest")) {
            grantAchievement(player, "first-chest");
        }
        
        // Treasure Hunter (10 chests)
        if (data.chestsOpened >= 10 && playerStats.unlockAchievement(playerId, playerName, "treasure-hunter")) {
            grantAchievement(player, "treasure-hunter");
        }
        
        // Chest Master (50 chests)
        if (data.chestsOpened >= 50 && playerStats.unlockAchievement(playerId, playerName, "chest-master")) {
            grantAchievement(player, "chest-master");
        }
        
        // Legendary Finder
        if (data.legendaryItemsFound >= 1 && playerStats.unlockAchievement(playerId, playerName, "legendary-finder")) {
            grantAchievement(player, "legendary-finder");
        }
        
        // Guardian Slayer (25 guardians)
        if (data.guardiansKilled >= 25 && playerStats.unlockAchievement(playerId, playerName, "guardian-slayer")) {
            grantAchievement(player, "guardian-slayer");
        }
    }
    
    private void grantAchievement(Player player, String achievementId) {
        String name = getMessage("achievements." + achievementId + ".name");
        String desc = getMessage("achievements." + achievementId + ".description");
        String reward = getMessage("achievements." + achievementId + ".reward-message");
        
        // Message
        player.sendMessage("");
        sendMessage(player, "broadcasts.achievement-title");
        sendMessage(player, "broadcasts.achievement-subtitle", "%name%", name);
        player.sendMessage("§7" + desc);
        if (reward != null && !reward.isEmpty()) {
            player.sendMessage(reward);
        }
        player.sendMessage("");
        
        // XP reward
        int xpReward = getConfig().getInt("statistics.achievements.list." + achievementId + ".xp-reward", 0);
        if (xpReward > 0) {
            player.giveExp(xpReward);
        }
        
        // Effects
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        player.getWorld().spawnParticle(Particle.TOTEM, player.getLocation().add(0, 1, 0), 50, 0.5, 1, 0.5, 0.1);
        
        // Broadcast
        if (isFeatureEnabled("features.broadcasts.achievement-announcement")) {
            broadcastMessage("broadcasts.achievement-unlocked", 
                "%player%", player.getName(),
                "%achievement%", name);
        }
    }
    
    private void applyWisdomBookToInventory(Player player, ItemStack book) {
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta)) return;
        
        EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) book.getItemMeta();
        int enchantedItems = 0;
        
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta() || item.equals(book)) continue;
            
            String itemType = item.getType().toString();
            ItemMeta meta = item.getItemMeta();
            boolean enchanted = false;
            
            if (itemType.contains("SWORD") || itemType.contains("AXE")) {
                if (bookMeta.hasStoredEnchant(Enchantment.DAMAGE_ALL)) {
                    meta.addEnchant(Enchantment.DAMAGE_ALL, 6, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.DURABILITY)) {
                    meta.addEnchant(Enchantment.DURABILITY, 5, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.MENDING)) {
                    meta.addEnchant(Enchantment.MENDING, 1, true);
                    enchanted = true;
                }
            } else if (itemType.contains("PICKAXE") || itemType.contains("SHOVEL") || itemType.contains("HOE")) {
                if (bookMeta.hasStoredEnchant(Enchantment.DIG_SPEED)) {
                    meta.addEnchant(Enchantment.DIG_SPEED, 6, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.LOOT_BONUS_BLOCKS)) {
                    meta.addEnchant(Enchantment.LOOT_BONUS_BLOCKS, 4, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.DURABILITY)) {
                    meta.addEnchant(Enchantment.DURABILITY, 5, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.MENDING)) {
                    meta.addEnchant(Enchantment.MENDING, 1, true);
                    enchanted = true;
                }
            } else if (itemType.contains("HELMET") || itemType.contains("CHESTPLATE") || 
                       itemType.contains("LEGGINGS") || itemType.contains("BOOTS")) {
                if (bookMeta.hasStoredEnchant(Enchantment.PROTECTION_ENVIRONMENTAL)) {
                    meta.addEnchant(Enchantment.PROTECTION_ENVIRONMENTAL, 6, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.DURABILITY)) {
                    meta.addEnchant(Enchantment.DURABILITY, 5, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.MENDING)) {
                    meta.addEnchant(Enchantment.MENDING, 1, true);
                    enchanted = true;
                }
            } else if (itemType.contains("BOW")) {
                meta.addEnchant(Enchantment.ARROW_DAMAGE, 6, true);
                meta.addEnchant(Enchantment.ARROW_INFINITE, 1, true);
                enchanted = true;
            }
            
            if (enchanted) {
                item.setItemMeta(meta);
                enchantedItems++;
            }
        }
        
        if (enchantedItems > 0) {
            if (book.getAmount() > 1) {
                book.setAmount(book.getAmount() - 1);
            } else {
                player.getInventory().remove(book);
            }
            
            sendMessage(player, "abilities.book-enchanted", "%count%", String.valueOf(enchantedItems));
            if (isSoundsEnabled()) {
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
            }
            if (isParticlesEnabled()) {
                player.getWorld().spawnParticle(Particle.ENCHANTMENT_TABLE, player.getLocation(), 30, 1, 1, 1, 0.3);
            }
        } else {
            sendMessage(player, "abilities.book-no-items");
        }
    }

    // ==================== SPAWN EFFECTS ====================
    
    private void spawnGuardians(Location location, ChestTier tier) {
        if (!isGuardiansEnabled()) return;
        
        World world = location.getWorld();
        if (world == null) return;
        
        int guardianCount = getGuardianCount(tier);
        String guardianName = getGuardianName(tier);
        
        for (int i = 0; i < guardianCount; i++) {
            double angle = (2 * Math.PI * i) / guardianCount;
            double x = location.getX() + Math.cos(angle) * 3;
            double z = location.getZ() + Math.sin(angle) * 3;
            
            // Find safe Y for guardian (above solid ground with 2 air blocks)
            int guardianY = findSafeYForEntity(world, (int)x, (int)location.getY(), (int)z);
            Location guardianLoc = new Location(world, x, guardianY, z);
            
            EntityType mobType = tier == ChestTier.LEGENDARY ? EntityType.WITHER_SKELETON :
                                tier == ChestTier.RARE ? EntityType.SKELETON : EntityType.ZOMBIE;
            
            LivingEntity guardian = (LivingEntity) world.spawnEntity(guardianLoc, mobType);
            guardian.setCustomName(guardianName);
            guardian.setCustomNameVisible(true);
            
            // Mark as guardian
            guardian.getPersistentDataContainer().set(GUARDIAN_MOB_KEY, PersistentDataType.STRING, "true");
            
            if (guardian instanceof Skeleton) {
                Skeleton skeleton = (Skeleton) guardian;
                skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                skeleton.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
            }
            
            guardian.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0));
        }
    }
    
    // Find safe Y coordinate for entity spawning (above ground with 2 air blocks)
    private int findSafeYForEntity(World world, int x, int startY, int z) {
        // First check if startY is already safe
        if (isSafeForEntity(world, x, startY, z)) {
            return startY;
        }
        
        // Search up
        for (int y = startY; y < startY + 10 && y < world.getMaxHeight() - 2; y++) {
            if (isSafeForEntity(world, x, y, z)) {
                return y;
            }
        }
        
        // Search down
        for (int y = startY - 1; y > startY - 10 && y > 0; y--) {
            if (isSafeForEntity(world, x, y, z)) {
                return y;
            }
        }
        
        // Find highest solid block and spawn above it
        int highestY = getHighestSolidBlock(world, x, z);
        return highestY + 1;
    }
    
    // Check if location is safe for entity (solid below, 2 air above)
    private boolean isSafeForEntity(World world, int x, int y, int z) {
        Material below = world.getBlockAt(x, y - 1, z).getType();
        Material at = world.getBlockAt(x, y, z).getType();
        Material above = world.getBlockAt(x, y + 1, z).getType();
        
        return below.isSolid() && at.isAir() && above.isAir();
    }
    
    private void createSpawnEffects(Location location, ChestTier tier) {
        World world = location.getWorld();
        if (world == null) return;
        
        if (isSoundsEnabled()) {
            Sound sound = tier == ChestTier.LEGENDARY ? Sound.ENTITY_DRAGON_FIREBALL_EXPLODE :
                         tier == ChestTier.RARE ? Sound.BLOCK_BEACON_ACTIVATE : Sound.BLOCK_CHEST_OPEN;
            world.playSound(location, sound, 2.0f, 1.0f);
        }
        
        if (tier == ChestTier.LEGENDARY && isLightningEnabled()) {
            world.strikeLightningEffect(location);
        }
    }
    
    private void broadcastChestSpawn(Location location, ChestTier tier) {
        if (!isFeatureEnabled("features.broadcasts.chest-spawn-announcement")) return;
        
        int displayY = location.getBlockY();
        String tierName = getTierName(tier);
        String x = String.valueOf(location.getBlockX());
        String y = String.valueOf(displayY);
        String z = String.valueOf(location.getBlockZ());
        
        // Broadcast message
        broadcastMessage("broadcasts.chest-spawned",
            "%tier%", tierName,
            "%x%", x,
            "%y%", y,
            "%z%", z);
        
        if (isFeatureEnabled("features.broadcasts.title-on-spawn")) {
            String titleText = getMessage("broadcasts.spawn-title");
            String subtitleText = getMessage("broadcasts.spawn-subtitle",
                "%x%", x,
                "%y%", y,
                "%z%", z);
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendTitle(titleText, subtitleText, 10, 70, 20);
                
                if (isSoundsEnabled()) {
                    Sound playerSound = tier == ChestTier.LEGENDARY ? Sound.UI_TOAST_CHALLENGE_COMPLETE :
                                       tier == ChestTier.RARE ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
                    player.playSound(player.getLocation(), playerSound, 1.0f, 1.0f);
                }
            }
        }
    }

    // ==================== COOLDOWN SYSTEM ====================
    
    private boolean isOnCooldown(Map<UUID, Long> cooldownMap, UUID playerId, long cooldownTime) {
        if (!cooldownMap.containsKey(playerId)) {
            return false;
        }
        return System.currentTimeMillis() - cooldownMap.get(playerId) < cooldownTime;
    }
    
    private void setCooldown(Map<UUID, Long> cooldownMap, UUID playerId) {
        cooldownMap.put(playerId, System.currentTimeMillis());
    }
    
    private long getRemainingCooldown(Map<UUID, Long> cooldownMap, UUID playerId, long cooldownTime) {
        if (!cooldownMap.containsKey(playerId)) {
            return 0L;
        }
        long timeLeft = cooldownTime - (System.currentTimeMillis() - cooldownMap.get(playerId));
        return Math.max(0L, timeLeft);
    }
    
    private String formatCooldown(long milliseconds) {
        if (milliseconds <= 0) return "0s";
        
        long seconds = milliseconds / 1000L;
        long remainingMs = milliseconds % 1000L;
        
        if (seconds > 0) {
            return seconds + "s";
        } else {
            return "0." + (remainingMs / 100) + "s";
        }
    }
    
    private String formatCooldownStatus(Map<UUID, Long> cooldownMap, UUID playerId, long cooldownTime) {
        long remaining = getRemainingCooldown(cooldownMap, playerId, cooldownTime);
        return remaining > 0 ? 
            getMessage("cooldowns.seconds-left", "%seconds%", formatCooldown(remaining)) : 
            getMessage("cooldowns.ready");
    }

    // ==================== ITEM CHECKS ====================
    
    private boolean isLegendarySword(ItemStack item) {
        return hasCustomKey(item, LEGENDARY_SWORD_KEY);
    }
    
    private boolean isMasterPickaxe(ItemStack item) {
        return hasCustomKey(item, MASTER_PICKAXE_KEY);
    }
    
    private boolean isTitanAxe(ItemStack item) {
        return hasCustomKey(item, TITAN_AXE_KEY);
    }
    
    private boolean isVoidShovel(ItemStack item) {
        return hasCustomKey(item, VOID_SHOVEL_KEY);
    }
    
    private boolean isStormHammer(ItemStack item) {
        return hasCustomKey(item, STORM_HAMMER_KEY);
    }
    
    private boolean isGuardianBow(ItemStack item) {
        return hasCustomKey(item, GUARDIAN_BOW_KEY);
    }
    
    private boolean isPoseidonTrident(ItemStack item) {
        return hasCustomKey(item, POSEIDON_TRIDENT_KEY);
    }
    
    private boolean hasCustomKey(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }
    
    private boolean isLegendaryItem(ItemStack item) {
        return isLegendarySword(item) || isMasterPickaxe(item) || isTitanAxe(item) ||
               isVoidShovel(item) || isStormHammer(item) || isGuardianBow(item) ||
               isPoseidonTrident(item) || hasCustomKey(item, PHOENIX_FEATHER_KEY) ||
               hasCustomKey(item, WISDOM_BOOK_KEY);
    }
    
    private boolean hasPhoenixFeather(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (hasCustomKey(item, PHOENIX_FEATHER_KEY)) {
                return true;
            }
        }
        return false;
    }
    
    public boolean hasPhoenixFeatherInHand(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        
        return hasCustomKey(mainHand, PHOENIX_FEATHER_KEY) || hasCustomKey(offHand, PHOENIX_FEATHER_KEY);
    }
    
    public boolean hasPoseidonTridentInHand(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        
        return hasCustomKey(mainHand, POSEIDON_TRIDENT_KEY) || hasCustomKey(offHand, POSEIDON_TRIDENT_KEY);
    }
    
    private void removePhoenixFeather(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (hasCustomKey(item, PHOENIX_FEATHER_KEY)) {
                player.getInventory().setItem(i, null);
                break;
            }
        }
    }
    
    private boolean isOre(Material material) {
        return material.toString().contains("_ORE") || 
               material == Material.ANCIENT_DEBRIS;
    }
    
    private boolean isLog(Material material) {
        return material.toString().contains("_LOG") || material.toString().contains("_WOOD");
    }
    
    private boolean canDigWithShovel(Material material) {
        return material == Material.DIRT || 
               material == Material.GRASS_BLOCK ||
               material == Material.SAND ||
               material == Material.GRAVEL ||
               material == Material.CLAY ||
               material == Material.COARSE_DIRT ||
               material == Material.PODZOL ||
               material == Material.MYCELIUM ||
               material == Material.SOUL_SAND ||
               material == Material.SOUL_SOIL ||
               material == Material.RED_SAND ||
               material == Material.FARMLAND ||
               material.toString().contains("CONCRETE_POWDER");
    }
    
    private ItemStack getSmeltedVersion(Material ore) {
        switch (ore) {
            case IRON_ORE:
            case DEEPSLATE_IRON_ORE:
                return new ItemStack(Material.IRON_INGOT);
            case GOLD_ORE:
            case DEEPSLATE_GOLD_ORE:
            case NETHER_GOLD_ORE:
                return new ItemStack(Material.GOLD_INGOT);
            case COPPER_ORE:
            case DEEPSLATE_COPPER_ORE:
                return new ItemStack(Material.COPPER_INGOT);
            case ANCIENT_DEBRIS:
                return new ItemStack(Material.NETHERITE_SCRAP);
            default:
                return null;
        }
    }
    
    private void cutDownTree(Block startBlock, Player player, ItemStack tool) {
        Set<Block> logs = new HashSet<>();
        Set<Block> leaves = new HashSet<>();
        
        findTreeBlocks(startBlock, logs, leaves, 0);
        
        int maxLogs = 50;
        int processed = 0;
        
        for (Block log : logs) {
            if (processed >= maxLogs) break;
            log.breakNaturally(tool);
            processed++;
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                int maxLeaves = 100;
                int processedLeaves = 0;
                
                for (Block leaf : leaves) {
                    if (processedLeaves >= maxLeaves) break;
                    if (random.nextDouble() < 0.8) {
                        leaf.breakNaturally();
                    }
                    processedLeaves++;
                }
            }
        }.runTaskLater(this, 20L);
    }
    
    private void findTreeBlocks(Block block, Set<Block> logs, Set<Block> leaves, int depth) {
        if (depth > 20 || logs.size() > 50) return;
        
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -1; z <= 1; z++) {
                    Block nearby = block.getRelative(x, y, z);
                    
                    if (isLog(nearby.getType()) && !logs.contains(nearby) && logs.size() < 50) {
                        logs.add(nearby);
                        findTreeBlocks(nearby, logs, leaves, depth + 1);
                    } else if (nearby.getType().toString().contains("LEAVES") && !leaves.contains(nearby) && leaves.size() < 100) {
                        leaves.add(nearby);
                    }
                }
            }
        }
    }

    // ==================== PARTICLE HELPERS ====================

    // Helper method
    private Particle resolveParticle(String newName, String oldName, Particle fallback) {
        try {
            return Particle.valueOf(newName);
        } catch (IllegalArgumentException e) {
            try {
                return Particle.valueOf(oldName);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Could not find particle " + newName + " or " + oldName + ", using fallback");
                return fallback;
            }
        }
    }

    // Then use cached values
    private Particle getHappyVillagerParticle() {
        return cachedHappyVillager;
    }

    private Particle getRainParticle() {
        return cachedRain;
    }
    
// ==================== COMMANDS ====================
    
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        
        // /giveapple
        if (cmd.getName().equalsIgnoreCase("giveapple")) {
            if (!sender.hasPermission("spawnchest.giveapple")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }
            
            if (args.length < 1) {
                sendMessage(sender, "commands.usage-giveapple");
                return true;
            }
            
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sendMessage(sender, "commands.player-not-found", "%player%", args[0]);
                return true;
            }
            
            int amount = 1;
            if (args.length > 1) {
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sendMessage(sender, "commands.invalid-number");
                    return true;
                }
            }
            
            for (int i = 0; i < amount; i++) {
                target.getInventory().addItem(createSummonerApple());
            }
            
            sendMessage(target, "commands.apple-received", "%amount%", String.valueOf(amount));
            sendMessage(sender, "commands.apple-given", 
                "%amount%", String.valueOf(amount), 
                "%player%", target.getName());
            return true;
        }
        
        // /mystats
        if (cmd.getName().equalsIgnoreCase("mystats")) {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "commands.players-only");
                return true;
            }
            
            if (!isStatsEnabled()) {
                sendMessage(sender, "commands.stats-disabled");
                return true;
            }
            
            Player player = (Player) sender;
            PlayerStats.PlayerData data = playerStats.getStats(player.getUniqueId(), player.getName());
            
            sendMessage(player, "stats.header");
            sendMessage(player, "stats.chests-opened", "%count%", String.valueOf(data.chestsOpened));
            sendMessage(player, "stats.common-opened", "%count%", String.valueOf(data.commonOpened));
            sendMessage(player, "stats.rare-opened", "%count%", String.valueOf(data.rareOpened));
            sendMessage(player, "stats.legendary-opened", "%count%", String.valueOf(data.legendaryOpened));
            sendMessage(player, "stats.legendaries-found", "%count%", String.valueOf(data.legendaryItemsFound));
            sendMessage(player, "stats.guardians-killed", "%count%", String.valueOf(data.guardiansKilled));
            sendMessage(player, "stats.apples-used", "%count%", String.valueOf(data.applesUsed));
            sendMessage(player, "stats.footer");
            
            return true;
        }
        
        // /leaderboard
        if (cmd.getName().equalsIgnoreCase("leaderboard")) {
            if (!isStatsEnabled()) {
                sendMessage(sender, "commands.stats-disabled");
                return true;
            }
            
            List<Map.Entry<String, Integer>> top = playerStats.getLeaderboard(10);
            
            sendMessage(sender, "stats.leaderboard-header");
            
            if (top.isEmpty()) {
                sendMessage(sender, "stats.leaderboard-empty");
            } else {
                int rank = 1;
                for (Map.Entry<String, Integer> entry : top) {
                    sendMessage(sender, "stats.leaderboard-entry",
                        "%rank%", String.valueOf(rank),
                        "%player%", entry.getKey(),
                        "%score%", String.valueOf(entry.getValue()));
                    rank++;
                }
            }
            sendMessage(sender, "stats.leaderboard-footer");
            return true;
        }
        
        // /chestconfig command
        if (cmd.getName().equalsIgnoreCase("chestconfig")) {
            if (!sender.hasPermission("spawnchest.config")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }
            
            if (args.length == 0) {
                sendConfigHelp(sender);
                return true;
            }
            
            String action = args[0].toLowerCase();
            
            switch (action) {
                case "get":
                    if (args.length < 2) {
                        sendMessage(sender, "commands.usage-chestconfig-get");
                        return true;
                    }
                    Object value = getConfig().get(args[1]);
                    sendMessage(sender, "commands.config-value", 
                        "%path%", args[1],
                        "%value%", String.valueOf(value));
                    break;
                    
                case "set":
                    if (args.length < 3) {
                        sendMessage(sender, "commands.usage-chestconfig-set");
                        return true;
                    }
                    String path = args[1];
                    String newValue = args[2];
                    
                    Object parsedValue;
                    if (newValue.equalsIgnoreCase("true") || newValue.equalsIgnoreCase("false")) {
                        parsedValue = Boolean.parseBoolean(newValue);
                    } else {
                        try {
                            if (newValue.contains(".")) {
                                parsedValue = Double.parseDouble(newValue);
                            } else {
                                parsedValue = Long.parseLong(newValue);
                            }
                        } catch (NumberFormatException e) {
                            parsedValue = newValue;
                        }
                    }
                    
                    setConfigValue(path, parsedValue);
                    sendMessage(sender, "commands.config-set", 
                        "%path%", path,
                        "%value%", String.valueOf(parsedValue));
                    break;
                    
                case "toggle":
                    if (args.length < 2) {
                        sender.sendMessage(getMessage("commands.usage-chestconfig-get")
                            .replace("get", "toggle"));
                        return true;
                    }
                    boolean current = isFeatureEnabled(args[1]);
                    setFeature(args[1], !current);
                    String status = !current ? 
                        getMessage("commands.status-on") : 
                        getMessage("commands.status-off");
                    sender.sendMessage("§a✓ " + args[1] + " is now " + status);
                    break;
                    
                case "reload":
                    reloadConfig();
                    spawnInterval = getConfig().getLong("settings.spawn-interval-seconds", 600L) * 1000L;
                    langManager.reload();
                    sendMessage(sender, "commands.config-reloaded");
                    break;
                    
                case "list":
                    sendConfigList(sender, args.length > 1 ? args[1] : "features");
                    break;
                    
                default:
                    sendConfigHelp(sender);
            }
            return true;
        }
        
        // /togglelegendary command
        if (cmd.getName().equalsIgnoreCase("togglelegendary")) {
            if (!sender.hasPermission("spawnchest.config")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }
            
            if (args.length == 0) {
                boolean current = isLegendaryItemsEnabled();
                setFeature("legendary-items.enabled", !current);
                String status = !current ? 
                    getMessage("commands.status-on") : 
                    getMessage("commands.status-off");
                sendMessage(sender, "commands.legendary-all-toggled", "%status%", status);
            } else {
                String item = args[0].toLowerCase();
                String itemPath = "legendary-items." + item + ".enabled";
                
                if (getConfig().get(itemPath) == null) {
                    sendMessage(sender, "commands.legendary-unknown-item", "%item%", item);
                    sendMessage(sender, "commands.legendary-available-items");
                    return true;
                }
                
                boolean current = isFeatureEnabled(itemPath);
                setFeature(itemPath, !current);
                String status = !current ? 
                    getMessage("commands.status-on") : 
                    getMessage("commands.status-off");
                sendMessage(sender, "commands.legendary-item-toggled", 
                    "%item%", item,
                    "%status%", status);
            }
            return true;
        }
        
        // /togglefeature command
        if (cmd.getName().equalsIgnoreCase("togglefeature")) {
            if (!sender.hasPermission("spawnchest.config")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }
            
            if (args.length == 0) {
                sendMessage(sender, "commands.features-available-header");
                sendMessage(sender, "commands.features-list-guardians");
                sendMessage(sender, "commands.features-list-particles");
                sendMessage(sender, "commands.features-list-sounds");
                sendMessage(sender, "commands.features-list-lightning");
                sendMessage(sender, "commands.features-list-announcements");
                sendMessage(sender, "commands.features-list-titles");
                sendMessage(sender, "commands.features-list-pvp");
                sendMessage(sender, "commands.features-list-cooldown-msg");
                sendMessage(sender, "commands.features-list-activation-msg");
                sendMessage(sender, "commands.features-list-stats");
                return true;
            }
            
            String feature = args[0].toLowerCase();
            String featurePath = getFeaturePath(feature);
            
            if (featurePath == null) {
                sendMessage(sender, "commands.features-unknown", "%feature%", feature);
                return true;
            }
            
            boolean current = isFeatureEnabled(featurePath);
            setFeature(featurePath, !current);
            String status = !current ? 
                getMessage("commands.status-on") : 
                getMessage("commands.status-off");
            sender.sendMessage("§6" + feature + ": " + status);
            return true;
        }
        
        // /nextchest
        if (cmd.getName().equalsIgnoreCase("nextchest")) {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastSpawn = currentTime - lastSpawnTime;
            long remainingTime = Math.max(0L, spawnInterval - timeSinceLastSpawn);
            
            long minutes = remainingTime / 60000L;
            long seconds = (remainingTime % 60000L) / 1000L;
            
            sendMessage(sender, "commands.next-chest",
                "%minutes%", String.valueOf(minutes),
                "%seconds%", String.valueOf(seconds));
            return true;
        }
        
        // /putintothechest - Custom loot menu
        if (cmd.getName().equalsIgnoreCase("putintothechest")) {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "commands.player-only");
                return true;
            }
            if (!sender.hasPermission("spawnchest.admin")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }
            
            Player player = (Player) sender;
            customLootManager.openMainMenu(player);
            return true;
        }
        
        // /chestnow (spawnchestnow)
        if (cmd.getName().equalsIgnoreCase("chestnow")) {
            if (!sender.hasPermission("spawnchest.admin")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }

            sendMessage(sender, "commands.searching-location");
            
            spawnChest(true, null);
            warned.clear();
            lastSecondsWarned.clear();
            sendMessage(sender, "commands.chest-spawned");

            return true;
        }
        
        // /setchesttimer
        if (cmd.getName().equalsIgnoreCase("setchesttimer")) {
            if (!sender.hasPermission("spawnchest.setchesttimer")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }

            if (args.length == 1) {
                try {
                    int seconds = Integer.parseInt(args[0]);
                    getConfig().set("settings.spawn-interval-seconds", seconds);
                    saveConfig();
                    spawnInterval = seconds * 1000L;
                    sendMessage(sender, "commands.spawn-interval-set", "%seconds%", String.valueOf(seconds));
                } catch (NumberFormatException e) {
                    sendMessage(sender, "commands.enter-valid-number");
                }
            } else {
                sendMessage(sender, "commands.usage-setchesttimer");
            }
            return true;
        }
        
        // /resettimer - reset timer to spawn chest soon
        if (cmd.getName().equalsIgnoreCase("resettimer")) {
            if (!sender.hasPermission("spawnchest.resettimer")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }
            
            // Set lastSpawnTime so chest spawns in 10 seconds
            lastSpawnTime = System.currentTimeMillis() - spawnInterval + 10000L;
            saveTimer();
            warned.clear();
            lastSecondsWarned.clear();
            sendMessage(sender, "commands.timer-reset");
            return true;
        }
        
        // /reloadchestconfig
        if (cmd.getName().equalsIgnoreCase("reloadchestconfig")) {
            if (!sender.hasPermission("spawnchest.reloadchestconfig")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }

            reloadConfig();
            langManager.reload();
            spawnInterval = getConfig().getLong("settings.spawn-interval-seconds", 600L) * 1000L;
            sendMessage(sender, "commands.config-reloaded");
            sendMessage(sender, "commands.language-reloaded",
                "%code%", langManager.getCurrentLanguage(),
                "%name%", LanguageManager.getLanguageName(langManager.getCurrentLanguage()));
            return true;
        }
        
        // /cheststats
        if (cmd.getName().equalsIgnoreCase("cheststats")) {
            sendMessage(sender, "commands.chest-stats-header");
            
            double commonChance = getConfig().getDouble("settings.chest-chances.common", 0.50) * 100;
            double rareChance = getConfig().getDouble("settings.chest-chances.rare", 0.35) * 100;
            double legendaryChance = getConfig().getDouble("settings.chest-chances.legendary", 0.15) * 100;
            
            sendMessage(sender, "commands.chest-stats-common-chance", "%chance%", String.format("%.1f", commonChance));
            sendMessage(sender, "commands.chest-stats-rare-chance", "%chance%", String.format("%.1f", rareChance));
            sendMessage(sender, "commands.chest-stats-legendary-chance", "%chance%", String.format("%.1f", legendaryChance));
            
            String guardiansStatus = isGuardiansEnabled() ? 
                getMessage("commands.status-on") : 
                getMessage("commands.status-off");
            String legendaryStatus = isLegendaryItemsEnabled() ? 
                getMessage("commands.status-on") : 
                getMessage("commands.status-off");
            String pvpStatus = isPvpProtection() ? 
                getMessage("commands.status-on") : 
                getMessage("commands.status-off");
            String particlesStatus = isParticlesEnabled() ? 
                getMessage("commands.status-on") : 
                getMessage("commands.status-off");
            String soundsStatus = isSoundsEnabled() ? 
                getMessage("commands.status-on") : 
                getMessage("commands.status-off");
            String statsStatus = isStatsEnabled() ? 
                getMessage("commands.status-on") : 
                getMessage("commands.status-off");
            
            sendMessage(sender, "commands.chest-stats-guardians", "%status%", guardiansStatus);
            sendMessage(sender, "commands.chest-stats-legendary-items", "%status%", legendaryStatus);
            sendMessage(sender, "commands.chest-stats-pvp-protection", "%status%", pvpStatus);
            sendMessage(sender, "commands.chest-stats-particles", "%status%", particlesStatus);
            sendMessage(sender, "commands.chest-stats-sounds", "%status%", soundsStatus);
            sendMessage(sender, "commands.chest-stats-statistics", "%status%", statsStatus);
            sendMessage(sender, "commands.chest-stats-active", "%count%", String.valueOf(activeChests.size()));
            return true;
        }
        
        // /cooldowns
        if (cmd.getName().equalsIgnoreCase("cooldowns")) {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "commands.players-only");
                return true;
            }

            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();

            sendMessage(player, "cooldowns.header");
            sendMessage(player, "cooldowns.dragon-slayer", 
                "%status%", formatCooldownStatus(swordCooldown, playerId, getSwordCooldown()));
            sendMessage(player, "cooldowns.titan-axe", 
                "%status%", formatCooldownStatus(axeCooldown, playerId, getAxeCooldown()));
            sendMessage(player, "cooldowns.storm-hammer", 
                "%status%", formatCooldownStatus(hammerCooldown, playerId, getHammerCooldown()));
            sendMessage(player, "cooldowns.guardian-bow", 
                "%status%", formatCooldownStatus(bowCooldown, playerId, getBowCooldown()));
            sendMessage(player, "cooldowns.void-shovel", 
                "%status%", formatCooldownStatus(shovelCooldown, playerId, getShovelCooldown()));
            sendMessage(player, "cooldowns.master-pickaxe", 
                "%status%", formatCooldownStatus(pickaxeCooldown, playerId, getPickaxeCooldown()));
            sendMessage(player, "cooldowns.tree-cutting", 
                "%status%", formatCooldownStatus(treeCutCooldown, playerId, getTreeCooldown()));
            sendMessage(player, "cooldowns.poseidon-trident", 
                "%status%", formatCooldownStatus(tridentCooldown, playerId, getTridentCooldown()));
            sendMessage(player, "cooldowns.footer");

            return true;
        }
        
        // /getlegendaryitems
        if (cmd.getName().equalsIgnoreCase("getlegendaryitems")) {
            if (!sender.hasPermission("spawnchest.getlegendaryitems")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }

            if (!(sender instanceof Player)) {
                sendMessage(sender, "commands.players-only");
                return true;
            }

            Player player = (Player) sender;

            if (isItemEnabled("dragon-slayer-sword")) player.getInventory().addItem(createLegendarySword());
            if (isItemEnabled("master-pickaxe")) player.getInventory().addItem(createMasterPickaxe());
            if (isItemEnabled("titan-axe")) player.getInventory().addItem(createTitanAxe());
            if (isItemEnabled("void-shovel")) player.getInventory().addItem(createVoidShovel());
            if (isItemEnabled("storm-hammer")) player.getInventory().addItem(createStormHammer());
            if (isItemEnabled("guardian-bow")) player.getInventory().addItem(createGuardianBow());
            if (isItemEnabled("wisdom-book")) player.getInventory().addItem(createWisdomBook());
            if (isItemEnabled("phoenix-feather")) player.getInventory().addItem(createPhoenixFeather());
            if (isItemEnabled("poseidon-trident")) player.getInventory().addItem(createPoseidonTrident());
            if (isFeatureEnabled("summoner-apple.enabled")) player.getInventory().addItem(createSummonerApple());

            player.getInventory().addItem(new ItemStack(Material.ARROW, 64));

            sendMessage(player, "commands.legendary-received");
            sendMessage(player, "commands.legendary-use-cooldowns");

            return true;
        }
        
        // /testchestzone
        if (cmd.getName().equalsIgnoreCase("testchestzone")) {
            if (!sender.hasPermission("spawnchest.testchestzone")) {
                sendMessage(sender, "commands.no-permission");
                return true;
            }

            int minDist = getConfig().getInt("settings.spawn-zone.min-distance", 400);
            int maxDist = getConfig().getInt("settings.spawn-zone.max-distance", 2000);

            sendMessage(sender, "commands.test-zone-header");
            sendMessage(sender, "commands.test-zone-min-dist", 
                "%min%", String.valueOf(minDist),
                "%max%", String.valueOf(maxDist));
            sendMessage(sender, "commands.test-zone-bury", 
                "%value%", String.valueOf(getConfig().getBoolean("settings.spawn-zone.bury-in-ground", true)));
            sendMessage(sender, "commands.test-zone-worlds", 
                "%worlds%", getConfig().getStringList("settings.enabled-worlds").toString());

            for (int i = 0; i < 5; i++) {
                double distance = minDist + random.nextDouble() * (maxDist - minDist);
                double angle = random.nextDouble() * 2 * Math.PI;
                int x = (int)(Math.cos(angle) * distance);
                int z = (int)(Math.sin(angle) * distance);
                sendMessage(sender, "commands.test-zone-example",
                    "%num%", String.valueOf(i + 1),
                    "%x%", String.valueOf(x),
                    "%z%", String.valueOf(z),
                    "%dist%", String.valueOf((int)distance));
            }

            return true;
        }

        return false;
    }
    
    private String getFeaturePath(String feature) {
        switch (feature) {
            case "guardians": return "features.guardians.enabled";
            case "particles": return "features.effects.particles";
            case "sounds": return "features.effects.sounds";
            case "lightning": return "features.effects.lightning-on-legendary";
            case "announcements": return "features.broadcasts.chest-spawn-announcement";
            case "titles": return "features.broadcasts.title-on-spawn";
            case "pvp-protection": return "abilities.pvp-protection";
            case "cooldown-messages": return "abilities.show-cooldown-messages";
            case "activation-messages": return "abilities.show-activation-messages";
            case "statistics": return "statistics.enabled";
            default: return null;
        }
    }
    
    private void sendConfigHelp(CommandSender sender) {
        sendMessage(sender, "commands.config-help-header");
        sendMessage(sender, "commands.config-help-get");
        sendMessage(sender, "commands.config-help-set");
        sendMessage(sender, "commands.config-help-toggle");
        sendMessage(sender, "commands.config-help-reload");
        sendMessage(sender, "commands.config-help-list");
        sendMessage(sender, "commands.config-help-examples");
        sendMessage(sender, "commands.config-help-example1");
        sendMessage(sender, "commands.config-help-example2");
    }
    
    private void sendConfigList(CommandSender sender, String section) {
        sendMessage(sender, "commands.config-list-header", "%section%", section);
        
        if (section.equals("features")) {
            sender.sendMessage("§7guardians.enabled: §e" + isGuardiansEnabled());
            sender.sendMessage("§7effects.particles: §e" + isParticlesEnabled());
            sender.sendMessage("§7effects.sounds: §e" + isSoundsEnabled());
            sender.sendMessage("§7effects.lightning: §e" + isLightningEnabled());
        } else if (section.equals("legendary-items")) {
            sendMessage(sender, "commands.config-list-master-toggle", 
                "%value%", String.valueOf(isLegendaryItemsEnabled()));
            sender.sendMessage("§7dragon-slayer-sword: §e" + isItemEnabled("dragon-slayer-sword"));
            sender.sendMessage("§7master-pickaxe: §e" + isItemEnabled("master-pickaxe"));
            sender.sendMessage("§7titan-axe: §e" + isItemEnabled("titan-axe"));
            sender.sendMessage("§7void-shovel: §e" + isItemEnabled("void-shovel"));
            sender.sendMessage("§7storm-hammer: §e" + isItemEnabled("storm-hammer"));
            sender.sendMessage("§7guardian-bow: §e" + isItemEnabled("guardian-bow"));
            sender.sendMessage("§7wisdom-book: §e" + isItemEnabled("wisdom-book"));
            sender.sendMessage("§7phoenix-feather: §e" + isItemEnabled("phoenix-feather"));
            sender.sendMessage("§7poseidon-trident: §e" + isItemEnabled("poseidon-trident"));
        } else if (section.equals("abilities")) {
            sender.sendMessage("§7pvp-protection: §e" + isPvpProtection());
            sender.sendMessage("§7show-cooldown-messages: §e" + isFeatureEnabled("abilities.show-cooldown-messages"));
            sender.sendMessage("§7show-activation-messages: §e" + isFeatureEnabled("abilities.show-activation-messages"));
        } else if (section.equals("loot")) {
            sender.sendMessage("§7basic-items: §e" + isFeatureEnabled("loot.basic-items.enabled"));
            sender.sendMessage("§7weapons-and-armor: §e" + isFeatureEnabled("loot.weapons-and-armor.enabled"));
            sender.sendMessage("§7tools: §e" + isFeatureEnabled("loot.tools.enabled"));
            sender.sendMessage("§7potions: §e" + isFeatureEnabled("loot.potions.enabled"));
            sender.sendMessage("§7food: §e" + isFeatureEnabled("loot.food.enabled"));
            sender.sendMessage("§7unique-items: §e" + isFeatureEnabled("loot.unique-items.enabled"));
            sender.sendMessage("§7custom-potions: §e" + isFeatureEnabled("custom-potions.enabled"));
        } else if (section.equals("statistics")) {
            sender.sendMessage("§7enabled: §e" + isStatsEnabled());
            sender.sendMessage("§7achievements.enabled: §e" + isFeatureEnabled("statistics.achievements.enabled"));
        }
    }
    
// ==================== TAB COMPLETION ====================
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (cmd.getName().equalsIgnoreCase("chestconfig")) {
            if (args.length == 1) {
                completions.addAll(Arrays.asList("get", "set", "toggle", "reload", "list"));
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("list")) {
                    completions.addAll(Arrays.asList("features", "legendary-items", "loot", "abilities", "statistics"));
                } else {
                    completions.addAll(Arrays.asList(
                        "features.guardians.enabled",
                        "features.effects.particles",
                        "features.effects.sounds",
                        "features.effects.lightning-on-legendary",
                        "features.effects.chest-beacon-beam",
                        "features.broadcasts.chest-spawn-announcement",
                        "features.broadcasts.title-on-spawn",
                        "features.broadcasts.countdown-warnings",
                        "features.broadcasts.resurrection-announcement",
                        "features.broadcasts.achievement-announcement",
                        "legendary-items.enabled",
                        "legendary-items.dragon-slayer-sword.enabled",
                        "legendary-items.dragon-slayer-sword.bonus-damage",
                        "legendary-items.dragon-slayer-sword.cooldown-ms",
                        "legendary-items.master-pickaxe.enabled",
                        "legendary-items.titan-axe.enabled",
                        "legendary-items.void-shovel.enabled",
                        "legendary-items.storm-hammer.enabled",
                        "legendary-items.storm-hammer.bonus-damage",
                        "legendary-items.storm-hammer.cooldown-ms",
                        "legendary-items.guardian-bow.enabled",
                        "legendary-items.wisdom-book.enabled",
                        "legendary-items.phoenix-feather.enabled",
                        "legendary-items.poseidon-trident.enabled",
                        "legendary-items.poseidon-trident.rain-radius",
                        "legendary-items.poseidon-trident.lightning-damage",
                        "summoner-apple.enabled",
                        "summoner-apple.drop-chance",
                        "abilities.pvp-protection",
                        "abilities.show-cooldown-messages",
                        "abilities.show-activation-messages",
                        "statistics.enabled",
                        "statistics.achievements.enabled",
                        "settings.spawn-interval-seconds",
                        "settings.chest-disappear-minutes",
                        "settings.spawn-zone.bury-in-ground",
                        "settings.chest-chances.common",
                        "settings.chest-chances.rare",
                        "settings.chest-chances.legendary",
                        "proximity-sounds.enabled",
                        "proximity-sounds.max-distance",
                        "custom-potions.enabled"
                    ));
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                completions.addAll(Arrays.asList("true", "false"));
            }
        } else if (cmd.getName().equalsIgnoreCase("togglelegendary")) {
            if (args.length == 1) {
                completions.addAll(Arrays.asList(
                    "dragon-slayer-sword", "master-pickaxe", "titan-axe", 
                    "void-shovel", "storm-hammer", "guardian-bow", 
                    "wisdom-book", "phoenix-feather", "poseidon-trident"
                ));
            }
        } else if (cmd.getName().equalsIgnoreCase("togglefeature")) {
            if (args.length == 1) {
                completions.addAll(Arrays.asList(
                    "guardians", "particles", "sounds", "lightning",
                    "announcements", "titles", "pvp-protection", 
                    "cooldown-messages", "activation-messages", "statistics"
                ));
            }
        } else if (cmd.getName().equalsIgnoreCase("giveapple")) {
            if (args.length == 1) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    completions.add(p.getName());
                }
            }
        }
        
        String input = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        completions.removeIf(s -> !s.toLowerCase().startsWith(input));
        
        return completions;
    }

    // ==================== INNER TASK CLASSES ====================
    
    private class ChestTimer extends BukkitRunnable {
        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastSpawn = currentTime - lastSpawnTime;
            long remainingTime = spawnInterval - timeSinceLastSpawn;
            
            // Warnings
            if (isFeatureEnabled("features.broadcasts.countdown-warnings")) {
                for (long warnTick : warningTimes) {
                    long warnTime = warnTick * 50L;
                    if (remainingTime <= warnTime && remainingTime > warnTime - 1000L && !warned.contains(warnTick)) {
                        int minutes = (int) (warnTick / 1200L);
                        broadcastMessage("broadcasts.minutes-left", "%minutes%", String.valueOf(minutes));
                        warned.add(warnTick);
                    }
                }
                
                if (remainingTime <= 60000L && remainingTime > 0L) {
                    int seconds = (int) (remainingTime / 1000L);
                    if (remainingTime % 1000L < 50L && !lastSecondsWarned.contains(seconds)) {
                        if (seconds <= 10 || seconds == 30 || seconds == 60) {
                            broadcastMessage("broadcasts.countdown", "%seconds%", String.valueOf(seconds));
                            
                            if (isSoundsEnabled()) {
                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.5f, 1.0f);
                                }
                            }
                        }
                        lastSecondsWarned.add(seconds);
                    }
                }
            }
            
            if (remainingTime <= 0L) {
                // Check if spawn is stuck
                if (spawnInProgress) {
                    long elapsed = System.currentTimeMillis() - spawnStartTime;
                    if (elapsed > 30000) {
                        getLogger().warning(getMessage("system.timer-spawn-reset"));
                        spawnInProgress = false;
                    } else {
                        return; // Spawn still in progress, wait
                    }
                }
                
                // spawnChest() will set spawnInProgress = true
                lastSpawnTime = System.currentTimeMillis();
                spawnChest();
                warned.clear();
                lastSecondsWarned.clear();
            }
        }
    }
    
    private class PhoenixPassiveTask extends BukkitRunnable {
        @Override
        public void run() {
            if (!isItemEnabled("phoenix-feather")) return;
            if (!getConfig().getBoolean("legendary-items.phoenix-feather.fire-immunity-passive", true)) return;
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (hasPhoenixFeatherInHand(player)) {
                    player.addPotionEffect(
                        new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 100, 0, true, false)
                    );
                }
            }
        }
    }
    
    private class PoseidonRainTask extends BukkitRunnable {
        @Override
        public void run() {
            if (!isItemEnabled("poseidon-trident")) return;
            
            int rainRadius = getConfig().getInt("legendary-items.poseidon-trident.rain-radius", 5);
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (hasPoseidonTridentInHand(player)) {
                    Location loc = player.getLocation();
                    
                    // Spawn rain particles in radius
                    for (int i = 0; i < 20; i++) {
                        double x = loc.getX() + (random.nextDouble() * rainRadius * 2) - rainRadius;
                        double z = loc.getZ() + (random.nextDouble() * rainRadius * 2) - rainRadius;
                        double y = loc.getY() + 10;
                        
                        player.getWorld().spawnParticle(Particle.DRIP_WATER, x, y, z, 1);
                        player.getWorld().spawnParticle(getRainParticle(), x, y - 5, z, 1);
                    }
                    
                    // Play rain sound occasionally
                    if (random.nextInt(10) == 0) {
                        player.getWorld().playSound(loc, Sound.WEATHER_RAIN, 0.3f, 1.0f);
                    }
                }
            }
        }
    }
    
    private class ProximitySoundTask extends BukkitRunnable {
        @Override
        public void run() {
            if (!isFeatureEnabled("proximity-sounds.enabled")) return;
            
            double maxDist = getConfig().getDouble("proximity-sounds.max-distance", 50);
            double minDist = getConfig().getDouble("proximity-sounds.min-distance", 5);
            String soundName = getConfig().getString("proximity-sounds.sound", "BLOCK_NOTE_BLOCK_CHIME");
            
            Sound sound;
            try {
                sound = Sound.valueOf(soundName);
            } catch (IllegalArgumentException e) {
                sound = Sound.BLOCK_NOTE_BLOCK_CHIME;
            }
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (ActiveChest chest : activeChests.values()) {
                    if (chest.opened) continue;
                    if (!chest.location.getWorld().equals(player.getWorld())) continue;
                    
                    double distance = chest.location.distance(player.getLocation());
                    
                    if (distance <= maxDist) {
                        // Volume based on distance (closer = louder)
                        float volume = (float) Math.max(0.1, 1.0 - (distance / maxDist));
                        float pitch = (float) (0.5 + (distance / maxDist) * 0.5);
                        
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    }
                }
            }
        }
    }
    
    private class ChestEffectsTask extends BukkitRunnable {
        private int tick = 0;
        
        @Override
        public void run() {
            tick++;
            
            if (!isParticlesEnabled()) return;
            
            for (ActiveChest chest : activeChests.values()) {
                if (chest.opened) continue;
                
                World world = chest.location.getWorld();
                if (world == null) continue;
                
                Location loc = chest.location.clone().add(0.5, 1.5, 0.5);
                
                // Rotating particles
                Particle particle = chest.tier == ChestTier.LEGENDARY ? Particle.DRAGON_BREATH :
                                   chest.tier == ChestTier.RARE ? Particle.ENCHANTMENT_TABLE : getHappyVillagerParticle();
                
                for (int i = 0; i < 5; i++) {
                    double angle = (tick + i) * 0.3;
                    double x = loc.getX() + Math.cos(angle) * 1.5;
                    double z = loc.getZ() + Math.sin(angle) * 1.5;
                    double y = loc.getY() + Math.sin(tick * 0.1) * 0.3;
                    
                    world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
                }
                
                // Beacon beam effect
                if (isFeatureEnabled("features.effects.chest-beacon-beam") && tick % 5 == 0) {
                    for (int y = 0; y < 30; y++) {
                        world.spawnParticle(Particle.END_ROD, loc.getX(), loc.getY() + y, loc.getZ(), 1, 0.1, 0, 0.1, 0);
                    }
                }
            }
        }
    }
    
    private class ChestDisappearTask extends BukkitRunnable {
        @Override
        public void run() {
            int disappearMinutes = getConfig().getInt("settings.chest-disappear-minutes", 30);
            if (disappearMinutes <= 0) return;
            
            long disappearTime = disappearMinutes * 60 * 1000L;
            long now = System.currentTimeMillis();
            
            Iterator<Map.Entry<String, ActiveChest>> it = activeChests.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, ActiveChest> entry = it.next();
                ActiveChest chest = entry.getValue();
                
                if (!chest.opened && (now - chest.spawnTime) > disappearTime) {
                    // Remove chest
                    Block block = chest.location.getBlock();
                    if (block.getType() == Material.CHEST) {
                        // Clear inventory
                        if (block.getState() instanceof Chest) {
                            ((Chest) block.getState()).getBlockInventory().clear();
                        }
                        block.setType(Material.AIR);
                        
                        // Effects
                        if (isParticlesEnabled()) {
                            chest.location.getWorld().spawnParticle(Particle.SMOKE_NORMAL, 
                                chest.location.clone().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0.05);
                        }
                        if (isSoundsEnabled()) {
                            chest.location.getWorld().playSound(chest.location, 
                                Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
                        }
                        
                        // Broadcast
                        broadcastMessage("broadcasts.chest-disappeared");
                    }
                    
                    it.remove();
                }
            }
        }
    }
    
    private class ArrowHomingTask extends BukkitRunnable {
        private final Arrow arrow;
        private final Player shooter;
        private final double maxDistance;
        private int ticks = 0;

        public ArrowHomingTask(Arrow arrow, Player shooter, double maxDistance) {
            this.arrow = arrow;
            this.shooter = shooter;
            this.maxDistance = maxDistance;
        }
        
        @Override
        public void run() {
            if (arrow == null || arrow.isDead() || arrow.isOnGround() || ticks++ > 60) {
                cancel();
                return;
            }
            
            LivingEntity target = null;
            double minDistance = maxDistance;
            
            // Use version adapter for entity search
            Collection<LivingEntity> nearbyEntities = versionAdapter.getNearbyLivingEntities(
                arrow.getLocation(), maxDistance);
            
            for (LivingEntity entity : nearbyEntities) {
                if (entity == null || entity == shooter) continue;
                if (isPvpProtection() && entity instanceof Player) continue;
                
                double distance = entity.getLocation().distance(arrow.getLocation());
                if (distance < minDistance) {
                    minDistance = distance;
                    target = entity;
                }
            }
            
            if (target != null) {
                Vector direction = target.getEyeLocation().subtract(arrow.getLocation()).toVector().normalize();
                arrow.setVelocity(direction.multiply(1.5));
                
                if (isParticlesEnabled()) {
                    arrow.getWorld().spawnParticle(Particle.ENCHANTMENT_TABLE, arrow.getLocation(), 1);
                }
            }
        }
    }
    
} // END OF CLASS