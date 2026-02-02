package com.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version adapter for multi-version and multi-platform support.
 * Supports: Spigot, Paper, Purpur, Pufferfish, Folia
 * Versions: 1.18.x - 1.21.x
 * Java: 17 and 21
 */
public class VersionAdapter {
    
    private final JavaPlugin plugin;
    private final LanguageManager lang;
    private final int majorVersion;
    private final int minorVersion;
    private final ServerType serverType;
    
    // Cached reflection methods
    private Method getChunkAtAsyncMethod;
    private Method getNearbyLivingEntitiesMethod;
    private Method getGlobalRegionSchedulerMethod;
    private Method getRegionSchedulerMethod;
    
    public enum ServerType {
        SPIGOT,
        PAPER,
        PURPUR,
        PUFFERFISH,
        FOLIA,
        UNKNOWN
    }
    
    public VersionAdapter(JavaPlugin plugin, LanguageManager lang) {
        this.plugin = plugin;
        this.lang = lang;
        
        // Parse version from Bukkit
        String version = Bukkit.getBukkitVersion();
        Pattern pattern = Pattern.compile("(\\d+)\\.(\\d+)");
        Matcher matcher = pattern.matcher(version);
        
        if (matcher.find()) {
            this.majorVersion = Integer.parseInt(matcher.group(1));
            this.minorVersion = Integer.parseInt(matcher.group(2));
        } else {
            this.majorVersion = 1;
            this.minorVersion = 20;
        }
        
        // Detect server type
        this.serverType = detectServerType();
        
        // Initialize reflection methods
        initReflection();
        
        // Log initialization
        logInitialization();
    }
    
    private void logInitialization() {
        plugin.getLogger().info(lang.getMessage("system.version-adapter-init"));
        plugin.getLogger().info(lang.getMessage("system.version-minecraft",
            "%version%", majorVersion + "." + minorVersion));
        plugin.getLogger().info(lang.getMessage("system.version-server",
            "%type%", serverType.name()));
        plugin.getLogger().info(lang.getMessage("system.version-full",
            "%version%", Bukkit.getVersion()));
        plugin.getLogger().info(lang.getMessage("system.version-java",
            "%version%", System.getProperty("java.version")));
    }
    
    private ServerType detectServerType() {
        String serverVersion = Bukkit.getVersion().toLowerCase();
        String serverName = Bukkit.getName().toLowerCase();
        
        // Check for Folia first (most specific)
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return ServerType.FOLIA;
        } catch (ClassNotFoundException ignored) {}
        
        // Check for Pufferfish
        if (serverVersion.contains("pufferfish") || serverName.contains("pufferfish")) {
            return ServerType.PUFFERFISH;
        }
        
        // Check for Purpur
        if (serverVersion.contains("purpur") || serverName.contains("purpur")) {
            return ServerType.PURPUR;
        }
        
