package com.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Manages custom loot for chest tiers.
 * Multi-version compatible (1.18-1.21.x).
 */
public class CustomLootManager {
    
    private final SpawnChestPlugin plugin;
    private final LanguageManager lang;
    private final File lootFile;
    
    private final Map<String, List<ItemStack>> customLoot = new HashMap<>();
    private final Map<String, Boolean> customLootEnabled = new HashMap<>();
    private final Map<UUID, String> playerEditingTier = new HashMap<>();
    
    public CustomLootManager(SpawnChestPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.lootFile = new File(plugin.getDataFolder(), "custom_loot.yml");
        
        customLootEnabled.put("common", false);
        customLootEnabled.put("rare", false);
        customLootEnabled.put("legendary", false);
        
        customLoot.put("common", new ArrayList<>());
        customLoot.put("rare", new ArrayList<>());
        customLoot.put("legendary", new ArrayList<>());
        
        loadCustomLoot();
    }
    
    // ==================== PUBLIC API ====================
    
    public void openMainMenu(Player player) {
        String title = lang.getMessage("gui.custom-loot-title");
        Inventory inv = Bukkit.createInventory(null, 27, title);
        
        // Common chest (slot 11)
        ItemStack commonBlock = new ItemStack(Material.CHEST);
        ItemMeta commonMeta = commonBlock.getItemMeta();
        if (commonMeta != null) {
            commonMeta.setDisplayName(lang.getMessage("gui.common-chest-name"));
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("gui.click-to-edit"));
            lore.add("");
            if (customLootEnabled.get("common")) {
                lore.add(lang.getMessage("gui.custom-enabled"));
                lore.add(lang.getMessage("gui.items-count", "%count%", String.valueOf(customLoot.get("common").size())));
            } else {
                lore.add(lang.getMessage("gui.custom-disabled"));
                lore.add(lang.getMessage("gui.using-default"));
            }
            commonMeta.setLore(lore);
            commonBlock.setItemMeta(commonMeta);
        }
        inv.setItem(11, commonBlock);
        
