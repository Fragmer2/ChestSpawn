package com.myplugin;

import org.bukkit.Particle;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Collection;

public class ArrowHomingTask extends BukkitRunnable {

    private final Arrow arrow;
    private final Player shooter;
    private final double maxDistance;
    private final boolean pvpProtection;
    private final SpawnChestPlugin plugin;
    private int ticks = 0;

    public ArrowHomingTask(Arrow arrow, Player shooter, double maxDistance, SpawnChestPlugin plugin) {
        this.arrow = arrow;
        this.shooter = shooter;
        this.maxDistance = maxDistance;
        this.pvpProtection = true;
        this.plugin = plugin;
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
        Collection<LivingEntity> nearbyEntities = plugin.getVersionAdapter().getNearbyLivingEntities(
            arrow.getLocation(), maxDistance);

        for (LivingEntity living : nearbyEntities) {
            if (living == shooter) continue;
            if (pvpProtection && living instanceof Player) continue;

            double distance = living.getLocation().distance(arrow.getLocation());
            if (distance < minDistance) {
                minDistance = distance;
                target = living;
            }
        }

        if (target != null) {
            Vector direction = target.getEyeLocation().subtract(arrow.getLocation()).toVector().normalize();
            arrow.setVelocity(direction.multiply(1.5));
            arrow.getWorld().spawnParticle(Particle.ENCHANTMENT_TABLE, arrow.getLocation(), 1);
        }
    }
}