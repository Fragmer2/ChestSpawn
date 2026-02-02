package com.myplugin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class ConfigManager {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    
    // Feature flags cache for performance
    private boolean guardiansEnabled;
    private boolean particlesEnabled;
    private boolean soundsEnabled;
    private boolean lightningEnabled;
    private boolean legendaryItemsEnabled;
    private boolean pvpProtection;
    
    // Individual legendary items
    private boolean dragonSwordEnabled;
    private boolean masterPickaxeEnabled;
    private boolean titanAxeEnabled;
    private boolean voidShovelEnabled;
    private boolean stormHammerEnabled;
    private boolean guardianBowEnabled;
    private boolean wisdomBookEnabled;
    private boolean phoenixFeatherEnabled;
    
    // Cooldowns
    private long swordCooldown;
    private long pickaxeCooldown;
    private long axeCooldown;
    private long shovelCooldown;
    private long hammerCooldown;
    private long bowCooldown;
    private long treeCooldown;
    
    // Damage values
    private double swordBonusDamage;
    private double axeBonusDamage;
    private double hammerBonusDamage;
    private double hammerAreaDamage;
    
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }
    
    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        loadAllSettings();
    }
    
    private void loadAllSettings() {
        // Features
        guardiansEnabled = config.getBoolean("features.guardians.enabled", true);
        particlesEnabled = config.getBoolean("features.effects.particles", true);
        soundsEnabled = config.getBoolean("features.effects.sounds", true);
        lightningEnabled = config.getBoolean("features.effects.lightning-on-legendary", true);
        pvpProtection = config.getBoolean("abilities.pvp-protection", true);
        
        // Legendary items master toggle
        legendaryItemsEnabled = config.getBoolean("legendary-items.enabled", true);
        
        // Individual items
        dragonSwordEnabled = config.getBoolean("legendary-items.dragon-slayer-sword.enabled", true);
        masterPickaxeEnabled = config.getBoolean("legendary-items.master-pickaxe.enabled", true);
        titanAxeEnabled = config.getBoolean("legendary-items.titan-axe.enabled", true);
        voidShovelEnabled = config.getBoolean("legendary-items.void-shovel.enabled", true);
        stormHammerEnabled = config.getBoolean("legendary-items.storm-hammer.enabled", true);
        guardianBowEnabled = config.getBoolean("legendary-items.guardian-bow.enabled", true);
        wisdomBookEnabled = config.getBoolean("legendary-items.wisdom-book.enabled", true);
        phoenixFeatherEnabled = config.getBoolean("legendary-items.phoenix-feather.enabled", true);
        
        // Cooldowns
        swordCooldown = config.getLong("legendary-items.dragon-slayer-sword.cooldown-ms", 3000);
        pickaxeCooldown = config.getLong("legendary-items.master-pickaxe.cooldown-ms", 1000);
        axeCooldown = config.getLong("legendary-items.titan-axe.combat-cooldown-ms", 4000);
        treeCooldown = config.getLong("legendary-items.titan-axe.tree-cooldown-ms", 6000);
        shovelCooldown = config.getLong("legendary-items.void-shovel.cooldown-ms", 5000);
        hammerCooldown = config.getLong("legendary-items.storm-hammer.cooldown-ms", 8000);
        bowCooldown = config.getLong("legendary-items.guardian-bow.cooldown-ms", 2000);
        
        // Damage
        swordBonusDamage = config.getDouble("legendary-items.dragon-slayer-sword.bonus-damage", 4.0);
        axeBonusDamage = config.getDouble("legendary-items.titan-axe.bonus-damage", 3.0);
        hammerBonusDamage = config.getDouble("legendary-items.storm-hammer.bonus-damage", 5.0);
        hammerAreaDamage = config.getDouble("legendary-items.storm-hammer.area-damage-amount", 4.0);
    }
    
    // ============ GETTERS ============
    
    public boolean isGuardiansEnabled() { return guardiansEnabled; }
    public boolean isParticlesEnabled() { return particlesEnabled; }
    public boolean isSoundsEnabled() { return soundsEnabled; }
    public boolean isLightningEnabled() { return lightningEnabled; }
    public boolean isPvpProtection() { return pvpProtection; }
    public boolean isLegendaryItemsEnabled() { return legendaryItemsEnabled; }
    
    public boolean isDragonSwordEnabled() { return legendaryItemsEnabled && dragonSwordEnabled; }
    public boolean isMasterPickaxeEnabled() { return legendaryItemsEnabled && masterPickaxeEnabled; }
    public boolean isTitanAxeEnabled() { return legendaryItemsEnabled && titanAxeEnabled; }
    public boolean isVoidShovelEnabled() { return legendaryItemsEnabled && voidShovelEnabled; }
    public boolean isStormHammerEnabled() { return legendaryItemsEnabled && stormHammerEnabled; }
    public boolean isGuardianBowEnabled() { return legendaryItemsEnabled && guardianBowEnabled; }
    public boolean isWisdomBookEnabled() { return legendaryItemsEnabled && wisdomBookEnabled; }
    public boolean isPhoenixFeatherEnabled() { return legendaryItemsEnabled && phoenixFeatherEnabled; }
    
    public boolean canDropInChest(String item) {
        return config.getBoolean("legendary-items." + item + ".drop-in-chests", true);
    }
    
    public long getSwordCooldown() { return swordCooldown; }
    public long getPickaxeCooldown() { return pickaxeCooldown; }
    public long getAxeCooldown() { return axeCooldown; }
    public long getTreeCooldown() { return treeCooldown; }
    public long getShovelCooldown() { return shovelCooldown; }
    public long getHammerCooldown() { return hammerCooldown; }
    public long getBowCooldown() { return bowCooldown; }
    
    public double getSwordBonusDamage() { return swordBonusDamage; }
    public double getAxeBonusDamage() { return axeBonusDamage; }
    public double getHammerBonusDamage() { return hammerBonusDamage; }
    public double getHammerAreaDamage() { return hammerAreaDamage; }
    
    public int getGuardianCount(String tier) {
        return config.getInt("features.guardians." + tier + "-count", 1);
    }
    
    public double getChestChance(String tier) {
        return config.getDouble("settings.chest-chances." + tier, 0.7);
    }
    
    public int getLootCount(String category, String tier) {
        return config.getInt("loot." + category + "." + tier + "-count", 3);
    }
    
    public boolean isLootCategoryEnabled(String category) {
        return config.getBoolean("loot." + category + ".enabled", true);
    }
    
    public boolean isFeatureEnabled(String path) {
        return config.getBoolean(path, true);
    }
    
    public String getMessage(String key) {
        String prefix = config.getString("messages.prefix", "§6[SpawnChest] §r");
        return prefix + config.getString("messages." + key, "Message not found: " + key);
    }
    
    public String getRawMessage(String key) {
        return config.getString("messages." + key, "Message not found: " + key);
    }
    
    // ============ SETTERS (for in-game commands) ============
    
    public void setFeature(String path, boolean value) {
        config.set(path, value);
        plugin.saveConfig();
        reload();
    }
    
    public void setValue(String path, Object value) {
        config.set(path, value);
        plugin.saveConfig();
        reload();
    }
    
    public Object getValue(String path) {
        return config.get(path);
    }
    
    public FileConfiguration getConfig() {
        return config;
    }
}