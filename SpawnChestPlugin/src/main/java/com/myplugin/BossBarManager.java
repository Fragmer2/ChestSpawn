package com.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Manages the optional BossBar HUD for SpawnChestPlugin.
 *
 * Two display modes:
 *  - Countdown : shown while no chest is active, counting down to next spawn.
 *                Can be limited to only appear N minutes before spawn.
 *  - Active    : shown after a chest spawns, showing tier, coordinates,
 *                and (optionally) time until the chest disappears.
 *                Can be limited to only appear for N minutes after spawn.
 *
 * The bar is automatically hidden when there is nothing to display.
 * All settings are read from config.yml on every tick so /reloadchestconfig
 * takes effect immediately without restarting the task.
 */
public class BossBarManager {

    private final SpawnChestPlugin plugin;

    /** The single BossBar instance. Null when disabled or not yet initialised. */
    private BossBar bossBar;

    /** Repeating 1-second task that updates the bar content. */
    private BukkitRunnable updateTask;

    public BossBarManager(SpawnChestPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Create the BossBar and start the update task.
     * Safe to call multiple times — stops any existing task first.
     */
    public void start() {
        // Always stop cleanly before (re)starting
        stop();

        if (!isEnabled()) return;

        bossBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
        bossBar.setVisible(false); // hidden until tick() decides what to show

        // Add all currently online players that have the permission
        for (Player p : Bukkit.getOnlinePlayers()) {
            addPlayer(p);
        }

        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        updateTask.runTaskTimer(plugin, 0L, 20L); // fires every second
    }

    /**
     * Hide and destroy the BossBar, cancel the update task.
     * Safe to call even if start() was never called.
     */
    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (bossBar != null) {
            bossBar.setVisible(false);
            bossBar.removeAll();
            bossBar = null;
        }
    }

    // ── Player management ──────────────────────────────────────────────────────

    /**
     * Add a player to the BossBar.
     * Call this from onPlayerJoin.
     */
    public void addPlayer(Player player) {
        if (!isEnabled() || bossBar == null) return;
        if (player.hasPermission("spawnchest.notify.bossbar")) {
            bossBar.addPlayer(player);
        }
    }

