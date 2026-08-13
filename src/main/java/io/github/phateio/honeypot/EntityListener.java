package io.github.phateio.honeypot;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Honeypot coverage for hanging entities standing inside a marked position:
 * item frames, glow item frames and paintings.
 *
 * <p>Blocks and hangings fail through different events, and a frame with an item
 * in it fails through a third: hitting it drops the item and leaves the frame
 * standing, which never reaches {@link HangingBreakByEntityEvent}. Both paths
 * are covered here so stealing a displayed item counts the same as tearing the
 * whole frame off the wall.
 */
public final class EntityListener implements Listener {

    private final Honeypot plugin;

    public EntityListener(Honeypot plugin) {
        this.plugin = plugin;
    }

    /** The hanging itself being torn down — includes frames that are already empty. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        handle(resolvePlayer(event.getRemover()), event.getEntity(), event);
    }

    /** Hitting a frame that holds something drops the item and spares the frame. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || frame.getItem().getType().isAir()) {
            return; // an empty frame breaks instead, which the hanging event covers
        }
        handle(resolvePlayer(event.getDamager()), frame, event);
    }

    private void handle(Player player, Hanging hanging, Cancellable event) {
        if (player == null) {
            return; // mob, explosion or dispenser: nobody to hold responsible
        }
        HangingSnapshot snapshot = HangingSnapshot.of(hanging);
        if (!plugin.registry().covers(snapshot)) {
            return;
        }
        if (player.hasPermission("honeypot.break")) {
            // Immune. Unlike a block, a hanging carries no individual mark to
            // remove — the position stays marked for whoever comes next.
            return;
        }
        int total = plugin.tracker().recordHangingBreak(player.getUniqueId(), snapshot,
                plugin.settings().pointsFor(snapshot.material()));
        plugin.alertOffense(player, snapshot.describe(), snapshot.pos(), snapshot.material(), total);
        plugin.punishIfTripped(player, total, event);
    }

    /** The player behind the damage, directly or through something they shot. */
    private static Player resolvePlayer(Entity source) {
        if (source instanceof Player player) {
            return player;
        }
        if (source instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }
}
