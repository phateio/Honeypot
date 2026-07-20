package io.github.phateio.honeypot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;

/**
 * Per-player offense points plus the break/placement records needed to roll a
 * griefer's changes back. Everything lives in memory only; points are
 * intentionally never reset while the server runs, so a kicked player who
 * returns and breaks another honeypot block trips it again immediately.
 */
public final class OffenseTracker {

    /** How long a player may stay offline before their records are rolled back. */
    private static final long LOGOUT_ROLLBACK_MILLIS = 300_000;

    private record Logout(String name, long time) { }

    private final Honeypot plugin;
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Map<BlockPos, BlockState>> brokenBlocks = new HashMap<>();
    private final Map<UUID, Set<BlockPos>> placedBlocks = new HashMap<>();
    // Pre-break states of every honeypot block broken this runtime. Never
    // cleared: placement rollback must not turn a restored honeypot block into
    // air when a griefer breaks it and then places something at the same spot.
    private final Map<BlockPos, BlockState> originalStates = new HashMap<>();
    private final Map<UUID, Logout> logoutTimes = new HashMap<>();

    public OffenseTracker(Honeypot plugin) {
        this.plugin = plugin;
    }

    /** Records a break for later rollback and returns the player's new point total. */
    public int recordBreak(UUID player, BlockPos pos, BlockState state, int pointsEarned) {
        brokenBlocks.computeIfAbsent(player, k -> new LinkedHashMap<>()).putIfAbsent(pos, state);
        originalStates.putIfAbsent(pos, state);
        return points.merge(player, pointsEarned, Integer::sum);
    }

    public void recordPlace(UUID player, BlockPos pos) {
        placedBlocks.computeIfAbsent(player, k -> new HashSet<>()).add(pos);
    }

    public boolean hasRecords(UUID player) {
        return brokenBlocks.containsKey(player) || placedBlocks.containsKey(player);
    }

    /** Counts of what a rollback actually restored and removed. */
    public record Result(int broken, int placed) {
        public String describe() {
            if (broken > 0 && placed > 0) {
                return broken + " broken and " + placed + " placed block(s)";
            }
            if (placed > 0) {
                return placed + " placed block(s)";
            }
            return broken + " broken block(s)";
        }
    }

    /** Restores everything the player broke and removes what they placed. */
    public Result rollback(UUID player) {
        int brokenCount = 0;
        int placedCount = 0;
        Map<BlockPos, BlockState> broken = brokenBlocks.remove(player);
        if (broken != null) {
            for (BlockState state : broken.values()) {
                // Force the update but skip physics so e.g. gravel doesn't cascade.
                state.update(true, false);
            }
            brokenCount = broken.size();
        }
        Set<BlockPos> placed = placedBlocks.remove(player);
        if (placed != null) {
            for (BlockPos pos : placed) {
                if (originalStates.containsKey(pos)) {
                    continue; // a break record restores the honeypot block here
                }
                World world = Bukkit.getWorld(pos.world());
                if (world == null) {
                    plugin.getLogger().warning("Cannot roll back placement in unloaded world: "
                            + pos.serialize());
                    continue;
                }
                world.getBlockAt(pos.x(), pos.y(), pos.z()).setType(Material.AIR, false);
                placedCount++;
            }
        }
        return new Result(brokenCount, placedCount);
    }

    /** Rolls back every player with outstanding records (used on shutdown). */
    public void rollbackAll() {
        Set<UUID> players = new HashSet<>(brokenBlocks.keySet());
        players.addAll(placedBlocks.keySet());
        int broken = 0;
        int placed = 0;
        for (UUID player : players) {
            Result result = rollback(player);
            broken += result.broken();
            placed += result.placed();
        }
        if (broken + placed > 0) {
            plugin.logEvent("Rolled back " + new Result(broken, placed).describe() + " on shutdown.");
        }
        logoutTimes.clear();
    }

    public void markLogout(UUID player, String name, long now) {
        if (hasRecords(player)) {
            logoutTimes.put(player, new Logout(name, now));
        }
    }

    public void clearLogout(UUID player) {
        logoutTimes.remove(player);
    }

    /** Rolls back players who have been offline longer than the grace period. */
    public void sweep(long now) {
        Iterator<Map.Entry<UUID, Logout>> it = logoutTimes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Logout> entry = it.next();
            if (now - entry.getValue().time() < LOGOUT_ROLLBACK_MILLIS) {
                continue;
            }
            it.remove();
            Result result = rollback(entry.getKey());
            plugin.logEvent("Rolled back " + result.describe() + " by " + entry.getValue().name()
                    + " (offline > " + (LOGOUT_ROLLBACK_MILLIS / 1000) + "s).");
        }
    }
}
