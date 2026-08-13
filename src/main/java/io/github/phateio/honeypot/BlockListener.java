package io.github.phateio.honeypot;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.BoundingBox;

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

        // Hangings resting on this block detach through a physics event that names
        // no player, so they are handled here — attributed to whoever pulled the
        // block out, while they still exist and can be snapshotted. This runs
        // before the block's own mark is consulted: a marked frame hanging on an
        // unmarked wall must not be a free way around the honeypot.
        List<HangingSnapshot> scored = new ArrayList<>();
        List<HangingSnapshot> collateral = new ArrayList<>();
        for (HangingSnapshot hanging : restingOn(block)) {
            (registry.covers(hanging) ? scored : collateral).add(hanging);
        }
        boolean marked = registry.isHoneypot(pos);
        if (!marked && scored.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("honeypot.break")) {
            // Immune; breaking an individually marked block removes the mark.
            // Blocks only covered by a region break normally (regions are
            // managed with /hp delete).
            if (marked) {
                String potName = registry.removeBlock(pos);
                if (potName != null) {
                    player.sendMessage("§6[Honeypot] §fmark removed from '" + potName + "': " + pos.serialize());
                }
            }
            return;
        }

        HoneypotConfig config = plugin.settings();
        OffenseTracker tracker = plugin.tracker();
        int total = 0;
        if (marked) {
            total = tracker.recordBreak(player.getUniqueId(), pos, block.getState(),
                    config.pointsFor(block.getType()));
            plugin.alertOffense(player, "block " + block.getType(), pos, block.getType(), total);
            // Unmarked hangings on a marked block are part of what the honeypot
            // protects, so they come back on rollback — but the block break is
            // the scored offense and they earn no points of their own.
            for (HangingSnapshot hanging : collateral) {
                tracker.snapshotHanging(player.getUniqueId(), hanging);
            }
        } else {
            // The block is not marked, but a marked hanging rests on it. Record
            // it for rollback anyway and score it nothing: without the block
            // back, restoring the hanging leaves it with nothing to hang on and
            // it simply drops again.
            tracker.recordBreak(player.getUniqueId(), pos, block.getState(), 0);
        }
        for (HangingSnapshot hanging : scored) {
            total = tracker.recordHangingBreak(player.getUniqueId(), hanging,
                    config.pointsFor(hanging.material()));
            plugin.alertOffense(player, hanging.describe(), hanging.pos(), hanging.material(), total);
        }
        plugin.punishIfTripped(player, total, event);
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

    /** Snapshots of every hanging that rests on this block, marked or not. */
    private List<HangingSnapshot> restingOn(Block block) {
        BlockPos pos = BlockPos.of(block);
        // Nothing marked anywhere near means nothing here can be a honeypot
        // hanging. A painting reaches at most MAX_SPAN blocks from what it rests
        // on, and this check keeps the entity lookup off the common path of
        // someone mining far from any honeypot.
        if (!plugin.registry().hasMarkWithin(pos, HangingSnapshot.MAX_SPAN)) {
            return List.of();
        }
        // Whatever rests on this block occupies the space next to it, so a
        // one-block margin catches even a large painting by its bounding box.
        BoundingBox around = BoundingBox.of(block).expand(1.0);
        List<HangingSnapshot> out = new ArrayList<>();
        for (Entity entity : block.getWorld().getNearbyEntities(around, e -> e instanceof Hanging)) {
            HangingSnapshot hanging = HangingSnapshot.of((Hanging) entity);
            if (hanging.hangsOn(pos)) {
                out.add(hanging);
            }
        }
        return out;
    }
}
