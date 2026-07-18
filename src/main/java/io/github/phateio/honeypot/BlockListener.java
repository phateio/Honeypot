package io.github.phateio.honeypot;

import java.util.Locale;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/** Break and placement handling for marked honeypot positions. */
public final class BlockListener implements Listener {

    private final Honeypot plugin;

    public BlockListener(Honeypot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockPos pos = BlockPos.of(block);
        PotRegistry registry = plugin.registry();
        if (!registry.isHoneypot(pos)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("honeypot.break")) {
            // Immune; breaking an individually marked block removes the mark.
            // Blocks only covered by a region break normally (regions are
            // managed with /hp delregion).
            if (registry.removeBlock(pos)) {
                player.sendMessage("§6[Honeypot] §fmark removed: " + pos.serialize());
            }
            return;
        }
        HoneypotConfig config = plugin.settings();
        OffenseTracker tracker = plugin.tracker();
        int total = tracker.recordBreak(player.getUniqueId(), pos, block.getState(),
                config.pointsFor(block.getType()));
        plugin.logEvent(player.getName() + " broke honeypot block " + block.getType()
                + " at " + pos.serialize() + " (" + total + "/" + config.offensePoints() + " points)");
        if (config.offensePoints() > 0 && total < config.offensePoints()) {
            return; // below threshold: the break stands until rollback
        }
        tracker.rollback(player.getUniqueId());
        event.setCancelled(true);
        plugin.punisher().punish(player);
        plugin.logEvent("Caught " + player.getName() + " (action: "
                + config.action().name().toLowerCase(Locale.ROOT) + ", " + total + " points)");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        BlockPos pos = BlockPos.of(event.getBlock());
        if (!plugin.registry().isHoneypot(pos)) {
            return;
        }
        if (event.getPlayer().hasPermission("honeypot.place")) {
            return;
        }
        // Not cancelled and worth no points; the position is removed again on
        // rollback so honeypot structures can't be altered by building.
        plugin.tracker().recordPlace(event.getPlayer().getUniqueId(), pos);
    }
}
