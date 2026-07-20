package io.github.phateio.honeypot;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
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