        // Check for Paper
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return ServerType.PAPER;
        } catch (ClassNotFoundException ignored) {}
        
        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            return ServerType.PAPER;
        } catch (ClassNotFoundException ignored) {}
        
        // Default to Spigot
        return ServerType.SPIGOT;
    }
    
    private void initReflection() {
        // Try to get World.getChunkAtAsync(int, int) for Paper
        try {
            getChunkAtAsyncMethod = World.class.getMethod("getChunkAtAsync", int.class, int.class);
        } catch (NoSuchMethodException e) {
            // Not available, will use sync
        }
        
        // Try to get Location.getNearbyLivingEntities(double) for newer versions
        try {
            getNearbyLivingEntitiesMethod = Location.class.getMethod("getNearbyLivingEntities", double.class);
        } catch (NoSuchMethodException e) {
            // Not available, will use World.getNearbyEntities
        }
        
        // Try to get Folia's GlobalRegionScheduler
        try {
            Class<?> bukkitClass = Bukkit.class;
            getGlobalRegionSchedulerMethod = bukkitClass.getMethod("getGlobalRegionScheduler");
        } catch (NoSuchMethodException e) {
            // Not Folia
        }
        
        // Try to get Folia's RegionScheduler
        try {
            Class<?> bukkitClass = Bukkit.class;
            getRegionSchedulerMethod = bukkitClass.getMethod("getRegionScheduler");
        } catch (NoSuchMethodException e) {
            // Not Folia
        }
    }
    
    // ==================== VERSION CHECKS ====================
    
    public int getMajorVersion() {
        return majorVersion;
    }
    
    public int getMinorVersion() {
        return minorVersion;
    }
    
    public ServerType getServerType() {
        return serverType;
    }
    
    public String getVersionDisplay() {
        return majorVersion + "." + minorVersion + " (" + serverType.name() + ")";
    }
    
    public boolean isBelow(int major, int minor) {
        if (majorVersion < major) return true;
        if (majorVersion > major) return false;
        return minorVersion < minor;
    }
    
    public boolean isAtLeast(int major, int minor) {
        return !isBelow(major, minor);
    }
    
    public boolean isPaper() {
        return serverType == ServerType.PAPER || 
               serverType == ServerType.PURPUR || 
               serverType == ServerType.PUFFERFISH ||
               serverType == ServerType.FOLIA;
    }
    
    public boolean isFolia() {
        return serverType == ServerType.FOLIA;
    }
    
    // ==================== CHUNK LOADING ====================
    
    /**
     * Load chunk asynchronously if supported (Paper), otherwise synchronously (Spigot).
     */
    @SuppressWarnings("unchecked")
    public void getChunkAtAsync(World world, int chunkX, int chunkZ, Consumer<Chunk> callback) {
        // Try Paper async chunk loading
        if (getChunkAtAsyncMethod != null && isPaper() && !isFolia()) {
            try {
                Object result = getChunkAtAsyncMethod.invoke(world, chunkX, chunkZ);
                if (result instanceof CompletableFuture) {
                    ((CompletableFuture<Chunk>) result).thenAccept(callback);
                    return;
                }
            } catch (Exception e) {
                String message = lang.getMessage("system.version-chunk-error",
                    "%error%", e.getMessage());
                plugin.getLogger().warning(message);
            }
        }
        
        // Spigot fallback - synchronous chunk loading
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        callback.accept(chunk);
    }
    
    // ==================== ENTITY SEARCH ====================
    
    /**
     * Get nearby living entities (version-safe).
     */
    @SuppressWarnings("unchecked")
    public Collection<LivingEntity> getNearbyLivingEntities(Location location, double radius) {
        // Try newer API (1.18+)
        if (getNearbyLivingEntitiesMethod != null) {
            try {
                Object result = getNearbyLivingEntitiesMethod.invoke(location, radius);
                if (result instanceof Collection) {
                    return (Collection<LivingEntity>) result;
                }
            } catch (Exception e) {
                // Fallback
            }
        }
        
        // Fallback: use World.getNearbyEntities and filter
        List<LivingEntity> entities = new ArrayList<>();
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (entity instanceof LivingEntity) {
                entities.add((LivingEntity) entity);
            }
        }
        return entities;
    }
    
    // ==================== SCHEDULER ====================
    
    /**
     * Run a task on the main thread (or appropriate thread for Folia).
     */
    public void runTask(Runnable task) {
        if (isFolia() && getGlobalRegionSchedulerMethod != null) {
            try {
                Object scheduler = getGlobalRegionSchedulerMethod.invoke(null);
                Method executeMethod = scheduler.getClass().getMethod("execute", JavaPlugin.class, Runnable.class);
                executeMethod.invoke(scheduler, plugin, task);
                return;
            } catch (Exception e) {
                // Fallback
            }
        }
        
        // Standard scheduler
        Bukkit.getScheduler().runTask(plugin, task);
    }
    
    /**
     * Run a task later on the main thread.
     */
    public void runTaskLater(Runnable task, long delayTicks) {
        if (isFolia() && getGlobalRegionSchedulerMethod != null) {
            try {
                Object scheduler = getGlobalRegionSchedulerMethod.invoke(null);
                Method runDelayedMethod = scheduler.getClass().getMethod("runDelayed", 
                    JavaPlugin.class, Consumer.class, long.class);
                runDelayedMethod.invoke(scheduler, plugin, (Consumer<Object>) t -> task.run(), delayTicks);
                return;
            } catch (Exception e) {
                // Fallback
            }
        }
        
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }
    
    // ==================== MATERIALS ====================
    
    public Material getBestSwordMaterial() {
        return Material.NETHERITE_SWORD;
    }
    
    public Material getBestPickaxeMaterial() {
        return Material.NETHERITE_PICKAXE;
    }
    
    public Material getBestAxeMaterial() {
        return Material.NETHERITE_AXE;
    }
    
    public Material getBestShovelMaterial() {
        return Material.NETHERITE_SHOVEL;
    }
    
    // ==================== WORLD HEIGHT ====================
    
    public int getMinWorldHeight(World world) {
        try {
            return world.getMinHeight();
        } catch (NoSuchMethodError e) {
            return 0;
        }
    }
    
    public int getMaxWorldHeight(World world) {
        return world.getMaxHeight();
    }
}