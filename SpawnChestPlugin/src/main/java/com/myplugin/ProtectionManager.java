package com.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Reflection-based integration with land/claim protection plugins.
 * No compile-time dependency on any of them — add them to softdepend only.
 *
 * Supported: WorldGuard 7, GriefPrevention, GriefDefender 2, Lands 5+
 */
public class ProtectionManager {

    private final SpawnChestPlugin plugin;

    private boolean wgEnabled;
    private boolean gpEnabled;
    private boolean gdEnabled;
    private boolean landsEnabled;

    // Cached Lands API object (avoids re-initialising on every check)
    private Object  landsApi          = null;
    private Method  landsGetAreaMethod = null;

    public ProtectionManager(SpawnChestPlugin plugin) {
        this.plugin = plugin;
        init();
    }

    // ── initialisation ─────────────────────────────────────────────────────────

    private void init() {
        wgEnabled    = present("WorldGuard")     && cfg("check-worldguard");
        gpEnabled    = present("GriefPrevention") && cfg("check-grief-prevention");
        gdEnabled    = present("GriefDefender")  && cfg("check-grief-defender");
        landsEnabled = present("Lands")          && cfg("check-lands");

        if (landsEnabled) initLandsApi();

        plugin.getLogger().info("[Protection] WorldGuard:" + sym(wgEnabled)
            + " GriefPrevention:" + sym(gpEnabled)
            + " GriefDefender:"   + sym(gdEnabled)
            + " Lands:"           + sym(landsEnabled));
    }

    private void initLandsApi() {
        try {
            Class<?> cls = Class.forName("me.angeschossen.lands.api.LandsIntegration");
            Method   of  = cls.getMethod("of", Plugin.class);
            landsApi           = of.invoke(null, plugin);
            landsGetAreaMethod = cls.getMethod("getArea", Location.class);
        } catch (Exception e) {
            // Lands API not in classpath or version mismatch — per-call reflection fallback
        }
    }

    // ── public API ─────────────────────────────────────────────────────────────

    /** Returns {@code true} if the location is inside a protected claim/region. */
    public boolean isProtected(Location loc) {
        if (!plugin.getConfig().getBoolean("protection.enabled", true)) return false;
        return (wgEnabled    && checkWorldGuard(loc))
            || (gpEnabled    && checkGriefPrevention(loc))
            || (gdEnabled    && checkGriefDefender(loc))
            || (landsEnabled && checkLands(loc));
    }

    // ── WorldGuard 7 ───────────────────────────────────────────────────────────

    private boolean checkWorldGuard(Location loc) {
        try {
            // WorldGuard.getInstance()
            Class<?> wgCls   = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object   wg      = wgCls.getMethod("getInstance").invoke(null);
            Object   platform = invoke(wg, "getPlatform");
            Object   container = invoke(platform, "getRegionContainer");

            // Convert Bukkit World → WorldEdit World
            Class<?> adapterCls = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter");
            Class<?> weWorldCls = Class.forName("com.sk89q.worldedit.world.World");
            Object   weWorld    = adapterCls.getMethod("adapt", org.bukkit.World.class)
                                      .invoke(null, loc.getWorld());

            // RegionContainer.get(world) → RegionManager
            Object rm = container.getClass()
                            .getMethod("get", weWorldCls)
                            .invoke(container, weWorld);
            if (rm == null) return false;

            // BlockVector3.at(x, y, z)
            Class<?> bv3Cls = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Object   bv3    = bv3Cls.getMethod("at", double.class, double.class, double.class)
                                 .invoke(null,
                                     (double) loc.getBlockX(),
                                     (double) loc.getBlockY(),
                                     (double) loc.getBlockZ());

            // rm.getApplicableRegions(bv3) → ApplicableRegionSet (Iterable)
            Object regions = rm.getClass()
                               .getMethod("getApplicableRegions", bv3Cls)
                               .invoke(rm, bv3);

            for (Object region : (Iterable<?>) regions) {
                String id = (String) region.getClass().getMethod("getId").invoke(region);
                if (!"__global__".equals(id)) return true; // found a real region
            }
            return false;
        } catch (Exception e) {
            return false; // WG not available or version mismatch
        }
    }

    // ── GriefPrevention ────────────────────────────────────────────────────────

    private boolean checkGriefPrevention(Location loc) {
        try {
            Class<?> cls      = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object   instance = cls.getField("instance").get(null);
            Object   ds       = instance.getClass().getField("dataStore").get(instance);

            // dataStore.getClaimAt(location, ignoreHeight, cachedClaim)
            Object claim = ds.getClass()
                .getMethod("getClaimAt", Location.class, boolean.class,
                    Class.forName("me.ryanhamshire.GriefPrevention.Claim"))
                .invoke(ds, loc, false, null);

            return claim != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ── GriefDefender 2 ────────────────────────────────────────────────────────

    private boolean checkGriefDefender(Location loc) {
        try {
            Class<?> gdCls = Class.forName("com.griefdefender.api.GriefDefender");
            Object   core  = gdCls.getMethod("getCore").invoke(null);

            // getClaimManager(worldUUID)
            Object cm = core.getClass()
                .getMethod("getClaimManager", java.util.UUID.class)
                .invoke(core, loc.getWorld().getUID());
            if (cm == null) return false;

            // Vector3i is from flowpowered-math (bundled with GD)
            Class<?> vecCls = Class.forName("com.flowpowered.math.vector.Vector3i");
            Object   vec    = vecCls.getConstructor(int.class, int.class, int.class)
                                 .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

            Object claim = cm.getClass()
                .getMethod("getClaimAt", vecCls)
                .invoke(cm, vec);

            return claim != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Lands 5+ ──────────────────────────────────────────────────────────────

    private boolean checkLands(Location loc) {
        try {
            if (landsApi != null && landsGetAreaMethod != null) {
                // Fast path — cached API object
                return landsGetAreaMethod.invoke(landsApi, loc) != null;
            }
            // Slow path fallback
            Class<?> cls  = Class.forName("me.angeschossen.lands.api.LandsIntegration");
            Object   api  = cls.getMethod("of", Plugin.class).invoke(null, plugin);
            Object   area = cls.getMethod("getArea", Location.class).invoke(api, loc);
            return area != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private boolean cfg(String key) {
        return plugin.getConfig().getBoolean("protection." + key, true);
    }

    private static boolean present(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }

    private static Object invoke(Object obj, String method) throws Exception {
        return obj.getClass().getMethod(method).invoke(obj);
    }

    private static String sym(boolean b) { return b ? "✓" : "✗"; }
}