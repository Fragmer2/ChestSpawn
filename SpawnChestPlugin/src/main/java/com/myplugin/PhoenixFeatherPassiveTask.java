package com.myplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class PhoenixFeatherPassiveTask extends BukkitRunnable {

    private final SpawnChestPlugin plugin;

    public PhoenixFeatherPassiveTask(SpawnChestPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.hasPhoenixFeatherInHand(player)) {
                player.addPotionEffect(
                    new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 100, 0, true, false)
                );
            }
        }
    }
}
