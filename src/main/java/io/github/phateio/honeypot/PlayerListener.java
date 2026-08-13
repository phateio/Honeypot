package io.github.phateio.honeypot;

import org.bukkit.block.Block;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Selection-mode marking plus login/logout bookkeeping for delayed rollbacks. */
public final class PlayerListener implements Listener {

    private final Honeypot plugin;

    public PlayerListener(Honeypot plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.isSelecting(player.getUniqueId())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && !item.getType().isAir()) {
            return; // holding something: interact normally, don't mark
        }
        if (!player.hasPermission("honeypot.create")) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        event.setCancelled(true);
        BlockPos pos = BlockPos.of(block);
        String potName = plugin.activePot(player.getUniqueId());
        if (plugin.registry().addBlock(potName, pos)) {
            player.sendMessage("§6[Honeypot] §fmarked " + block.getType() + " at " + pos.serialize()
                    + " in '" + potName + "'");
        } else {
            player.sendMessage("§6[Honeypot] §falready marked: " + pos.serialize());
        }
    }

    /**
     * Marks a hanging by right-clicking it. Its position is an air block that
     * the hanging itself covers, so the block handler above can never reach it:
     * clicking the hanging sends an interact-entity packet rather than
     * RIGHT_CLICK_BLOCK, and clearing it away first only yields RIGHT_CLICK_AIR.
     * Targeting commands raytrace blocks and return the wall behind, so without
     * this the only route to a marked hanging is typing its anchor coordinates —
     * which nothing in the game will tell you.
     */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Hanging hanging)) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.isSelecting(player.getUniqueId())) {
            return;
        }
        if (!player.getInventory().getItemInMainHand().getType().isAir()) {
            return; // holding something: interact normally, don't mark
        }
        if (!player.hasPermission("honeypot.create")) {
            return;
        }
        event.setCancelled(true);
        BlockPos pos = HangingSnapshot.blockPosOf(hanging);
        String potName = plugin.activePot(player.getUniqueId());
        if (plugin.registry().addBlock(potName, pos)) {
            player.sendMessage("§6[Honeypot] §fmarked " + hanging.getType() + " at " + pos.serialize()
                    + " in '" + potName + "'");
        } else {
            player.sendMessage("§6[Honeypot] §falready marked: " + pos.serialize());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Cancel the pending offline rollback; the player is back.
        plugin.tracker().clearLogout(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        handleLeave(event.getPlayer());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        handleLeave(event.getPlayer());
    }

    private void handleLeave(Player player) {
        plugin.clearSelecting(player.getUniqueId());
        plugin.tracker().markLogout(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }
}
