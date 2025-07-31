package com.myplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
import java.util.HashMap;

public class SpawnChestPlugin extends JavaPlugin implements Listener {
    private long lastSpawnTime = 0L;
    private long spawnInterval;
    private final Set<Long> warningTimes = new HashSet<>(Arrays.asList(24000L, 12000L, 6000L, 3600L, 1200L));
    private final Set<Long> warned = new HashSet<>();
    private final Set<Integer> lastSecondsWarned = new HashSet<>();
    private final Random random = new Random();
    
    // Отслеживание открытых сундуков
    private final Set<String> openedChests = new HashSet<>();
    
    // Система перезарядки способностей
    private final Map<UUID, Long> swordCooldown = new HashMap<>();
    private final Map<UUID, Long> axeCooldown = new HashMap<>();
    private final Map<UUID, Long> hammerCooldown = new HashMap<>();
    private final Map<UUID, Long> bowCooldown = new HashMap<>();
    private final Map<UUID, Long> shovelCooldown = new HashMap<>();
    private final Map<UUID, Long> pickaxeCooldown = new HashMap<>();
    private final Map<UUID, Long> treeCutCooldown = new HashMap<>();
    
    // Время перезарядки в миллисекундах
    private static final long SWORD_COOLDOWN = 3000L;      // 3 секунды
    private static final long AXE_COOLDOWN = 4000L;        // 4 секунды  
    private static final long HAMMER_COOLDOWN = 8000L;     // 8 секунд
    private static final long BOW_COOLDOWN = 2000L;        // 2 секунды
    private static final long SHOVEL_COOLDOWN = 5000L;     // 5 секунд
    private static final long PICKAXE_COOLDOWN = 1000L;    // 1 секунда
    private static final long TREE_CUT_COOLDOWN = 6000L;   // 6 секунд
    
    // Файл для сохранения времени
    private File dataFile;
    
    // Ключи для кастомных предметов
    private NamespacedKey LEGENDARY_SWORD_KEY;
    private NamespacedKey MASTER_PICKAXE_KEY;
    private NamespacedKey PHOENIX_FEATHER_KEY;
    private NamespacedKey GUARDIAN_BOW_KEY;
    private NamespacedKey TITAN_AXE_KEY;
    private NamespacedKey VOID_SHOVEL_KEY;
    private NamespacedKey STORM_HAMMER_KEY;
    private NamespacedKey WISDOM_BOOK_KEY;
    
    // Типы сундуков
    public enum ChestTier {
        COMMON(0.7, "§fОбычный сундук", "§7Содержит базовые предметы"),
        RARE(0.25, "§9Редкий сундук", "§7Содержит ценные предметы"),
        LEGENDARY(0.05, "§6Легендарный сундук", "§7Содержит эпические сокровища!");
        
        public final double chance;
        public final String displayName;
        public final String description;
        
        ChestTier(double chance, String displayName, String description) {
            this.chance = chance;
            this.displayName = displayName;
            this.description = description;
        }
    }

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.reloadConfig();
        this.spawnInterval = this.getConfig().getLong("settings.spawn-interval-seconds", 1800L) * 1000L;
        
        // Инициализируем ключи для кастомных предметов
        LEGENDARY_SWORD_KEY = new NamespacedKey(this, "legendary_sword");
        MASTER_PICKAXE_KEY = new NamespacedKey(this, "master_pickaxe");
        PHOENIX_FEATHER_KEY = new NamespacedKey(this, "phoenix_feather");
        GUARDIAN_BOW_KEY = new NamespacedKey(this, "guardian_bow");
        TITAN_AXE_KEY = new NamespacedKey(this, "titan_axe");
        VOID_SHOVEL_KEY = new NamespacedKey(this, "void_shovel");
        STORM_HAMMER_KEY = new NamespacedKey(this, "storm_hammer");
        WISDOM_BOOK_KEY = new NamespacedKey(this, "wisdom_book");
        
