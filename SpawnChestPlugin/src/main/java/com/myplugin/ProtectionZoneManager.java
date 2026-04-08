package com.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages temporary protection zones around active chests.
 *
 * Two modes depending on what is installed:
 *
 *   WITH WorldGuard  — creates a real WG region on chest spawn,
 *                      removes it when the chest disappears or is opened.
 *
 *   WITHOUT WorldGuard — stores zone boundaries in memory and blocks
 *                        BlockBreakEvent / BlockPlaceEvent directly.
 *                        Zones are cleared on server restart automatically
 *                        because ActiveChests are not persisted across restarts.
 *
 * All WorldGuard calls are done via reflection so the plugin compiles and
 * runs even when WorldGuard is not installed.
 */
public class ProtectionZoneManager {

    private final SpawnChestPlugin plugin;

    /**
     * Fallback zones when WorldGuard is not available.
     * Key   = chestKey ("x,y,z")
     * Value = zone boundary data
     */
    private final Map<String, FallbackZone> fallbackZones = new HashMap<>();

    /** Cached WG API objects — null if WorldGuard is not installed. */
    private Object wgPlatform      = null;
    private Object regionContainer = null;
    private boolean wgAvailable    = false;

    public ProtectionZoneManager(SpawnChestPlugin plugin) {
        this.plugin = plugin;
        initWorldGuard();
    }

    // ── Initialisation ─────────────────────────────────────────────────────────

    private void initWorldGuard() {
        if (!plugin.getConfig().getBoolean("chest-protection-zone.use-worldguard", true)) {
            return;
        }

        Plugin wgPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wgPlugin == null || !wgPlugin.isEnabled()) return;

