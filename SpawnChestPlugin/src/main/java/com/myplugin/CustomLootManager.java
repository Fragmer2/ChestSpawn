package com.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemFlag;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CustomLootManager {
    
    private final SpawnChestPlugin plugin;
    private final LanguageManager lang;
    private final File lootFile;
    private FileConfiguration lootConfig;
    
    // Track which tier each player is editing
    private final Map<UUID, String> editingSessions = new HashMap<>();
    
    // Item fractions
    private static final String[] FRACTIONS = {
        "all", "one-half", "one-third", "one-fourth", "one-fifth", 
        "one-sixth", "one-seventh", "one-eighth", "one-ninth", "one-tenth"
    };
    
    public CustomLootManager(SpawnChestPlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        
        this.lootFile = new File(plugin.getDataFolder(), "custom_loot.yml");
        
        if (!lootFile.exists()) {
            try {
                lootFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create custom_loot.yml: " + e.getMessage());
            }
        }
        
        this.lootConfig = YamlConfiguration.loadConfiguration(lootFile);
        
        // Initialize default values
        for (String tier : Arrays.asList("common", "rare", "legendary")) {
            if (!lootConfig.contains(tier + ".enabled")) {
                lootConfig.set(tier + ".enabled", false);
                lootConfig.set(tier + ".double-chest", false);
                lootConfig.set(tier + ".item-fraction", "one-third");
                lootConfig.set(tier + ".items", new ArrayList<>());
            }
        }
        
        try {
            lootConfig.save(lootFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save custom_loot.yml: " + e.getMessage());
        }
    }
    
    // ==================== CONFIGURATION ====================
    
    public boolean isCustomLootEnabled(String tier) {
        return lootConfig.getBoolean(tier + ".enabled", false);
    }
    
    public boolean isDoubleChest(String tier) {
        return lootConfig.getBoolean(tier + ".double-chest", false);
    }
    
    public String getItemFraction(String tier) {
        return lootConfig.getString(tier + ".item-fraction", "one-third");
    }
    
    public int getChestSize(String tier) {
        return isDoubleChest(tier) ? 54 : 27;
    }
    
    public int getChestAreaSize(String tier) {
        return isDoubleChest(tier) ? 45 : 18;
    }
    
    public List<ItemStack> getCustomLoot(String tier) {
        List<ItemStack> items = new ArrayList<>();
        
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> itemsList = (List<Map<?, ?>>) lootConfig.getList(tier + ".items", new ArrayList<>());
        
        for (Map<?, ?> itemMap : itemsList) {
            try {
                ItemStack item = ItemStack.deserialize((Map<String, Object>) itemMap);
                items.add(item);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not deserialize item: " + e.getMessage());
            }
        }
        
        return items;
    }
    
    public int getCustomLootCount(String tier) {
        return getCustomLoot(tier).size();
    }
    
    // Get random subset of custom loot based on fraction
    public List<ItemStack> getRandomCustomLoot(String tier) {
        List<ItemStack> allItems = getCustomLoot(tier);
        if (allItems.isEmpty()) {
            return new ArrayList<>();
        }
        
        String fraction = getItemFraction(tier);
        int count = calculateItemCount(allItems.size(), fraction);
        
        // Shuffle and take first 'count' items
        List<ItemStack> shuffled = new ArrayList<>(allItems);
        Collections.shuffle(shuffled);
        
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }
    
    private int calculateItemCount(int total, String fraction) {
        switch (fraction) {
            case "all": return total;
            case "one-half": return Math.max(1, total / 2);
            case "one-third": return Math.max(1, total / 3);
            case "one-fourth": return Math.max(1, total / 4);
            case "one-fifth": return Math.max(1, total / 5);
            case "one-sixth": return Math.max(1, total / 6);
            case "one-seventh": return Math.max(1, total / 7);
            case "one-eighth": return Math.max(1, total / 8);
            case "one-ninth": return Math.max(1, total / 9);
            case "one-tenth": return Math.max(1, total / 10);
            default: return Math.max(1, total / 3);
        }
    }
    
    // ==================== INVENTORY MANAGEMENT ====================
    
    // Main menu
    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, lang.getMessage("gui.custom-loot-title"));
        
        // Common tier (slot 11)
        inv.setItem(11, createTierIcon("common"));
        
        // Rare tier (slot 13)
        inv.setItem(13, createTierIcon("rare"));
        
        // Legendary tier (slot 15)
        inv.setItem(15, createTierIcon("legendary"));
        
        player.openInventory(inv);
    }
    
    private ItemStack createTierIcon(String tier) {
        Material material;
        switch (tier) {
            case "common": material = Material.CHEST; break;
            case "rare": material = Material.ENDER_CHEST; break;
            case "legendary": material = Material.BEACON; break;
            default: material = Material.CHEST;
        }
        
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            // Use correct keys from config
            meta.setDisplayName(lang.getMessage("gui." + tier + "-chest-name"));
            
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("gui.click-to-edit"));
            lore.add("");
            
            boolean enabled = isCustomLootEnabled(tier);
            if (enabled) {
                lore.add(lang.getMessage("gui.custom-enabled"));
            } else {
                lore.add(lang.getMessage("gui.custom-disabled"));
                lore.add(lang.getMessage("gui.using-default"));
            }
            
            lore.add("");
            lore.add(lang.getMessage("gui.items-count", "%count%", String.valueOf(getCustomLootCount(tier))));
            lore.add(lang.getMessage("gui.chest-type-display", "%type%", 
                lang.getMessage(isDoubleChest(tier) ? "gui.chest-type-double" : "gui.chest-type-single")));
            lore.add(lang.getMessage("gui.item-fraction-display", "%fraction%", 
                lang.getMessage("gui.fraction." + getItemFraction(tier))));
            
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        
        return makeUnmodifiable(icon);
    }
    
    // Edit menu
    public void openEditMenu(Player player, String tier) {
        editingSessions.put(player.getUniqueId(), tier);
        
        boolean isDouble = isDoubleChest(tier);
        int inventorySize = isDouble ? 54 : 27;
        int chestArea = isDouble ? 45 : 18;
        
        Inventory inv = Bukkit.createInventory(null, inventorySize, 
            lang.getMessage("gui.edit-" + tier));
        
        // Load existing items (only in chest area)
        List<ItemStack> items = getCustomLoot(tier);
        for (int i = 0; i < items.size() && i < chestArea; i++) {
            inv.setItem(i, items.get(i));
        }
        
        // Control buttons in LAST ROW
        int controlRow = chestArea;
        
        // Chest type toggle (slot 1)
        inv.setItem(controlRow + 1, createChestTypeButton(tier));
        
        // Fraction decrease button (slot 3)
        inv.setItem(controlRow + 3, createFractionButton(tier, false));
        
        // Fraction display (slot 4)
        inv.setItem(controlRow + 4, createFractionDisplay(tier));
        
        // Fraction increase button (slot 5)
        inv.setItem(controlRow + 5, createFractionButton(tier, true));
        
        // Save button (slot 6)
        inv.setItem(controlRow + 6, createSaveButton());
        
        // Back button (slot 7)
        inv.setItem(controlRow + 7, createBackButton());
        
        // Reset button (slot 8)
        inv.setItem(controlRow + 8, createResetButton());
        
        player.openInventory(inv);
    }
    
    private ItemStack createChestTypeButton(String tier) {
        boolean isDouble = isDoubleChest(tier);
        ItemStack button = new ItemStack(isDouble ? Material.CHEST_MINECART : Material.MINECART);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.getMessage("gui.chest-type-toggle"));
            
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("gui.current-type", "%type%", 
                lang.getMessage(isDouble ? "gui.chest-type-double" : "gui.chest-type-single")));
            lore.add("");
            lore.add(lang.getMessage("gui.chest-type-info"));
            lore.add(lang.getMessage("gui.chest-type-single-desc"));
            lore.add(lang.getMessage("gui.chest-type-double-desc"));
            
            meta.setLore(lore);
            button.setItemMeta(meta);
        }
        return makeUnmodifiable(button);
    }
    
    private ItemStack createFractionButton(String tier, boolean increase) {
        ItemStack button = new ItemStack(increase ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(increase ? 
                lang.getMessage("gui.fraction-increase") : 
                lang.getMessage("gui.fraction-decrease"));
            meta.setLore(lang.getMessageList(increase ? 
                "gui.fraction-increase-lore" : 
                "gui.fraction-decrease-lore"));
            button.setItemMeta(meta);
        }
        return makeUnmodifiable(button);
    }
    
    private ItemStack createFractionDisplay(String tier) {
        String fraction = getItemFraction(tier);
        ItemStack display = new ItemStack(Material.PAPER);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.getMessage("gui.current-fraction"));
            
            List<String> lore = new ArrayList<>();
            lore.add(lang.getMessage("gui.fraction-info"));
            lore.add("");
            lore.add(lang.getMessage("gui.fraction." + fraction));
            
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        return makeUnmodifiable(display);
    }
    
    private ItemStack createSaveButton() {
        ItemStack button = new ItemStack(Material.EMERALD);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.getMessage("gui.save-button"));
            meta.setLore(lang.getMessageList("gui.save-lore"));
            button.setItemMeta(meta);
        }
        return makeUnmodifiable(button);
    }
    
    private ItemStack createBackButton() {
        ItemStack button = new ItemStack(Material.ARROW);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.getMessage("gui.back-button"));
            button.setItemMeta(meta);
        }
        return makeUnmodifiable(button);
    }
    
    private ItemStack createResetButton() {
        ItemStack button = new ItemStack(Material.BARRIER);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.getMessage("gui.reset-button"));
            meta.setLore(lang.getMessageList("gui.reset-lore"));
            button.setItemMeta(meta);
        }
        return makeUnmodifiable(button);
    }
    
    // ==================== RESET CONFIRMATION MENUS ====================
    
    // First confirmation menu
    public void openResetConfirmMenu(Player player, String tier) {
        String tierName = lang.getMessage("gui.reset-confirm-title-" + tier);
        
        Inventory inv = Bukkit.createInventory(null, 27, 
            lang.getMessage("gui.reset-first-menu-title", "%tier%", tierName));
        
        // Warning sign in center (slot 13)
        ItemStack warning = new ItemStack(Material.ORANGE_CONCRETE);
        ItemMeta warnMeta = warning.getItemMeta();
        if (warnMeta != null) {
            warnMeta.setDisplayName(lang.getMessage("gui.reset-first-warning"));
            warnMeta.setLore(lang.getMessageList("gui.reset-first-warning-lore",
                "%tier%", tier,
                "%count%", String.valueOf(getCustomLootCount(tier))));
            warning.setItemMeta(warnMeta);
        }
        inv.setItem(13, makeUnmodifiable(warning));
        
        // Continue button (slot 11)
        ItemStack continueBtn = new ItemStack(Material.YELLOW_CONCRETE);
        ItemMeta continueMeta = continueBtn.getItemMeta();
        if (continueMeta != null) {
            continueMeta.setDisplayName(lang.getMessage("gui.reset-continue"));
            continueMeta.setLore(lang.getMessageList("gui.reset-continue-lore"));
            continueBtn.setItemMeta(continueMeta);
        }
        inv.setItem(11, makeUnmodifiable(continueBtn));
        
        // Cancel button (slot 15)
        ItemStack cancel = new ItemStack(Material.GREEN_CONCRETE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName(lang.getMessage("gui.reset-cancel"));
            cancelMeta.setLore(lang.getMessageList("gui.reset-cancel-lore"));
            cancel.setItemMeta(cancelMeta);
        }
        inv.setItem(15, makeUnmodifiable(cancel));
        
        player.openInventory(inv);
    }
    
    // Second confirmation menu (final)
    public void openResetFinalConfirm(Player player, String tier) {
        String tierName = lang.getMessage("gui.reset-confirm-title-" + tier);
        
        Inventory inv = Bukkit.createInventory(null, 27, 
            lang.getMessage("gui.reset-final-menu-title", "%tier%", tierName));
        
        // Final warning in center (slot 13)
        ItemStack warning = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta warnMeta = warning.getItemMeta();
        if (warnMeta != null) {
            warnMeta.setDisplayName(lang.getMessage("gui.reset-final-warning"));
            warnMeta.setLore(lang.getMessageList("gui.reset-final-warning-lore",
                "%tier%", tier,
                "%count%", String.valueOf(getCustomLootCount(tier))));
            warning.setItemMeta(warnMeta);
        }
        inv.setItem(13, makeUnmodifiable(warning));
        
        // Confirm reset button (slot 11)
        ItemStack confirmBtn = new ItemStack(Material.RED_CONCRETE);
        ItemMeta confirmMeta = confirmBtn.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.setDisplayName(lang.getMessage("gui.reset-confirm-final"));
            confirmMeta.setLore(lang.getMessageList("gui.reset-confirm-final-lore"));
            confirmBtn.setItemMeta(confirmMeta);
        }
        inv.setItem(11, makeUnmodifiable(confirmBtn));
        
        // Cancel button (slot 15)
        ItemStack cancel = new ItemStack(Material.GREEN_CONCRETE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName(lang.getMessage("gui.reset-cancel"));
            cancelMeta.setLore(lang.getMessageList("gui.reset-cancel-lore"));
            cancel.setItemMeta(cancelMeta);
        }
        inv.setItem(15, makeUnmodifiable(cancel));
        
        player.openInventory(inv);
    }
    
    // Make item unmodifiable (visual enchant glow)
    public ItemStack makeUnmodifiable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }
    
    // ==================== BUTTON HANDLERS ====================
    
    public void handleChestTypeToggle(Player player, String tier) {
        boolean currentDouble = isDoubleChest(tier);
        boolean newDouble = !currentDouble;
        
        // Get current inventory before closing
        Inventory currentInv = player.getOpenInventory().getTopInventory();
        int oldChestArea = getChestAreaSize(tier);
        
        // Save all items from current chest
        List<ItemStack> savedItems = new ArrayList<>();
        for (int i = 0; i < oldChestArea; i++) {
            ItemStack item = currentInv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                savedItems.add(item.clone());
            }
        }
        
        // Update config
        lootConfig.set(tier + ".double-chest", newDouble);
        
        try {
            lootConfig.save(lootFile);
        } catch (IOException e) {
            player.sendMessage(lang.getMessage("gui.chest-type-change-error"));
            return;
        }
        
        // Calculate new chest area size
        int newChestArea = newDouble ? 45 : 18;
        
        // Separate items that fit and don't fit
        List<ItemStack> itemsToKeep = new ArrayList<>();
        List<ItemStack> itemsToDrop = new ArrayList<>();
        
        if (savedItems.size() <= newChestArea) {
            // All items fit - keep everything
            itemsToKeep.addAll(savedItems);
        } else {
            // Some items don't fit
            for (int i = 0; i < savedItems.size(); i++) {
                if (i < newChestArea) {
                    itemsToKeep.add(savedItems.get(i));
                } else {
                    itemsToDrop.add(savedItems.get(i));
                }
            }
        }
        
        // Save items that fit
        List<Map<String, Object>> itemsList = new ArrayList<>();
        for (ItemStack item : itemsToKeep) {
            itemsList.add(item.serialize());
        }
        lootConfig.set(tier + ".items", itemsList);
        
        try {
            lootConfig.save(lootFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Error saving items: " + e.getMessage());
        }
        
        // Drop items that don't fit
        if (!itemsToDrop.isEmpty()) {
            Location dropLoc = player.getLocation();
            for (ItemStack item : itemsToDrop) {
                player.getWorld().dropItemNaturally(dropLoc, item);
            }
            
            player.sendMessage(lang.getMessage("gui.chest-size-items-dropped", 
                "%count%", String.valueOf(itemsToDrop.size())));
        }
        
        // Send message about chest type change
        player.sendMessage(lang.getMessage("gui.chest-type-changed", 
            "%type%", lang.getMessage(newDouble ? "gui.chest-type-double" : "gui.chest-type-single")));
        
        // Reopen menu with new size
        Bukkit.getScheduler().runTaskLater(plugin, () -> openEditMenu(player, tier), 1L);
    }
    
    public void handleFractionChange(Player player, String tier, boolean increase) {
        String currentFraction = getItemFraction(tier);
        int currentIndex = Arrays.asList(FRACTIONS).indexOf(currentFraction);
        
        if (currentIndex == -1) currentIndex = 2; // Default to "one-third"
        
        int newIndex;
        if (increase) {
            newIndex = Math.max(0, currentIndex - 1);
        } else {
            newIndex = Math.min(FRACTIONS.length - 1, currentIndex + 1);
        }
        
        String newFraction = FRACTIONS[newIndex];
        lootConfig.set(tier + ".item-fraction", newFraction);
        
        try {
            lootConfig.save(lootFile);
        } catch (IOException e) {
            player.sendMessage(lang.getMessage("gui.fraction-change-error"));
            return;
        }
        
        player.sendMessage(lang.getMessage("gui.fraction-changed", 
            "%fraction%", lang.getMessage("gui.fraction." + newFraction)));
        
        // Reopen menu to update display
        Bukkit.getScheduler().runTaskLater(plugin, () -> openEditMenu(player, tier), 1L);
    }
    
    public void saveCustomLoot(String tier, Inventory inventory) {
        int chestArea = getChestAreaSize(tier);
        List<Map<String, Object>> itemsList = new ArrayList<>();
        
        for (int i = 0; i < chestArea; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                itemsList.add(item.serialize());
            }
        }
        
        lootConfig.set(tier + ".enabled", !itemsList.isEmpty());
        lootConfig.set(tier + ".items", itemsList);
        
        try {
            lootConfig.save(lootFile);
            plugin.getLogger().info(lang.getMessage("system.custom-loot-saved"));
        } catch (IOException e) {
            plugin.getLogger().severe(lang.getMessage("system.custom-loot-save-error", 
                "%error%", e.getMessage()));
        }
    }
    
    // Reset ONLY items, keep settings (double-chest, fraction)
    public void resetCustomLootWithDrop(Player player, String tier) {
        // Get all items before reset
        List<ItemStack> items = getCustomLoot(tier);
        
        // Drop all items at player location
        if (!items.isEmpty()) {
            Location dropLoc = player.getLocation();
            for (ItemStack item : items) {
                player.getWorld().dropItemNaturally(dropLoc, item);
            }
            
            player.sendMessage(lang.getMessage("gui.reset-items-dropped", 
                "%count%", String.valueOf(items.size())));
        }
        
        // Reset ONLY items and enabled status
        // KEEP double-chest and item-fraction settings!
        lootConfig.set(tier + ".enabled", false);
        lootConfig.set(tier + ".items", new ArrayList<>());
        // DON'T touch: tier + ".double-chest"
        // DON'T touch: tier + ".item-fraction"
        
        try {
            lootConfig.save(lootFile);
            player.sendMessage(lang.getMessage("gui.reset-complete", "%tier%", tier));
        } catch (IOException e) {
            plugin.getLogger().severe(lang.getMessage("system.custom-loot-save-error", 
                "%error%", e.getMessage()));
        }
    }
    
    // ==================== SESSION MANAGEMENT ====================
    
    public String getEditingTier(UUID playerId) {
        return editingSessions.get(playerId);
    }
    
    public void clearEditingState(UUID playerId) {
        editingSessions.remove(playerId);
    }
    
    public boolean isEditingInventory(String title) {
        return title.contains(lang.getMessage("gui.edit-common")) ||
               title.contains(lang.getMessage("gui.edit-rare")) ||
               title.contains(lang.getMessage("gui.edit-legendary"));
    }
    
    // Check if inventory is first confirmation menu
    public boolean isFirstConfirmMenu(String title) {
        // Check if contains the first menu identifier
        return title.contains("§6⚠ Reset Confirmation:") || 
               title.contains(lang.getMessage("gui.reset-first-menu-title", "%tier%", "").substring(0, Math.min(10, lang.getMessage("gui.reset-first-menu-title", "%tier%", "").length())));
    }
    
    // Check if inventory is final confirmation menu
    public boolean isFinalConfirmMenu(String title) {
        // Check if contains the final menu identifier
        return title.contains("§4§l⚠ FINAL WARNING:") || 
               title.contains(lang.getMessage("gui.reset-final-menu-title", "%tier%", "").substring(0, Math.min(10, lang.getMessage("gui.reset-final-menu-title", "%tier%", "").length())));
    }
    
    // Get tier from any confirmation menu
    public String getTierFromConfirmMenu(String title) {
        if (title.contains(lang.getMessage("gui.reset-confirm-title-common"))) {
            return "common";
        } else if (title.contains(lang.getMessage("gui.reset-confirm-title-rare"))) {
            return "rare";
        } else if (title.contains(lang.getMessage("gui.reset-confirm-title-legendary"))) {
            return "legendary";
        }
        return null;
    }
}