        // Создаем папку для данных
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "timer.txt");
        
        // Загружаем сохраненное время
        loadTimer();
        
        // Регистрируем обработчики событий
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Запускаем основной таймер
        new ChestTimer().runTaskTimer(this, 0L, 20L);
        
        // Таймер для пассивных эффектов Пера Феникса
        new PhoenixFeatherPassiveTask().runTaskTimer(this, 0L, 40L);
        
        getLogger().info("SpawnChestPlugin v2.0 BALANCED включен! Сбалансированные эпичные сундуки!");
    }

    @Override
    public void onDisable() {
        saveTimer();
        getLogger().info("SpawnChestPlugin выключен!");
    }
    
    private void loadTimer() {
        if (dataFile.exists()) {
            try (FileReader reader = new FileReader(dataFile)) {
                Scanner scanner = new Scanner(reader);
                if (scanner.hasNextLong()) {
                    lastSpawnTime = scanner.nextLong();
                    getLogger().info("Загружено время последнего спавна: " + lastSpawnTime);
                } else {
                    lastSpawnTime = System.currentTimeMillis();
                }
            } catch (IOException e) {
                getLogger().warning("Не удалось загрузить время: " + e.getMessage());
                lastSpawnTime = System.currentTimeMillis();
            }
        } else {
            lastSpawnTime = System.currentTimeMillis();
        }
    }
    
    private void saveTimer() {
        try (FileWriter writer = new FileWriter(dataFile)) {
            writer.write(String.valueOf(lastSpawnTime));
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить время: " + e.getMessage());
        }
    }

    private void spawnChest() {
        World world = Bukkit.getWorld("world");
        if (world == null) {
            getLogger().severe("Мир 'world' не найден!");
            return;
        }

        // Асинхронный поиск места для избежания лагов
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Location location = findSafeSpawnLocationAsync(world);
                
                // Спавним сундук в главном потоке
                Bukkit.getScheduler().runTask(this, () -> {
                    if (location != null) {
                        spawnChestAtLocation(location);
                    } else {
                        getLogger().warning("Не удалось найти подходящее место для сундука.");
                        // Используем спавн мира как fallback
                        Location fallback = world.getSpawnLocation().add(0, 1, 0);
                        spawnChestAtLocation(fallback);
                    }
                });
            } catch (Exception e) {
                getLogger().severe("Ошибка при спавне сундука: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    private Location findSafeSpawnLocationAsync(World world) {
        // Зона спавна от 500 до 2000 блоков
        for (int attempts = 0; attempts < 100; attempts++) {
            double x = (random.nextDouble() * 1500.0 + 500.0) * (random.nextBoolean() ? 1 : -1);
            double z = (random.nextDouble() * 1500.0 + 500.0) * (random.nextBoolean() ? 1 : -1);
            
            int chunkX = (int) x >> 4;
            int chunkZ = (int) z >> 4;
            
            // Принудительно загружаем чанк если нужно
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            
            try {
                int y = world.getHighestBlockYAt((int) x, (int) z);
                
                if (y > 60 && y < 200) {
                    Material surface = world.getBlockAt((int) x, y - 1, (int) z).getType();
                    Material above = world.getBlockAt((int) x, y, (int) z).getType();
                    Material above2 = world.getBlockAt((int) x, y + 1, (int) z).getType();
                    
                    if (surface.isSolid() && 
                        surface != Material.BEDROCK && 
                        surface != Material.BARRIER &&
                        surface != Material.LAVA &&
                        !surface.toString().contains("WATER") &&
                        above == Material.AIR &&
                        above2 == Material.AIR) {
                        
                        getLogger().info("Найдено место для сундука: X=" + (int)x + " Y=" + y + " Z=" + (int)z);
                        return new Location(world, x, y, z);
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        getLogger().warning("Не удалось найти место в дальней зоне, используем случайную точку на земле");
        double fallbackX = (random.nextDouble() * 1500.0 + 500.0) * (random.nextBoolean() ? 1 : -1);
        double fallbackZ = (random.nextDouble() * 1500.0 + 500.0) * (random.nextBoolean() ? 1 : -1);
        int fallbackY = world.getHighestBlockYAt((int)fallbackX, (int)fallbackZ);
        return new Location(world, fallbackX, fallbackY, fallbackZ);
    }
    
    private void spawnChestAtLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            getLogger().warning("Некорректная локация для спавна сундука!");
            return;
        }
        
        ChestTier tier = determineChestTier();
        
        Block block = location.getWorld().getBlockAt(location);
        block.setType(Material.CHEST);
        Chest chest = (Chest) block.getState();
        
        fillChestWithLoot(chest.getInventory(), tier);
        spawnGuardians(location, tier);
        createSpawnEffects(location, tier);
        broadcastChestSpawn(location, tier);
        
        lastSpawnTime = System.currentTimeMillis();
        saveTimer();
    }
    
    private ChestTier determineChestTier() {
        double roll = random.nextDouble();
        double cumulative = 0.0;
        
        for (ChestTier tier : ChestTier.values()) {
            cumulative += tier.chance;
            if (roll <= cumulative) {
                return tier;
            }
        }
        return ChestTier.COMMON;
    }
    
    private void fillChestWithLoot(Inventory inventory, ChestTier tier) {
        List<ItemStack> loot = generateLoot(tier);
        Collections.shuffle(loot);
        
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots);
        
        int itemsToPlace = Math.min(loot.size(), 
            tier == ChestTier.LEGENDARY ? 20 : 
            tier == ChestTier.RARE ? 15 : 12);
        
        for (int i = 0; i < itemsToPlace && i < slots.size(); i++) {
            inventory.setItem(slots.get(i), loot.get(i));
        }
    }
    
    private List<ItemStack> generateLoot(ChestTier tier) {
        List<ItemStack> loot = new ArrayList<>();
        
        loot.addAll(getBasicItems(tier));
        loot.addAll(getWeaponsAndArmor(tier));
        loot.addAll(getTools(tier));
        loot.addAll(getPotions(tier));
        loot.addAll(getFood(tier));
        
        if (tier == ChestTier.RARE || tier == ChestTier.LEGENDARY) {
            loot.addAll(getUniqueItems(tier));
        }
        
        if (tier == ChestTier.LEGENDARY) {
            loot.addAll(getLegendaryItems());
        }
        
        return loot;
    }
    
    private List<ItemStack> getBasicItems(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        Material[] basics = {
            Material.DIAMOND, Material.EMERALD, Material.IRON_INGOT, Material.GOLD_INGOT,
            Material.NETHERITE_SCRAP, Material.ANCIENT_DEBRIS, Material.EXPERIENCE_BOTTLE,
            Material.ENDER_PEARL, Material.NAME_TAG, Material.SADDLE, Material.TOTEM_OF_UNDYING,
            Material.LAPIS_LAZULI, Material.REDSTONE, Material.COAL, Material.COPPER_INGOT,
            Material.BLAZE_ROD, Material.ENDER_EYE, Material.SLIME_BALL, Material.SHULKER_SHELL,
            Material.PRISMARINE_SHARD, Material.PRISMARINE_CRYSTALS, Material.RABBIT_FOOT,
            Material.GHAST_TEAR, Material.MAGMA_CREAM, Material.NETHER_STAR, Material.DRAGON_BREATH,
            Material.PHANTOM_MEMBRANE, Material.HEART_OF_THE_SEA, Material.NAUTILUS_SHELL,
            Material.ECHO_SHARD, Material.RECOVERY_COMPASS, Material.DISC_FRAGMENT_5
        };
        
        int count = tier == ChestTier.LEGENDARY ? 8 : tier == ChestTier.RARE ? 5 : 3;
        for (int i = 0; i < count; i++) {
            Material mat = basics[random.nextInt(basics.length)];
            int amount = tier == ChestTier.LEGENDARY ? 3 + random.nextInt(5) : 
                        tier == ChestTier.RARE ? 2 + random.nextInt(3) : 1 + random.nextInt(2);
            items.add(new ItemStack(mat, Math.min(amount, mat.getMaxStackSize())));
        }
        
        return items;
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
        
        for (int i = 0; i < (tier == ChestTier.LEGENDARY ? 3 : 2); i++) {
            Material weapon = weapons[random.nextInt(weapons.length)];
            ItemStack item = new ItemStack(weapon);
            if (tier != ChestTier.COMMON) {
                item = enchantItem(item, tier);
            }
            items.add(item);
        }
        
        for (int i = 0; i < (tier == ChestTier.LEGENDARY ? 4 : tier == ChestTier.RARE ? 2 : 1); i++) {
            Material armorPiece = armor[random.nextInt(armor.length)];
            ItemStack item = new ItemStack(armorPiece);
            if (tier != ChestTier.COMMON) {
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
        
        int count = tier == ChestTier.LEGENDARY ? 4 : tier == ChestTier.RARE ? 3 : 2;
        for (int i = 0; i < count; i++) {
            Material tool = tools[random.nextInt(tools.length)];
            ItemStack item = new ItemStack(tool);
            if (tier != ChestTier.COMMON && random.nextDouble() < 0.7) {
                item = enchantItem(item, tier);
            }
            items.add(item);
        }
        
        return items;
    }
    
    private List<ItemStack> getPotions(ChestTier tier) {
        List<ItemStack> items = new ArrayList<>();
        
        items.add(createPotion(PotionEffectType.SPEED, 3600, 1));
        items.add(createPotion(PotionEffectType.STRENGTH, 3600, 1));
        items.add(createPotion(PotionEffectType.REGENERATION, 900, 1));
        items.add(createPotion(PotionEffectType.FIRE_RESISTANCE, 3600, 0));
        items.add(createPotion(PotionEffectType.NIGHT_VISION, 3600, 0));
        
        if (tier == ChestTier.RARE || tier == ChestTier.LEGENDARY) {
            items.add(createPotion(PotionEffectType.STRENGTH, 1800, 2));
            items.add(createPotion(PotionEffectType.SPEED, 1800, 2));
            items.add(createPotion(PotionEffectType.JUMP_BOOST, 3600, 2));
        }
        
        if (tier == ChestTier.LEGENDARY) {
            items.add(createPotion(PotionEffectType.REGENERATION, 400, 2));
            items.add(createPotion(PotionEffectType.ABSORPTION, 2400, 1));
        }
        
        return items;
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
        
        return items;
    }
    
    private List<ItemStack> getLegendaryItems() {
        List<ItemStack> items = new ArrayList<>();
        
        items.add(createLegendarySword());
        items.add(createMasterPickaxe());
        items.add(createTitanAxe());
        items.add(createVoidShovel());
        items.add(createStormHammer());
        items.add(createGuardianBow());
        items.add(createWisdomBook());
        items.add(createPhoenixFeather());
        
        return items;
    }
    
    private ItemStack enchantItem(ItemStack item, ChestTier tier) {
        String itemType = item.getType().toString();
        int maxLevel = tier == ChestTier.LEGENDARY ? 6 : tier == ChestTier.RARE ? 5 : 3;
        
        if (itemType.contains("SWORD")) {
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.UNBREAKING, random.nextInt(5) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, random.nextInt(2) + 1);
                item.addUnsafeEnchantment(Enchantment.LOOTING, random.nextInt(4) + 1);
                item.addUnsafeEnchantment(Enchantment.SWEEPING_EDGE, random.nextInt(3) + 1);
            }
        } else if (itemType.contains("BOW")) {
            item.addUnsafeEnchantment(Enchantment.POWER, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.PUNCH, random.nextInt(2) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.FLAME, 1);
                if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.INFINITY, 1);
            }
        } else if (itemType.contains("PICKAXE")) {
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.FORTUNE, random.nextInt(4) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, random.nextInt(5) + 1);
                item.addUnsafeEnchantment(Enchantment.MENDING, 1);
            }
        } else if (itemType.contains("AXE")) {
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.SHARPNESS, random.nextInt(maxLevel) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, random.nextInt(5) + 1);
                item.addUnsafeEnchantment(Enchantment.FORTUNE, random.nextInt(3) + 1);
            }
        } else if (itemType.contains("SHOVEL")) {
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, random.nextInt(maxLevel) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, random.nextInt(5) + 1);
                item.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1);
            }
        } else if (itemType.contains("HELMET") || itemType.contains("CHESTPLATE") || 
                   itemType.contains("LEGGINGS") || itemType.contains("BOOTS")) {
            item.addUnsafeEnchantment(Enchantment.PROTECTION, random.nextInt(maxLevel) + 1);
            if (random.nextBoolean()) item.addUnsafeEnchantment(Enchantment.UNBREAKING, random.nextInt(5) + 1);
            if (tier == ChestTier.LEGENDARY && random.nextBoolean()) {
                item.addUnsafeEnchantment(Enchantment.MENDING, 1);
                if (itemType.contains("BOOTS")) {
                    item.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, random.nextInt(5) + 1);
                    item.addUnsafeEnchantment(Enchantment.DEPTH_STRIDER, random.nextInt(3) + 1);
                }
                if (itemType.contains("HELMET")) {
                    item.addUnsafeEnchantment(Enchantment.AQUA_AFFINITY, 1);
                    item.addUnsafeEnchantment(Enchantment.RESPIRATION, random.nextInt(4) + 1);
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
    
    // === КАСТОМНЫЕ ПРЕДМЕТЫ ===
    
    private ItemStack createLegendarySword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§6⚔ Клинок Драконобойца ⚔"));
            meta.lore(Arrays.asList(
                Component.text("§7Легендарный меч, выкованный"),
                Component.text("§7из сердца дракона. Пылает"),
                Component.text("§7вечным огнем и жаждет битвы."),
                Component.text("§c+4 Урон ⚔ (15.5 макс)"),
                Component.text("§e⚡ Поджигает врагов на 5с"),
                Component.text("§cПерезарядка: 3 секунды"),
                Component.text("§6Легендарный предмет")
            ));
            meta.addEnchant(Enchantment.SHARPNESS, 6, true);
            meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.addEnchant(Enchantment.LOOTING, 4, true);
            meta.addEnchant(Enchantment.SWEEPING_EDGE, 4, true);
            
            meta.getPersistentDataContainer().set(LEGENDARY_SWORD_KEY, PersistentDataType.STRING, "true");
            sword.setItemMeta(meta);
        }
        return sword;
    }
    
    private ItemStack createMasterPickaxe() {
        ItemStack pickaxe = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = pickaxe.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§b⛏ Кирка Мастера ⛏"));
            meta.lore(Arrays.asList(
                Component.text("§7Инструмент великого мастера,"),
                Component.text("§7способный пробить любую породу"),
                Component.text("§7и принести богатства."),
                Component.text("§e⚡ Автоплавка руды"),
                Component.text("§e⚡ Шанс дропа x2"),
                Component.text("§e⚡ Добывает быстрее в 1.5 раза"),
                Component.text("§cПерезарядка: 1 секунда"),
                Component.text("§6Легендарный предмет")
            ));
            meta.addEnchant(Enchantment.EFFICIENCY, 6, true);
            meta.addEnchant(Enchantment.FORTUNE, 4, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            
            meta.getPersistentDataContainer().set(MASTER_PICKAXE_KEY, PersistentDataType.STRING, "true");
            pickaxe.setItemMeta(meta);
        }
        return pickaxe;
    }
    
    private ItemStack createTitanAxe() {
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = axe.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§4🪓 Топор Титана 🪓"));
            meta.lore(Arrays.asList(
                Component.text("§7Мощный топор древних титанов,"),
                Component.text("§7способный срубить целый лес"),
                Component.text("§7одним ударом."),
                Component.text("§c+3 Урон ⚔ (16 макс)"),
                Component.text("§e⚡ Срубает все дерево сразу"),
                Component.text("§cПерезарядка боя: 4 секунды"),
                Component.text("§cПерезарядка срубки: 6 секунд"),
                Component.text("§6Легендарный предмет")
            ));
            meta.addEnchant(Enchantment.EFFICIENCY, 6, true);
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            
            meta.getPersistentDataContainer().set(TITAN_AXE_KEY, PersistentDataType.STRING, "true");
            axe.setItemMeta(meta);
        }
        return axe;
    }
    
    private ItemStack createVoidShovel() {
        ItemStack shovel = new ItemStack(Material.NETHERITE_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§5🕳 Лопата Пустоты 🕳"));
            meta.lore(Arrays.asList(
                Component.text("§7Таинственная лопата из пустоты,"),
                Component.text("§7поглощающая землю и камень"),
                Component.text("§7в бездонную тьму."),
                Component.text("§e⚡ Копает 3x3 область (SHIFT)"),
                Component.text("§e⚡ Автосбор в инвентарь"),
                Component.text("§e⚡ Сверхбыстрая добыча"),
                Component.text("§cПерезарядка: 5 секунд"),
                Component.text("§6Легендарный предмет")
            ));
            meta.addEnchant(Enchantment.EFFICIENCY, 7, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            meta.addEnchant(Enchantment.SILK_TOUCH, 1, true);
            
            meta.getPersistentDataContainer().set(VOID_SHOVEL_KEY, PersistentDataType.STRING, "true");
            shovel.setItemMeta(meta);
        }
        return shovel;
    }
    
    private ItemStack createStormHammer() {
        ItemStack hammer = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = hammer.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§9⚡ Молот Бури ⚡"));
            meta.lore(Arrays.asList(
                Component.text("§7Молот повелителя гроз, призывающий"),
                Component.text("§7молнии и сокрушающий врагов"),
                Component.text("§7силой самих небес."),
                Component.text("§c+5 Урон ⚔ (18.5 макс)"),
                Component.text("§e⚡ Призывает молнии"),
                Component.text("§e⚡ Урон по области 3x3"),
                Component.text("§cПерезарядка: 8 секунд"),
                Component.text("§6Легендарный предмет")
            ));
            meta.addEnchant(Enchantment.SHARPNESS, 6, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);
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
            meta.displayName(Component.text("§a🏹 Лук Стража 🏹"));
            meta.lore(Arrays.asList(
                Component.text("§7Лук защитника королевства,"),
                Component.text("§7никогда не промахивается"),
                Component.text("§7и поражает любую цель."),
                Component.text("§c+6 Урон 🏹 (26 макс)"),
                Component.text("§e⚡ Двойной выстрел"),
                Component.text("§e⚡ Самонаводящиеся стрелы"),
                Component.text("§cПерезарядка: 2 секунды"),
                Component.text("§6Легендарный предмет")
            ));
            meta.addEnchant(Enchantment.POWER, 6, true);
            meta.addEnchant(Enchantment.INFINITY, 1, true);
            meta.addEnchant(Enchantment.PUNCH, 2, true);
            meta.addEnchant(Enchantment.FLAME, 1, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);
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
            meta.displayName(Component.text("§d📚 Книга Древней Мудрости 📚"));
            meta.lore(Arrays.asList(
                Component.text("§7Содержит знания древних"),
                Component.text("§7цивилизаций и мощные чары."),
                Component.text("§e⚡ Применить ко всей броне"),
                Component.text("§e⚡ Сбалансированные зачарования"),
                Component.text("§e⚡ ПКМ для использования"),
                Component.text("§6Легендарный предмет")
            ));
            meta.addStoredEnchant(Enchantment.MENDING, 1, true);
            meta.addStoredEnchant(Enchantment.UNBREAKING, 3, true);
            meta.addStoredEnchant(Enchantment.PROTECTION, 4, true);
            meta.addStoredEnchant(Enchantment.SHARPNESS, 5, true);
            meta.addStoredEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.addStoredEnchant(Enchantment.FORTUNE, 3, true);
            meta.addStoredEnchant(Enchantment.LOOTING, 3, true);
            
            meta.getPersistentDataContainer().set(WISDOM_BOOK_KEY, PersistentDataType.STRING, "true");
            book.setItemMeta(meta);
        }
        return book;
    }
    
    private ItemStack createPhoenixFeather() {
        ItemStack feather = new ItemStack(Material.FEATHER);
        ItemMeta meta = feather.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§c🔥 Перо Феникса 🔥"));
            meta.lore(Arrays.asList(
                Component.text("§7Редчайшее перо мифического"),
                Component.text("§7феникса. Дарует бессмертие"),
                Component.text("§7и защиту от огня."),
                Component.text("§e⚡ Воскрешение при смерти"),
                Component.text("§e⚡ Иммунитет к огню (в руке)"),
                Component.text("§e⚡ Полное исцеление"),
                Component.text("§6Легендарный артефакт")
            ));
            
            meta.getPersistentDataContainer().set(PHOENIX_FEATHER_KEY, PersistentDataType.STRING, "true");
            feather.setItemMeta(meta);
        }
        return feather;
    }
    
    // === ОБРАБОТЧИКИ СОБЫТИЙ ===
    
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        
        Player attacker = (Player) event.getDamager();
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        UUID playerId = attacker.getUniqueId();
        
        // Защита от PvP
        if (event.getEntity() instanceof Player) {
            return;
        }
        
        if (!(event.getEntity() instanceof LivingEntity)) return;
        LivingEntity target = (LivingEntity) event.getEntity();
        
        // Клинок Драконобойца
        if (isLegendarySword(weapon)) {
            if (isOnCooldown(swordCooldown, playerId, SWORD_COOLDOWN)) {
                long remaining = getRemainingCooldown(swordCooldown, playerId, SWORD_COOLDOWN);
                attacker.sendMessage("§c⚔ Клинок перезаряжается! Осталось: " + formatCooldown(remaining));
                return;
            }
            
            event.setDamage(event.getDamage() + 4.0);
            setCooldown(swordCooldown, playerId);
            
            try {
                target.setFireTicks(100);
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation(), 8, 0.3, 0.3, 0.3, 0.05);
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_BLAZE_HURT, 0.8f, 1.0f);
                attacker.sendMessage("§6⚔ Клинок Драконобойца пылает! Перезарядка: " + (SWORD_COOLDOWN/1000) + "с");
            } catch (Exception e) {
                getLogger().warning("Ошибка при использовании Клинка Драконобойца: " + e.getMessage());
            }
        }
        
        // Топор Титана
        if (isTitanAxe(weapon)) {
            if (isOnCooldown(axeCooldown, playerId, AXE_COOLDOWN)) {
                long remaining = getRemainingCooldown(axeCooldown, playerId, AXE_COOLDOWN);
                attacker.sendMessage("§c🪓 Топор перезаряжается! Осталось: " + formatCooldown(remaining));
                return;
            }
            
            event.setDamage(event.getDamage() + 3.0);
            setCooldown(axeCooldown, playerId);
            
            try {
                target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation(), 8, 0.3, 0.3, 0.3, 0.1, Material.STONE.createBlockData());
                target.getWorld().playSound(target.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.0f);
                attacker.sendMessage("§4🪓 Топор Титана сокрушает! Перезарядка: " + (AXE_COOLDOWN/1000) + "с");
            } catch (Exception e) {
                getLogger().warning("Ошибка при использовании Топора Титана: " + e.getMessage());
            }
        }
        
        // Молот Бури
        if (isStormHammer(weapon)) {
            if (isOnCooldown(hammerCooldown, playerId, HAMMER_COOLDOWN)) {
                long remaining = getRemainingCooldown(hammerCooldown, playerId, HAMMER_COOLDOWN);
                attacker.sendMessage("§c⚡ Молот перезаряжается! Осталось: " + formatCooldown(remaining));
                return;
            }
            
            event.setDamage(event.getDamage() + 5.0);
            setCooldown(hammerCooldown, playerId);
            
            Location loc = target.getLocation();
            
            try {
                target.getWorld().strikeLightningEffect(loc);
                
                for (LivingEntity nearby : target.getLocation().getNearbyLivingEntities(3)) {
                    if (nearby != attacker && nearby != target && !(nearby instanceof Player)) {
                        double currentHealth = nearby.getHealth();
                        double newHealth = currentHealth - 4.0;
                        if (newHealth <= 0) {
                            nearby.setHealth(0.1);
                        } else {
                            nearby.setHealth(newHealth);
                        }
                    }
                }
                
                target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, loc, 15, 1, 1, 1, 0.1);
                target.getWorld().playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.0f);
                attacker.sendMessage("§9⚡ Молот Бури гремит! Перезарядка: " + (HAMMER_COOLDOWN/1000) + "с");
            } catch (Exception e) {
                getLogger().warning("Ошибка при использовании Молота Бури: " + e.getMessage());
            }
        }
    }
    
    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        
        Player shooter = (Player) event.getEntity();
        ItemStack bow = event.getBow();
        UUID playerId = shooter.getUniqueId();
        
        if (bow == null || !isGuardianBow(bow)) return;
        
        if (isOnCooldown(bowCooldown, playerId, BOW_COOLDOWN)) {
            long remaining = getRemainingCooldown(bowCooldown, playerId, BOW_COOLDOWN);
            shooter.sendMessage("§c🏹 Лук перезаряжается! Осталось: " + formatCooldown(remaining));
            event.setCancelled(true);
            return;
        }
        
        setCooldown(bowCooldown, playerId);
        
        try {
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        Arrow arrow = shooter.launchProjectile(Arrow.class);
                        arrow.setVelocity(event.getProjectile().getVelocity().clone()
                            .rotateAroundY(Math.toRadians(10)));
                        arrow.setCritical(true);
                        arrow.setFireTicks(100);
                        
                        new ArrowHomingTask(arrow, shooter, 5.0).runTaskTimer(SpawnChestPlugin.this, 1L, 1L);
                    } catch (Exception e) {
                        getLogger().warning("Ошибка при выстреле из Лука Стража: " + e.getMessage());
                    }
                }
            }.runTaskLater(this, 1L);
            
            shooter.sendMessage("§a🏹 Лук Стража: двойной выстрел! Перезарядка: " + (BOW_COOLDOWN/1000) + "с");
        } catch (Exception e) {
            getLogger().warning("Ошибка в обработчике Лука Стража: " + e.getMessage());
        }
    }
    
    @EventHandler 
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        Block block = event.getBlock();
        UUID playerId = player.getUniqueId();
        
        try {
            // Кирка Мастера
            if (isMasterPickaxe(tool)) {
                if (isOnCooldown(pickaxeCooldown, playerId, PICKAXE_COOLDOWN)) {
                    long remaining = getRemainingCooldown(pickaxeCooldown, playerId, PICKAXE_COOLDOWN);
                    if (remaining > 500) { // Показываем только если больше 0.5с
                        player.sendMessage("§c⛏ Кирка перезаряжается! Осталось: " + formatCooldown(remaining));
                    }
                    return;
                }
                
                Material blockType = block.getType();
                
                if (isOre(blockType)) {
                    setCooldown(pickaxeCooldown, playerId);
                    event.setDropItems(false);
                    
                    ItemStack smelted = getSmeltedVersion(blockType);
                    if (smelted != null) {
                        int amount = random.nextBoolean() ? 2 : 1;
                        smelted.setAmount(amount);
                        block.getWorld().dropItemNaturally(block.getLocation(), smelted);
                        block.getWorld().spawnParticle(Particle.FLAME, block.getLocation().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2, 0.05);
                        
                        if (random.nextDouble() < 0.3) {
                            player.sendMessage("§b⛏ Кирка переплавляет руду!");
                        }
                    } else {
                        Collection<ItemStack> normalDrops = block.getDrops(tool);
                        for (ItemStack drop : normalDrops) {
                            int amount = drop.getAmount() * (random.nextBoolean() ? 2 : 1);
                            drop.setAmount(Math.min(amount, drop.getType().getMaxStackSize()));
                            block.getWorld().dropItemNaturally(block.getLocation(), drop);
                        }
                        
                        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2, 0.05);
                        
                        if (random.nextDouble() < 0.3) {
                            player.sendMessage("§b⛏ Кирка удваивает дроп!");
                        }
                    }
                }
            }
            
            // Топор Титана для срубки деревьев
            if (isTitanAxe(tool) && isLog(block.getType())) {
                if (isOnCooldown(treeCutCooldown, playerId, TREE_CUT_COOLDOWN)) {
                    long remaining = getRemainingCooldown(treeCutCooldown, playerId, TREE_CUT_COOLDOWN);
                    player.sendMessage("§c🌳 Срубка дерева перезаряжается! Осталось: " + formatCooldown(remaining));
                    return;
                }
                
                setCooldown(treeCutCooldown, playerId);
                
                try {
                    cutDownTree(block, player, tool);
                    if (random.nextDouble() < 0.3) {
                        player.sendMessage("§4🪓 Топор Титана срубает дерево! Перезарядка: " + (TREE_CUT_COOLDOWN/1000) + "с");
                    }
                } catch (Exception e) {
                    getLogger().warning("Ошибка при срубке дерева: " + e.getMessage());
                }
            }
            
            // Лопата Пустоты
            if (isVoidShovel(tool)) {
                if (player.isSneaking()) {
                    if (isOnCooldown(shovelCooldown, playerId, SHOVEL_COOLDOWN)) {
                        long remaining = getRemainingCooldown(shovelCooldown, playerId, SHOVEL_COOLDOWN);
                        player.sendMessage("§c🕳 Лопата перезаряжается! Осталось: " + formatCooldown(remaining));
                        return;
                    }
                    
                    setCooldown(shovelCooldown, playerId);
                    
                    try {
                        int blocksDestroyed = 0;
                        List<ItemStack> allDrops = new ArrayList<>();
                        
                        for (int x = -1; x <= 1; x++) {
                            for (int z = -1; z <= 1; z++) {
                                for (int y = 0; y <= 1; y++) {
                                    if (x == 0 && y == 0 && z == 0) continue;
                                    if (blocksDestroyed >= 26) break;
                                    
                                    Block nearby = block.getRelative(x, y, z);
                                    
                                    if (nearby.getType().isSolid() && 
                                        nearby.getType() != Material.BEDROCK &&
                                        nearby.getType() != Material.BARRIER &&
                                        canDigWithShovel(nearby.getType())) {
                                        
                                        Collection<ItemStack> drops = nearby.getDrops(tool);
                                        allDrops.addAll(drops);
                                        nearby.setType(Material.AIR);
                                        blocksDestroyed++;
                                        
                                        if (blocksDestroyed % 5 == 0) {
                                            nearby.getWorld().spawnParticle(Particle.PORTAL, nearby.getLocation().add(0.5, 0.5, 0.5), 2);
                                        }
                                    }
                                }
                            }
                        }
                        
                        for (ItemStack drop : allDrops) {
                            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(drop);
                            for (ItemStack leftoverItem : leftover.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                            }
                        }
                        
                        block.getWorld().spawnParticle(Particle.PORTAL, block.getLocation(), 20, 1, 1, 1, 0.1);
                        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_PORTAL_AMBIENT, 1.0f, 0.8f);
                        
                        if (blocksDestroyed > 0) {
                            player.sendMessage("§5🕳 Лопата Пустоты выкопала " + blocksDestroyed + " блоков! Перезарядка: " + (SHOVEL_COOLDOWN/1000) + "с");
                        }
                    } catch (Exception e) {
                        getLogger().warning("Ошибка при использовании Лопаты Пустоты: " + e.getMessage());
                        player.sendMessage("§cОшибка при использовании лопаты!");
                    }
                } else {
                    if (random.nextDouble() < 0.2) {
                        player.sendMessage("§7Зажмите §eSHIFT §7для активации области копания!");
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("Критическая ошибка в onBlockBreak: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        
        Player player = (Player) event.getEntity();
        
        if (hasPhoenixFeather(player) && player.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            
            try {
                double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                player.setHealth(maxHealth);
            } catch (Exception e) {
                player.setHealth(20.0);
            }
            
            player.setFireTicks(0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 2));
            
            removePhoenixFeather(player);
            
            Location loc = player.getLocation();
            player.getWorld().spawnParticle(Particle.DRAGON_BREATH, loc, 50, 2, 2, 2, 0.1);
            player.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 1.0f);
            
            player.sendMessage("§c🔥 Перо Феникса спасает вас от смерти!");
            Bukkit.broadcast(Component.text("§6" + player.getName() + " был воскрешен Пером Феникса!"));
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
        
        if (!openedChests.contains(chestKey)) {
            openedChests.add(chestKey);
            player.sendMessage("§e✨ Вы первым открыли этот сундук!");
            getLogger().info("Сундук " + chestKey + " был открыт игроком " + player.getName());
        }
    }
    
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        
        if (item == null || !item.hasItemMeta()) return;
        
        if (hasCustomKey(item, WISDOM_BOOK_KEY)) {
            if (event.getAction().toString().contains("RIGHT_CLICK")) {
                event.setCancelled(true);
                applyWisdomBookToInventory(player, item);
            }
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
                if (bookMeta.hasStoredEnchant(Enchantment.SHARPNESS)) {
                    meta.addEnchant(Enchantment.SHARPNESS, 6, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.UNBREAKING)) {
                    meta.addEnchant(Enchantment.UNBREAKING, 5, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.MENDING)) {
                    meta.addEnchant(Enchantment.MENDING, 1, true);
                    enchanted = true;
                }
                if (itemType.contains("SWORD") && bookMeta.hasStoredEnchant(Enchantment.LOOTING)) {
                    meta.addEnchant(Enchantment.LOOTING, 4, true);
                    enchanted = true;
                }
            } else if (itemType.contains("PICKAXE") || itemType.contains("SHOVEL") || itemType.contains("HOE")) {
                if (bookMeta.hasStoredEnchant(Enchantment.EFFICIENCY)) {
                    meta.addEnchant(Enchantment.EFFICIENCY, 6, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.FORTUNE)) {
                    meta.addEnchant(Enchantment.FORTUNE, 4, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.UNBREAKING)) {
                    meta.addEnchant(Enchantment.UNBREAKING, 5, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.MENDING)) {
                    meta.addEnchant(Enchantment.MENDING, 1, true);
                    enchanted = true;
                }
            } else if (itemType.contains("HELMET") || itemType.contains("CHESTPLATE") || 
                       itemType.contains("LEGGINGS") || itemType.contains("BOOTS")) {
                if (bookMeta.hasStoredEnchant(Enchantment.PROTECTION)) {
                    meta.addEnchant(Enchantment.PROTECTION, 6, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.UNBREAKING)) {
                    meta.addEnchant(Enchantment.UNBREAKING, 5, true);
                    enchanted = true;
                }
                if (bookMeta.hasStoredEnchant(Enchantment.MENDING)) {
                    meta.addEnchant(Enchantment.MENDING, 1, true);
                    enchanted = true;
                }
                
                if (itemType.contains("BOOTS")) {
                    meta.addEnchant(Enchantment.FEATHER_FALLING, 5, true);
                    meta.addEnchant(Enchantment.DEPTH_STRIDER, 3, true);
                    enchanted = true;
                } else if (itemType.contains("HELMET")) {
                    meta.addEnchant(Enchantment.AQUA_AFFINITY, 1, true);
                    meta.addEnchant(Enchantment.RESPIRATION, 3, true);
                    enchanted = true;
                }
            } else if (itemType.contains("BOW")) {
                meta.addEnchant(Enchantment.POWER, 6, true);
                meta.addEnchant(Enchantment.UNBREAKING, 5, true);
                meta.addEnchant(Enchantment.MENDING, 1, true);
                meta.addEnchant(Enchantment.INFINITY, 1, true);
                enchanted = true;
            }
            
            if (enchanted) {
                item.setItemMeta(meta);
                enchantedItems++;
                player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);
            }
        }
        
        if (enchantedItems > 0) {
            if (book.getAmount() > 1) {
                book.setAmount(book.getAmount() - 1);
            } else {
                player.getInventory().remove(book);
            }
            
            player.sendMessage("§d📚 Книга Мудрости зачаровала " + enchantedItems + " предметов!");
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
            player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation(), 30, 1, 1, 1, 0.3);
        } else {
            player.sendMessage("§c📚 Нет подходящих предметов для зачарования!");
        }
    }
    
    // === СИСТЕМА ПЕРЕЗАРЯДКИ ===
    
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
        if (milliseconds <= 0) return "0с";
        
        long seconds = milliseconds / 1000L;
        long remainingMs = milliseconds % 1000L;
        
        if (seconds > 0) {
            return seconds + "с";
        } else {
            return "0." + (remainingMs / 100) + "с";
        }
    }
    
    // === ПРОВЕРКИ ПРЕДМЕТОВ ===
    
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
    
    private boolean hasCustomKey(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }
    
    private boolean hasPhoenixFeather(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (hasCustomKey(item, PHOENIX_FEATHER_KEY)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean hasPhoenixFeatherInHand(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        
        return hasCustomKey(mainHand, PHOENIX_FEATHER_KEY) || hasCustomKey(offHand, PHOENIX_FEATHER_KEY);
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
               material == Material.ANCIENT_DEBRIS ||
               material.toString().equals("NETHER_QUARTZ_ORE");
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
        try {
            Set<Block> logs = new HashSet<>();
            Set<Block> leaves = new HashSet<>();
            
            findTreeBlocks(startBlock, logs, leaves, 0);
            
            int maxLogs = 50;
            int processed = 0;
            
            for (Block log : logs) {
                if (processed >= maxLogs) break;
                try {
                    log.breakNaturally(tool);
                    processed++;
                } catch (Exception e) {
                    continue;
                }
            }
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        int maxLeaves = 100;
                        int processedLeaves = 0;
                        
                        for (Block leaf : leaves) {
                            if (processedLeaves >= maxLeaves) break;
                            try {
                                if (random.nextDouble() < 0.8) {
                                    leaf.breakNaturally();
                                }
                                processedLeaves++;
                            } catch (Exception e) {
                                continue;
                            }
                        }
                    } catch (Exception e) {
                        getLogger().warning("Ошибка при удалении листьев: " + e.getMessage());
                    }
                }
            }.runTaskLater(this, 20L);
        } catch (Exception e) {
            getLogger().warning("Ошибка при срубке дерева: " + e.getMessage());
        }
    }
    
    private void findTreeBlocks(Block block, Set<Block> logs, Set<Block> leaves, int depth) {
        try {
            if (depth > 20 || logs.size() > 50) return;
            
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 2; y++) {
                    for (int z = -1; z <= 1; z++) {
                        try {
                            Block nearby = block.getRelative(x, y, z);
                            
                            if (isLog(nearby.getType()) && !logs.contains(nearby) && logs.size() < 50) {
                                logs.add(nearby);
                                findTreeBlocks(nearby, logs, leaves, depth + 1);
                            } else if (nearby.getType().toString().contains("LEAVES") && !leaves.contains(nearby) && leaves.size() < 100) {
                                leaves.add(nearby);
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return;
        }
    }
    
    private void spawnGuardians(Location location, ChestTier tier) {
        World world = location.getWorld();
        if (world == null) return;
        
        int guardianCount = tier == ChestTier.LEGENDARY ? 3 : tier == ChestTier.RARE ? 2 : 1;
        
        for (int i = 0; i < guardianCount; i++) {
            double angle = (2 * Math.PI * i) / guardianCount;
            double x = location.getX() + Math.cos(angle) * 3;
            double z = location.getZ() + Math.sin(angle) * 3;
            Location guardianLoc = new Location(world, x, location.getY(), z);
            
            EntityType mobType = tier == ChestTier.LEGENDARY ? EntityType.WITHER_SKELETON :
                                tier == ChestTier.RARE ? EntityType.SKELETON : EntityType.ZOMBIE;
            
            LivingEntity guardian = (LivingEntity) world.spawnEntity(guardianLoc, mobType);
            try {
                guardian.customName(Component.text(tier.displayName + " Guardian"));
            } catch (Exception e) {
                guardian.setCustomName(tier.displayName + " Guardian");
            }
            guardian.setCustomNameVisible(true);
            
            if (guardian instanceof Skeleton) {
                Skeleton skeleton = (Skeleton) guardian;
                skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                skeleton.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
            }
            
            guardian.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0));
        }
    }
    
    private void createSpawnEffects(Location location, ChestTier tier) {
        World world = location.getWorld();
        if (world == null) return;
        
        String chestKey = location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
        
        Sound sound = tier == ChestTier.LEGENDARY ? Sound.ENTITY_DRAGON_FIREBALL_EXPLODE :
                     tier == ChestTier.RARE ? Sound.BLOCK_BEACON_ACTIVATE : Sound.BLOCK_CHEST_OPEN;
        world.playSound(location, sound, 2.0f, 1.0f);
        
        Particle particle = tier == ChestTier.LEGENDARY ? Particle.DRAGON_BREATH :
                           tier == ChestTier.RARE ? Particle.ENCHANT : Particle.HAPPY_VILLAGER;
        
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (openedChests.contains(chestKey)) {
                    cancel();
                    return;
                }
                
                if (ticks >= 6000) {
                    cancel();
                    return;
                }
                
                for (int i = 0; i < 10; i++) {
                    double angle = (ticks + i) * 0.3;
                    double x = location.getX() + Math.cos(angle) * 2;
                    double z = location.getZ() + Math.sin(angle) * 2;
                    double y = location.getY() + 1 + Math.sin(ticks * 0.1) * 0.5;
                    
                    world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0.1);
                }
                
                if (ticks % 600 == 0) {
                    world.playSound(location, sound, 0.5f, 1.0f);
                }
                
                ticks++;
            }
        }.runTaskTimer(this, 0L, 2L);
        
        if (tier == ChestTier.LEGENDARY) {
            world.strikeLightningEffect(location);
        }
    }
    
    private void broadcastChestSpawn(Location location, ChestTier tier) {
        String coords = "X: " + location.getBlockX() + " Y: " + location.getBlockY() + " Z: " + location.getBlockZ();
        
        String message = getConfigMsg("messages.chest-spawned")
            .replace("%tier%", tier.displayName)
            .replace("%x%", String.valueOf(location.getBlockX()))
            .replace("%y%", String.valueOf(location.getBlockY()))
            .replace("%z%", String.valueOf(location.getBlockZ()))
            .replace("%coords%", coords);
        
        broadcastMsg(message);
        
        Component title = Component.text(tier.displayName);
        Component subtitle = Component.text(getConfigMsg("messages.spawn-subtitle")
            .replace("%x%", String.valueOf(location.getBlockX()))
            .replace("%y%", String.valueOf(location.getBlockY()))
            .replace("%z%", String.valueOf(location.getBlockZ())));
        
        Title fullTitle = Title.title(title, subtitle);
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(fullTitle);
            
            Sound playerSound = tier == ChestTier.LEGENDARY ? Sound.UI_TOAST_CHALLENGE_COMPLETE :
                               tier == ChestTier.RARE ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
            player.playSound(player.getLocation(), playerSound, 1.0f, 1.0f);
        }
    }

    private String getConfigMsg(String path) {
        return getConfig().getString(path, "Сообщение не найдено: " + path);
    }

    private void broadcastMsg(String msg) {
        Bukkit.getServer().broadcast(Component.text(msg));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("nextchest")) {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastSpawn = currentTime - lastSpawnTime;
            long remainingTime = Math.max(0L, spawnInterval - timeSinceLastSpawn);
            
            long minutes = remainingTime / 60000L;
            long seconds = (remainingTime % 60000L) / 1000L;
            
            sender.sendMessage(getConfigMsg("messages.next-chest")
                .replace("%minutes%", String.valueOf(minutes))
                .replace("%seconds%", String.valueOf(seconds)));
            return true;
            
        } else if (cmd.getName().equalsIgnoreCase("spawnchestnow")) {
            if (!sender.hasPermission("spawnchest.spawnchestnow")) {
                sender.sendMessage("§cНет прав!");
                return true;
            }
            
            sender.sendMessage("§eПоиск места для сундука...");
            
            final CommandSender finalSender = sender;
            
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    World world = Bukkit.getWorld("world");
                    if (world == null) {
                        finalSender.sendMessage("§cМир не найден!");
                        return;
                    }
                    
                    Location location = findSafeSpawnLocationAsync(world);
                    
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (location != null) {
                            spawnChestAtLocation(location);
                            warned.clear();
                            lastSecondsWarned.clear();
                            finalSender.sendMessage(getConfigMsg("messages.chest-now"));
                        } else {
                            finalSender.sendMessage("§cНе удалось найти подходящее место для сундука!");
                        }
                    });
                } catch (Exception e) {
                    getLogger().severe("Ошибка при поиске места для сундука: " + e.getMessage());
                    finalSender.sendMessage("§cОшибка при создании сундука!");
                }
            });
            
            return true;
            
        } else if (cmd.getName().equalsIgnoreCase("setchesttimer")) {
            if (!sender.hasPermission("spawnchest.setchesttimer")) {
                sender.sendMessage("§cНет прав!");
                return true;
            }
            
            if (args.length == 1) {
                try {
                    int seconds = Integer.parseInt(args[0]);
                    getConfig().set("settings.spawn-interval-seconds", seconds);
                    saveConfig();
                    spawnInterval = seconds * 1000L;
                    sender.sendMessage("§aВремя до следующего сундука изменено на " + seconds + " секунд.");
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cВведите корректное число секунд!");
                }
            } else {
                sender.sendMessage("§cИспользование: /setchesttimer <секунды>");
            }
            return true;
            
        } else if (cmd.getName().equalsIgnoreCase("reloadchestconfig")) {
            if (!sender.hasPermission("spawnchest.reloadchestconfig")) {
                sender.sendMessage("§cНет прав!");
                return true;
            }
            
            reloadConfig();
            spawnInterval = getConfig().getLong("settings.spawn-interval-seconds", 1800L) * 1000L;
            sender.sendMessage("§aКонфиг перезагружен.");
            return true;
            
        } else if (cmd.getName().equalsIgnoreCase("cheststats")) {
            if (sender instanceof Player) {
                Player statsPlayer = (Player) sender;
                statsPlayer.sendMessage("§6=== Статистика сундуков ===");
                statsPlayer.sendMessage("§7Шанс обычного сундука: §f70%");
                statsPlayer.sendMessage("§9Шанс редкого сундука: §f25%");
                statsPlayer.sendMessage("§6Шанс легендарного сундука: §f5%");
                statsPlayer.sendMessage("§7Зона спавна: §f500-2000 блоков от центра");
                statsPlayer.sendMessage("§7Всего легендарных предметов: §f8");
                statsPlayer.sendMessage("§7Сбалансированные эффекты: §aВКЛ");
                statsPlayer.sendMessage("§7PvP защита: §aВКЛ §7(эффекты не работают против игроков)");
                statsPlayer.sendMessage("§7Краш-защита: §aВКЛ");
                statsPlayer.sendMessage("§7Эффекты до открытия: §aВКЛ");
                statsPlayer.sendMessage("§7Система перезарядки: §aВКЛ");
                statsPlayer.sendMessage("§7Команды: §e/getlegendaryitems, /cooldowns");
            }
            return true;
            
        } else if (cmd.getName().equalsIgnoreCase("cooldowns")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cЭта команда только для игроков!");
                return true;
            }
            
            Player statsPlayer = (Player) sender;
            UUID playerId = statsPlayer.getUniqueId();
            
            statsPlayer.sendMessage("§6=== Перезарядка способностей ===");
            
            long swordRemaining = getRemainingCooldown(swordCooldown, playerId, SWORD_COOLDOWN);
            long axeRemaining = getRemainingCooldown(axeCooldown, playerId, AXE_COOLDOWN);
            long hammerRemaining = getRemainingCooldown(hammerCooldown, playerId, HAMMER_COOLDOWN);
            long bowRemaining = getRemainingCooldown(bowCooldown, playerId, BOW_COOLDOWN);
            long shovelRemaining = getRemainingCooldown(shovelCooldown, playerId, SHOVEL_COOLDOWN);
            long pickaxeRemaining = getRemainingCooldown(pickaxeCooldown, playerId, PICKAXE_COOLDOWN);
            long treeCutRemaining = getRemainingCooldown(treeCutCooldown, playerId, TREE_CUT_COOLDOWN);
            
            statsPlayer.sendMessage("§6⚔ Клинок Драконобойца: " + 
                (swordRemaining > 0 ? "§c" + formatCooldown(swordRemaining) : "§aГотов"));
            statsPlayer.sendMessage("§4🪓 Топор Титана (бой): " + 
                (axeRemaining > 0 ? "§c" + formatCooldown(axeRemaining) : "§aГотов"));
            statsPlayer.sendMessage("§9⚡ Молот Бури: " + 
                (hammerRemaining > 0 ? "§c" + formatCooldown(hammerRemaining) : "§aГотов"));
            statsPlayer.sendMessage("§a🏹 Лук Стража: " + 
                (bowRemaining > 0 ? "§c" + formatCooldown(bowRemaining) : "§aГотов"));
            statsPlayer.sendMessage("§5🕳 Лопата Пустоты: " + 
                (shovelRemaining > 0 ? "§c" + formatCooldown(shovelRemaining) : "§aГотов"));
            statsPlayer.sendMessage("§b⛏ Кирка Мастера: " + 
                (pickaxeRemaining > 0 ? "§c" + formatCooldown(pickaxeRemaining) : "§aГотов"));
            statsPlayer.sendMessage("§4🌳 Срубка дерева: " + 
                (treeCutRemaining > 0 ? "§c" + formatCooldown(treeCutRemaining) : "§aГотов"));
            
            return true;
            
        } else if (cmd.getName().equalsIgnoreCase("testchestzone")) {
            if (!sender.hasPermission("spawnchest.testchestzone")) {
                sender.sendMessage("§cНет прав!");
                return true;
            }
            
            sender.sendMessage("§eTестирование зоны спавна сундуков...");
            
            for (int i = 0; i < 5; i++) {
                double x = (random.nextDouble() * 1500.0 + 500.0) * (random.nextBoolean() ? 1 : -1);
                double z = (random.nextDouble() * 1500.0 + 500.0) * (random.nextBoolean() ? 1 : -1);
                sender.sendMessage("§7Пример " + (i+1) + ": §fX=" + (int)x + " Z=" + (int)z + " §7(расстояние от центра: " + (int)Math.sqrt(x*x + z*z) + ")");
            }
            
            return true;
            
        } else if (cmd.getName().equalsIgnoreCase("getlegendaryitems")) {
            if (!sender.hasPermission("spawnchest.getlegendaryitems")) {
                sender.sendMessage("§cНет прав!");
                return true;
            }
            
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cЭта команда только для игроков!");
                return true;
            }
            
            Player commandPlayer = (Player) sender;
            
            try {
                commandPlayer.getInventory().addItem(createLegendarySword());
                commandPlayer.getInventory().addItem(createMasterPickaxe());
                commandPlayer.getInventory().addItem(createTitanAxe());
                commandPlayer.getInventory().addItem(createVoidShovel());
                commandPlayer.getInventory().addItem(createStormHammer());
                commandPlayer.getInventory().addItem(createGuardianBow());
                commandPlayer.getInventory().addItem(createWisdomBook());
                commandPlayer.getInventory().addItem(createPhoenixFeather());
                
                commandPlayer.getInventory().addItem(new ItemStack(Material.ARROW, 64));
                
                commandPlayer.sendMessage("§6✨ Вы получили все легендарные предметы для тестирования!");
                commandPlayer.sendMessage("§7Включены: меч, кирка, топор, лопата, молот, лук, книга, перо");
                commandPlayer.sendMessage("§7+ 64 стрелы для лука");
                commandPlayer.sendMessage("§eИспользуйте §f/cooldowns §eдля проверки перезарядки способностей!");
                
                getLogger().info("Игрок " + commandPlayer.getName() + " получил все легендарные предметы");
                
            } catch (Exception e) {
                commandPlayer.sendMessage("§cОшибка при выдаче предметов!");
                getLogger().warning("Ошибка при выдаче предметов игроку " + commandPlayer.getName() + ": " + e.getMessage());
            }
            
            return true;
        }
        
        return false;
    }

    // === ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ ===
    
    private class ChestTimer extends BukkitRunnable {
        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastSpawn = currentTime - lastSpawnTime;
            long remainingTime = spawnInterval - timeSinceLastSpawn;
            
            for (long warnTick : warningTimes) {
                long warnTime = warnTick * 50L;
                if (remainingTime <= warnTime && remainingTime > warnTime - 1000L && !warned.contains(warnTick)) {
                    int minutes = (int) (warnTick / 1200L);
                    broadcastMsg(getConfigMsg("messages.minutes-left").replace("%minutes%", String.valueOf(minutes)));
                    warned.add(warnTick);
                }
            }
            
            if (remainingTime <= 60000L && remainingTime > 0L) {
                int seconds = (int) (remainingTime / 1000L);
                if (remainingTime % 1000L < 50L && !lastSecondsWarned.contains(seconds)) {
                    if (seconds <= 10 || seconds == 30 || seconds == 60) {
                        broadcastMsg(getConfigMsg("messages.countdown").replace("%seconds%", String.valueOf(seconds)));
                        
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.5f, 1.0f);
                        }
                    }
                    lastSecondsWarned.add(seconds);
                }
            }
            
            if (remainingTime <= 0L) {
                spawnChest();
                warned.clear();
                lastSecondsWarned.clear();
            }
        }
    }
    
    private class PhoenixFeatherPassiveTask extends BukkitRunnable {
        @Override
        public void run() {
            try {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (hasPhoenixFeatherInHand(player)) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 100, 0, true, false));
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Ошибка в PhoenixFeatherPassiveTask: " + e.getMessage());
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
            try {
                if (arrow == null || arrow.isDead() || arrow.isOnGround() || ticks++ > 60) {
                    cancel();
                    return;
                }
                
                LivingEntity target = null;
                double minDistance = maxDistance;
                
                try {
                    for (LivingEntity entity : arrow.getLocation().getNearbyLivingEntities(maxDistance)) {
                        if (entity == null || entity == shooter || entity instanceof Player) continue;
                        
                        double distance = entity.getLocation().distance(arrow.getLocation());
                        if (distance < minDistance) {
                            minDistance = distance;
                            target = entity;
                        }
                    }
                } catch (Exception e) {
                    cancel();
                    return;
                }
                
                if (target != null) {
                    try {
                        Vector direction = target.getEyeLocation().subtract(arrow.getLocation()).toVector().normalize();
                        arrow.setVelocity(direction.multiply(1.5));
                        arrow.getWorld().spawnParticle(Particle.ENCHANT, arrow.getLocation(), 1);
                    } catch (Exception e) {
                        cancel();
                    }
                }
                
            } catch (Exception e) {
                getLogger().warning("Ошибка в ArrowHomingTask: " + e.getMessage());
                cancel();
            }
        }
    }
}