    /**
     * Remove a player from the BossBar.
     * Call this from onPlayerQuit.
     */
    public void removePlayer(Player player) {
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    // ── Main tick (runs every second) ─────────────────────────────────────────

    private void tick() {
        // If the feature was disabled via /reloadchestconfig while the task
        // was already running, hide the bar and do nothing.
        if (!isEnabled()) {
            if (bossBar != null) {
                bossBar.setVisible(false);
            }
            return;
        }

        // Lazy-init: handles the case where enabled was changed to true after
        // startup via /reloadchestconfig without a full restart.
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID);
            bossBar.setVisible(false);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("spawnchest.notify.bossbar")) {
                    bossBar.addPlayer(p);
                }
            }
        }

        boolean showActiveChest = plugin.getConfig().getBoolean("features.bossbar.show-active-chest", true);
        boolean showCountdown   = plugin.getConfig().getBoolean("features.bossbar.show-countdown",    true);

        SpawnChestPlugin.ActiveChest active = firstUnopenedChest();

        // ── Priority 0: pre-open countdown (highest priority) ─────────────────
        if (active != null
                && active.activating
                && active.locked
                && plugin.getConfig().getBoolean("pre-open-timer.show-in-bossbar", true)) {

            int durationSeconds = plugin.getConfig().getInt(
                "pre-open-timer.duration-seconds", 30);
            long remaining = active.getRemainingSeconds(durationSeconds);
            double progress = durationSeconds > 0
                ? (double) remaining / durationSeconds : 0.0;

            BarColor color = parseColor(
                "pre-open-timer.bossbar-color", BarColor.PINK);

            bossBar.setColor(color);
            bossBar.setProgress(clamp(progress));
            bossBar.setTitle(buildPreOpenTitle(active, remaining));
            bossBar.setVisible(true);
            return;
        }

        // ── Priority 1: active chest bar ──────────────────────────────────────
        if (active != null && showActiveChest) {
            if (tickActiveChest(active)) return;
            // tickActiveChest returns false if the configured display window
            // has already expired → fall through to countdown or hide
        }

        // ── Priority 2: countdown bar ─────────────────────────────────────────
        if (showCountdown) {
            if (tickCountdown()) return;
            // tickCountdown returns false if we are outside the configured
            // show window (countdown-show-minutes-before) → fall through to hide
        }

        // ── Nothing to show → hide the bar ────────────────────────────────────
        bossBar.setVisible(false);
    }

    // ── Active chest display ───────────────────────────────────────────────────

    /**
     * Update the bar for an active (unopened) chest.
     *
     * @return true  if the bar was updated and should be shown
     *         false if the configured display window has expired
     */
    private boolean tickActiveChest(SpawnChestPlugin.ActiveChest active) {
        long now = System.currentTimeMillis();

        // If chest was opened — show a different message until it disappears
        if (active.opened) {
            int disappearMinutes = plugin.getConfig().getInt(
                "settings.chest-disappear-minutes", 30);
            if (disappearMinutes <= 0) return false; // no disappear timer — hide bar

            long expiresAt = active.spawnTime + disappearMinutes * 60_000L;
            long remaining = Math.max(0L, expiresAt - now);
            if (remaining <= 0) return false;

            long mins = remaining / 60_000L;
            long secs = (remaining % 60_000L) / 1_000L;
            double progress = (double) remaining / (disappearMinutes * 60_000L);

            bossBar.setColor(parseColor("features.bossbar.color-active", BarColor.GREEN));
            bossBar.setProgress(clamp(progress));
            bossBar.setTitle(buildOpenedTitle(active, mins, secs));
            bossBar.setVisible(true);
            return true;
        }

        int showMinutes = plugin.getConfig().getInt(
            "features.bossbar.active-chest-show-minutes", 0);

        if (showMinutes > 0) {
            long windowMs  = showMinutes * 60_000L;
            long elapsed   = now - active.spawnTime;
            if (elapsed > windowMs) {
                // Display window expired — do not show the active bar
                return false;
            }
        }

        int disappearMinutes = plugin.getConfig().getInt(
            "settings.chest-disappear-minutes", 30);

        String tierName = plugin.getTierNamePublic(active.tier);
        int x = active.location.getBlockX();
        int y = active.location.getBlockY();
        int z = active.location.getBlockZ();

        if (disappearMinutes <= 0) {
            // Chest never disappears — show static bar, full progress
            bossBar.setColor(parseColor("features.bossbar.color-active", BarColor.GREEN));
            bossBar.setProgress(1.0);
            bossBar.setTitle(buildActiveTitle(tierName, x, y, z, -1, -1));
            bossBar.setVisible(true);
            return true;
        }

        // Calculate remaining lifetime
        long expiresAt = active.spawnTime + disappearMinutes * 60_000L;
        long remaining = Math.max(0L, expiresAt - now);
        long mins      = remaining / 60_000L;
        long secs      = (remaining % 60_000L) / 1_000L;
        double progress = (double) remaining / (disappearMinutes * 60_000L);

        bossBar.setColor(parseColor("features.bossbar.color-active", BarColor.GREEN));
        bossBar.setProgress(clamp(progress));
        bossBar.setTitle(buildActiveTitle(tierName, x, y, z, mins, secs));
        bossBar.setVisible(true);
        return true;
    }

    // ── Countdown display ──────────────────────────────────────────────────────

    /**
     * Update the bar for the next chest countdown.
     *
     * @return true  if the bar was updated and should be shown
     *         false if we are outside the configured show window
     */
    private boolean tickCountdown() {
        long now       = System.currentTimeMillis();
        long interval  = plugin.getSpawnInterval();
        long remaining = Math.max(0L, interval - (now - plugin.getLastSpawnTime()));

        // countdown-show-minutes-before: 0 = always show, N = only last N minutes
        int showMinutesBefore = plugin.getConfig().getInt(
            "features.bossbar.countdown-show-minutes-before", 5);

        if (showMinutesBefore > 0) {
            long windowMs = showMinutesBefore * 60_000L;
            if (remaining > windowMs) {
                // Still too far away from spawn — do not show countdown bar yet
                return false;
            }
        }

        long mins      = remaining / 60_000L;
        long secs      = (remaining % 60_000L) / 1_000L;
        double progress = interval > 0 ? (double) remaining / interval : 0.0;

        // Switch to "soon" color when under 60 seconds
        BarColor color = remaining < 60_000L
            ? parseColor("features.bossbar.color-soon",      BarColor.RED)
            : parseColor("features.bossbar.color-countdown", BarColor.YELLOW);

        bossBar.setColor(color);
        bossBar.setProgress(clamp(progress));
        bossBar.setTitle(buildCountdownTitle(mins, secs));
        bossBar.setVisible(true);
        return true;
    }

    // ── Title builders ─────────────────────────────────────────────────────────

    /**
     * BossBar title after chest is opened — shows disappear countdown.
     * Example: "✦ Legendary opened  ·  disappears in 28m 12s"
     */
    private String buildOpenedTitle(SpawnChestPlugin.ActiveChest active,
                                    long mins, long secs) {
        String tierName = plugin.getTierNamePublic(active.tier);
        if (mins > 0) {
            return String.format("§7✦ §8%s opened  §7·  §8disappears in §f%dm %02ds",
                tierName, mins, secs);
        }
        return String.format("§7✦ §8%s opened  §7·  §cdisappears in §c%ds",
            tierName, secs);
    }
    /**
     * Pre-open countdown title.
     * Example: "⏳ Chest activating  ·  activated by Steve  ·  22s"
     */
    private String buildPreOpenTitle(SpawnChestPlugin.ActiveChest active, long remaining) {
        String tierName = plugin.getTierNamePublic(active.tier);
        if (remaining > 0) {
            return String.format("§d⏳ §e%s  §7·  §fopens in §c%ds",
                tierName, remaining);
        }
        return String.format("§a✦ §e%s  §7·  §fNow open!", tierName);
    }
    /**
     * Countdown title examples:
     *   "✦ Next chest  ·  3m 42s"
     *   "✦ Next chest  ·  12s"   (red when < 60 s)
     */
 // стало
    private String buildCountdownTitle(long mins, long secs) {
        LanguageManager lang = plugin.getLanguageManager();
        if (mins > 0) {
            return lang.getMessage("bossbar.countdown-minutes",
                "%minutes%", String.valueOf(mins),
                "%seconds%", String.format("%02d", secs));
        }
        return lang.getMessage("bossbar.countdown-seconds",
            "%seconds%", String.valueOf(secs));
    }

    private String buildActiveTitle(String tierName,
                                    int x, int y, int z,
                                    long mins, long secs) {
        LanguageManager lang = plugin.getLanguageManager();
        String coords = x + ", " + y + ", " + z;

        if (mins < 0) {
            // No expiry — static bar
            return lang.getMessage("bossbar.active-no-timer",
                "%tier%", tierName,
                "%x%", String.valueOf(x),
                "%y%", String.valueOf(y),
                "%z%", String.valueOf(z));
        }

        String timeStr = mins > 0
            ? lang.getMessage("bossbar.time-minutes",
                "%minutes%", String.valueOf(mins),
                "%seconds%", String.format("%02d", secs))
            : lang.getMessage("bossbar.time-seconds",
                "%seconds%", String.valueOf(secs));

        return lang.getMessage("bossbar.active-with-timer",
            "%tier%",    tierName,
            "%x%",       String.valueOf(x),
            "%y%",       String.valueOf(y),
            "%z%",       String.valueOf(z),
            "%time%",    timeStr);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Return the first unopened active chest, or null if none. */
    private SpawnChestPlugin.ActiveChest firstUnopenedChest() {
        for (SpawnChestPlugin.ActiveChest c : plugin.getActiveChests().values()) {
            if (!c.opened) return c;
        }
        return null;
    }

    /** Read a boolean config value with a default. */
    private boolean cfg(String path, boolean def) {
        return plugin.getConfig().getBoolean(path, def);
    }

    /** isEnabled() reads the config live so reload takes effect instantly. */
    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("features.bossbar.enabled", false);
    }

    /**
     * Parse a BarColor from config. Falls back to the provided default
     * if the value is missing or not a valid BarColor name.
     */
    private BarColor parseColor(String path, BarColor fallback) {
        String raw = plugin.getConfig().getString(path, fallback.name()).toUpperCase();
        try {
            return BarColor.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double clamp(double v) {
        return Math.min(1.0, Math.max(0.0, v));
    }
}