        // Rare chest (slot 13)
        ItemStack rareBlock = new ItemStack(Material.CHEST);
        ItemMeta rareMeta = rareBlock.getItemMeta();
        if (rareMeta != null) {
            rareMeta.setDisplayName(lang.getMessage("gui.rare-chest-name"));
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("gui.click-to-edit"));
            lore.add("");
            if (customLootEnabled.get("rare")) {
                lore.add(lang.getMessage("gui.custom-enabled"));
                lore.add(lang.getMessage("gui.items-count", "%count%", String.valueOf(customLoot.get("rare").size())));
            } else {
                lore.add(lang.getMessage("gui.custom-disabled"));
                lore.add(lang.getMessage("gui.using-default"));
            }
            rareMeta.setLore(lore);
            rareBlock.setItemMeta(rareMeta);
        }
        inv.setItem(13, rareBlock);
        
        // Legendary chest (slot 15)
        ItemStack legendaryBlock = new ItemStack(Material.ENDER_CHEST);
        ItemMeta legendaryMeta = legendaryBlock.getItemMeta();
        if (legendaryMeta != null) {
            legendaryMeta.setDisplayName(lang.getMessage("gui.legendary-chest-name"));
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("gui.click-to-edit"));
            lore.add("");
            if (customLootEnabled.get("legendary")) {
                lore.add(lang.getMessage("gui.custom-enabled"));
                lore.add(lang.getMessage("gui.items-count", "%count%", String.valueOf(customLoot.get("legendary").size())));
            } else {
                lore.add(lang.getMessage("gui.custom-disabled"));
                lore.add(lang.getMessage("gui.using-default"));
            }
            legendaryMeta.setLore(lore);
            legendaryBlock.setItemMeta(legendaryMeta);
        }
        inv.setItem(15, legendaryBlock);
        
        player.openInventory(inv);
    }
    
    public void openEditMenu(Player player, String tier) {
        openTierEditor(player, tier);
    }
    
    public void saveCustomLoot(String tier, Inventory inventory) {
        saveItemsFromInventory(inventory, tier);
        customLootEnabled.put(tier, true);
        saveCustomLoot();
    }
    
    public void resetCustomLoot(String tier) {
        customLoot.put(tier, new ArrayList<>());
        customLootEnabled.put(tier, false);
        saveCustomLoot();
    }
    
    public void clearEditingState(UUID playerId) {
        playerEditingTier.remove(playerId);
    }
    
    public boolean isCustomLootEnabled(String tier) {
        return customLootEnabled.getOrDefault(tier, false);
    }
    
    public List<ItemStack> getCustomLoot(String tier) {
        return new ArrayList<>(customLoot.getOrDefault(tier, new ArrayList<>()));
    }
    
    public List<ItemStack> getRandomCustomLoot(String tier) {
        List<ItemStack> all = getCustomLoot(tier);
        if (all.isEmpty()) return all;
        
        // Return 1/3 of items randomly
        int count = Math.max(1, all.size() / 3);
        Collections.shuffle(all);
        return all.subList(0, Math.min(count, all.size()));
    }
    
    public boolean isEditingInventory(String title) {
        return title.equals(lang.getMessage("gui.custom-loot-title")) ||
               title.equals(lang.getMessage("gui.edit-common")) ||
               title.equals(lang.getMessage("gui.edit-rare")) ||
               title.equals(lang.getMessage("gui.edit-legendary"));
    }
    
    public boolean isControlSlot(int slot) {
        return slot >= 45;
    }
    
    public String getEditingTier(UUID playerId) {
        return playerEditingTier.get(playerId);
    }
    
    // ==================== INTERNAL METHODS ====================
    
    private void openTierEditor(Player player, String tier) {
        String title;
        switch (tier) {
            case "common":
                title = lang.getMessage("gui.edit-common");
                break;
            case "rare":
                title = lang.getMessage("gui.edit-rare");
                break;
            case "legendary":
                title = lang.getMessage("gui.edit-legendary");
                break;
            default:
                title = lang.getMessage("gui.custom-loot-title");
        }
        
        Inventory inv = Bukkit.createInventory(null, 54, title);
        
        // Load current items
        List<ItemStack> items = customLoot.get(tier);
        for (int i = 0; i < Math.min(items.size(), 45); i++) {
            inv.setItem(i, items.get(i));
        }
        
        // Control buttons (bottom row)
        // Save button (slot 48)
        ItemStack saveBtn = new ItemStack(Material.LIME_WOOL);
        ItemMeta saveMeta = saveBtn.getItemMeta();
        if (saveMeta != null) {
            saveMeta.setDisplayName(lang.getMessage("gui.save-button"));
            saveMeta.setLore(lang.getMessageList("gui.save-lore"));
            saveBtn.setItemMeta(saveMeta);
        }
        inv.setItem(48, saveBtn);
        
        // Back button (slot 49)
        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(lang.getMessage("gui.back-button"));
            backBtn.setItemMeta(backMeta);
        }
        inv.setItem(49, backBtn);
        
        // Reset button (slot 50)
        ItemStack resetBtn = new ItemStack(Material.RED_WOOL);
        ItemMeta resetMeta = resetBtn.getItemMeta();
        if (resetMeta != null) {
            resetMeta.setDisplayName(lang.getMessage("gui.reset-button"));
            resetMeta.setLore(lang.getMessageList("gui.reset-lore"));
            resetBtn.setItemMeta(resetMeta);
        }
        inv.setItem(50, resetBtn);
        
        playerEditingTier.put(player.getUniqueId(), tier);
        player.openInventory(inv);
    }
    
    private void saveItemsFromInventory(Inventory inv, String tier) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }
        customLoot.put(tier, items);
    }
    
    private void loadCustomLoot() {
        if (!lootFile.exists()) {
            plugin.getLogger().info("No custom loot file found, using defaults.");
            return;
        }
        
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(lootFile);
            
            for (String tier : Arrays.asList("common", "rare", "legendary")) {
                customLootEnabled.put(tier, config.getBoolean(tier + ".enabled", false));
                
                List<ItemStack> items = new ArrayList<>();
                if (config.contains(tier + ".items")) {
                    List<?> itemList = config.getList(tier + ".items");
                    if (itemList != null) {
                        for (Object obj : itemList) {
                            if (obj instanceof ItemStack) {
                                items.add((ItemStack) obj);
                            }
                        }
                    }
                }
                customLoot.put(tier, items);
            }
            
            plugin.getLogger().info("Loaded custom loot configuration.");
        } catch (Exception e) {
            plugin.getLogger().warning("Error loading custom loot: " + e.getMessage());
        }
    }
    
    private void saveCustomLoot() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            
            for (String tier : Arrays.asList("common", "rare", "legendary")) {
                config.set(tier + ".enabled", customLootEnabled.get(tier));
                config.set(tier + ".items", customLoot.get(tier));
            }
            
            config.save(lootFile);
            plugin.getLogger().info("Custom loot saved to file.");
        } catch (IOException e) {
            plugin.getLogger().severe("Error saving custom loot: " + e.getMessage());
        }
    }
}