        try {
            // WorldGuard.getInstance().getPlatform().getRegionContainer()
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg        = wgClass.getMethod("getInstance").invoke(null);
            wgPlatform       = wg.getClass().getMethod("getPlatform").invoke(wg);
            regionContainer  = wgPlatform.getClass()
                .getMethod("getRegionContainer").invoke(wgPlatform);
            wgAvailable      = true;

            plugin.getLogger().info("[ProtectionZone] WorldGuard detected — using WG regions.");
        } catch (Exception e) {
            plugin.getLogger().warning(
                "[ProtectionZone] WorldGuard found but API init failed: " + e.getMessage()
                + " — falling back to built-in protection.");
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Create a protection zone centred on the given chest location.
     * Called from SpawnChestPlugin right after a chest is placed.
     */
    public void createZone(Location chestLoc, String chestKey) {
        if (!plugin.getConfig().getBoolean("chest-protection-zone.enabled", true)) return;

        int radius = plugin.getConfig().getInt("chest-protection-zone.radius", 5);

        if (wgAvailable) {
            createWGRegion(chestLoc, chestKey, radius);
        } else {
            createFallbackZone(chestLoc, chestKey, radius);
        }

        if (plugin.getConfig().getBoolean("chest-protection-zone.notify-players", true)) {
            plugin.getLogger().info(plugin.getLanguageManager().getMessage(
                "broadcasts.zone-created",
                "%x%", String.valueOf(chestLoc.getBlockX()),
                "%y%", String.valueOf(chestLoc.getBlockY()),
                "%z%", String.valueOf(chestLoc.getBlockZ())));
        }
    }

    /**
     * Remove the protection zone for the given chest.
     * Called when the chest disappears (timer) or is opened.
     */
    public void removeZone(String chestKey, Location chestLoc) {
        if (!plugin.getConfig().getBoolean("chest-protection-zone.enabled", true)) return;

        if (wgAvailable) {
            removeWGRegion(chestKey, chestLoc);
        } else {
            fallbackZones.remove(chestKey);
        }

        if (plugin.getConfig().getBoolean("chest-protection-zone.notify-players", true)) {
            if (chestLoc != null) {
                plugin.getLogger().info(plugin.getLanguageManager().getMessage(
                    "broadcasts.zone-removed",
                    "%x%", String.valueOf(chestLoc.getBlockX()),
                    "%y%", String.valueOf(chestLoc.getBlockY()),
                    "%z%", String.valueOf(chestLoc.getBlockZ())));
            }
        }
    }

    /**
     * Check whether the given location is inside any active protection zone.
     * Used in BlockBreakEvent / BlockPlaceEvent handlers.
     */
    public boolean isInsideZone(Location loc) {
        if (!plugin.getConfig().getBoolean("chest-protection-zone.enabled", true)) return false;

        // Fallback zones — always checked (even with WG, as a safety net)
        for (FallbackZone zone : fallbackZones.values()) {
            if (zone.contains(loc)) return true;
        }

        return false; // WG handles its own blocking via flags
    }

    /**
     * Remove ALL zones — called on plugin disable to clean up WG regions
     * left over from a previous session (best-effort).
     */
    public void removeAllZones() {
        for (Map.Entry<String, FallbackZone> entry : fallbackZones.entrySet()) {
            FallbackZone zone = entry.getValue();
            removeWGRegion(entry.getKey(),
                new Location(Bukkit.getWorld(zone.worldName),
                    zone.centerX, zone.centerY, zone.centerZ));
        }
        fallbackZones.clear();
    }

    // ── WorldGuard region ──────────────────────────────────────────────────────

    private void createWGRegion(Location loc, String chestKey, int radius) {
        try {
            World world = loc.getWorld();
            if (world == null) return;

            String regionName = buildRegionName(chestKey);

            // Convert Bukkit world to WorldEdit world
            Class<?> adapterClass = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter");
            Object weWorld = adapterClass.getMethod("adapt", World.class)
                .invoke(null, world);

            // Get RegionManager for this world
            Class<?> weWorldClass = Class.forName("com.sk89q.worldedit.world.World");
            Object rm = regionContainer.getClass()
                .getMethod("get", weWorldClass)
                .invoke(regionContainer, weWorld);
            if (rm == null) {
                // WorldGuard not managing this world — use fallback
                createFallbackZone(loc, chestKey, radius);
                return;
            }

            // BlockVector3 min/max corners
            Class<?> bv3Class = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Object min = bv3Class.getMethod("at", double.class, double.class, double.class)
                .invoke(null,
                    (double)(loc.getBlockX() - radius),
                    (double) 0,
                    (double)(loc.getBlockZ() - radius));
            Object max = bv3Class.getMethod("at", double.class, double.class, double.class)
                .invoke(null,
                    (double)(loc.getBlockX() + radius),
                    (double)(world.getMaxHeight() - 1),
                    (double)(loc.getBlockZ() + radius));

            // Create ProtectedCuboidRegion
            Class<?> regionClass = Class.forName(
                "com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion");
            Object region = regionClass
                .getConstructor(String.class, boolean.class, bv3Class, bv3Class)
                .newInstance(regionName, false, min, max);

            // Set flags: deny build, deny block-break, deny block-place
            Class<?> flagsClass  = Class.forName(
                "com.sk89q.worldguard.protection.flags.Flags");
            Class<?> stateFlag   = Class.forName(
                "com.sk89q.worldguard.protection.flags.StateFlag");
            Class<?> stateEnum   = Class.forName(
                "com.sk89q.worldguard.protection.flags.StateFlag$State");

            Object denyState  = stateEnum.getField("DENY").get(null);
            Object buildFlag  = flagsClass.getField("BUILD").get(null);
            Object breakFlag  = flagsClass.getField("BLOCK_BREAK").get(null);
            Object placeFlag  = flagsClass.getField("BLOCK_PLACE").get(null);

            Method setFlag = region.getClass().getMethod("setFlag",
                Class.forName("com.sk89q.worldguard.protection.flags.Flag"), Object.class);
            setFlag.invoke(region, buildFlag,  denyState);
            setFlag.invoke(region, breakFlag,  denyState);
            setFlag.invoke(region, placeFlag,  denyState);

            // Add region to manager
            rm.getClass().getMethod("addRegion",
                Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion"))
                .invoke(rm, region);

            // Save
            rm.getClass().getMethod("save").invoke(rm);

            // Also store as fallback zone for our own event checks
            createFallbackZone(loc, chestKey, radius);

        } catch (Exception e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage(
                "system.zone-wg-create-error",
                "%x%", String.valueOf(loc.getBlockX()),
                "%y%", String.valueOf(loc.getBlockY()),
                "%z%", String.valueOf(loc.getBlockZ()),
                "%error%", e.getMessage()));

            // Fallback if WG region creation failed
            createFallbackZone(loc, chestKey, radius);
        }
    }

    private void removeWGRegion(String chestKey, Location loc) {
        if (!wgAvailable) return;

        try {
            if (loc == null || loc.getWorld() == null) return;

            String regionName = buildRegionName(chestKey);

            Class<?> adapterClass = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter");
            Object weWorld = adapterClass.getMethod("adapt", World.class)
                .invoke(null, loc.getWorld());

            Class<?> weWorldClass = Class.forName("com.sk89q.worldedit.world.World");
            Object rm = regionContainer.getClass()
                .getMethod("get", weWorldClass)
                .invoke(regionContainer, weWorld);

            if (rm == null) return;

            rm.getClass().getMethod("removeRegion", String.class)
                .invoke(rm, regionName);
            rm.getClass().getMethod("save").invoke(rm);

        } catch (Exception e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage(
                "system.zone-wg-remove-error",
                "%name%", buildRegionName(chestKey),
                "%error%", e.getMessage()));
        }
    }

    // ── Fallback zone ──────────────────────────────────────────────────────────

    private void createFallbackZone(Location loc, String chestKey, int radius) {
        if (loc.getWorld() == null) return;
        fallbackZones.put(chestKey, new FallbackZone(
            loc.getWorld().getName(),
            loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
            radius));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String buildRegionName(String chestKey) {
        String prefix = plugin.getConfig().getString(
            "chest-protection-zone.worldguard-region-prefix", "spawnchest");
        // chestKey is "x,y,z" — replace commas with underscores
        return prefix + "_" + chestKey.replace(",", "_");
    }

    // ── Fallback zone data class ───────────────────────────────────────────────

    public static class FallbackZone {
        public final String worldName;
        public final int centerX, centerY, centerZ;
        public final int radius;

        public FallbackZone(String worldName,
                            int centerX, int centerY, int centerZ,
                            int radius) {
            this.worldName = worldName;
            this.centerX   = centerX;
            this.centerY   = centerY;
            this.centerZ   = centerZ;
            this.radius    = radius;
        }

        /**
         * Returns true if the location is inside this zone.
         * Uses XZ-only check (full world height) — matches WG region behaviour.
         */
        public boolean contains(Location loc) {
            if (loc.getWorld() == null) return false;
            if (!loc.getWorld().getName().equals(worldName)) return false;

            int dx = Math.abs(loc.getBlockX() - centerX);
            int dz = Math.abs(loc.getBlockZ() - centerZ);
            return dx <= radius && dz <= radius;
        }
    }
}