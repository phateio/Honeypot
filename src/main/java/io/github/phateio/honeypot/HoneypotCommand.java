package io.github.phateio.honeypot;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class HoneypotCommand implements TabExecutor {

    private static final String PREFIX = "§6[Honeypot] §f";
    private static final List<String> SUB_COMMANDS =
            List.of("pos1", "pos2", "region", "regions", "delregion", "save", "reload");

    private final Honeypot plugin;
    private final Map<UUID, BlockPos> pos1 = new HashMap<>();
    private final Map<UUID, BlockPos> pos2 = new HashMap<>();

    public HoneypotCommand(Honeypot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cselection mode is for players only; see " + command.getUsage());
                return true;
            }
            boolean selecting = plugin.toggleSelecting(player.getUniqueId());
            player.sendMessage(PREFIX + (selecting
                    ? "selection mode on: right-click blocks with an empty main hand to mark them."
                    : "selection mode off."));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pos1" -> setCorner(sender, args, pos1, "pos1");
            case "pos2" -> setCorner(sender, args, pos2, "pos2");
            case "region" -> createRegion(sender);
            case "regions" -> listRegions(sender);
            case "delregion" -> deleteRegion(sender, args);
            case "save" -> {
                plugin.registry().save();
                sender.sendMessage(PREFIX + "honeypot data saved.");
            }
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(PREFIX + "config and honeypot data reloaded.");
            }
            default -> sender.sendMessage("§cusage: " + command.getUsage());
        }
        return true;
    }

    private void setCorner(CommandSender sender, String[] args, Map<UUID, BlockPos> store, String name) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cplayers only.");
            return;
        }
        BlockPos pos;
        if (args.length == 4) {
            try {
                pos = new BlockPos(player.getWorld().getName(), Integer.parseInt(args[1]),
                        Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            } catch (NumberFormatException e) {
                sender.sendMessage("§ccoordinates must be integers.");
                return;
            }
        } else if (args.length == 1) {
            pos = BlockPos.of(targetBlock(player));
        } else {
            sender.sendMessage("§cusage: /hp " + name + " [x y z]");
            return;
        }
        store.put(player.getUniqueId(), pos);
        sender.sendMessage(PREFIX + name + " set to " + pos.serialize());
    }

    /** The block in the player's crosshair within 10 blocks, else the block underfoot. */
    private static Block targetBlock(Player player) {
        Block target = player.getTargetBlockExact(10);
        return target != null ? target : player.getLocation().subtract(0, 1, 0).getBlock();
    }

    private void createRegion(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cplayers only.");
            return;
        }
        BlockPos a = pos1.get(player.getUniqueId());
        BlockPos b = pos2.get(player.getUniqueId());
        if (a == null || b == null) {
            sender.sendMessage("§cset pos1 and pos2 first.");
            return;
        }
        if (!a.world().equals(b.world())) {
            sender.sendMessage("§cpos1 and pos2 must be in the same world.");
            return;
        }
        plugin.registry().addRegion(new Region(a.world(), a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
        List<Region> regions = plugin.registry().regions();
        sender.sendMessage(PREFIX + "region #" + regions.size() + " created: "
                + describe(regions.getLast()));
    }

    private void listRegions(CommandSender sender) {
        List<Region> regions = plugin.registry().regions();
        if (regions.isEmpty()) {
            sender.sendMessage(PREFIX + "no regions.");
            return;
        }
        sender.sendMessage(PREFIX + regions.size() + " region(s):");
        for (int i = 0; i < regions.size(); i++) {
            sender.sendMessage("§f" + (i + 1) + ". " + describe(regions.get(i)));
        }
    }

    private void deleteRegion(CommandSender sender, String[] args) {
        int index;
        try {
            index = args.length == 2 ? Integer.parseInt(args[1]) : 0;
        } catch (NumberFormatException e) {
            index = 0;
        }
        if (index < 1) {
            sender.sendMessage("§cusage: /hp delregion <number> (see /hp regions)");
            return;
        }
        Region removed = plugin.registry().removeRegion(index - 1);
        if (removed == null) {
            sender.sendMessage("§cno region #" + index + ".");
        } else {
            sender.sendMessage(PREFIX + "region #" + index + " deleted: " + describe(removed));
        }
    }

    private static String describe(Region region) {
        return region.world() + " (" + region.minX() + ", " + region.minY() + ", " + region.minZ()
                + ") to (" + region.maxX() + ", " + region.maxY() + ", " + region.maxZ() + ")";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUB_COMMANDS.stream().filter(sub -> sub.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("pos1") || sub.equals("pos2")) && args.length <= 4
                && sender instanceof Player player) {
            // Like vanilla /tp: prefill the coordinates of the targeted block.
            Block target = targetBlock(player);
            int coordinate = switch (args.length) {
                case 2 -> target.getX();
                case 3 -> target.getY();
                default -> target.getZ();
            };
            return List.of(String.valueOf(coordinate));
        }
        if (sub.equals("delregion") && args.length == 2) {
            return IntStream.rangeClosed(1, plugin.registry().regions().size())
                    .mapToObj(String::valueOf)
                    .filter(number -> number.startsWith(args[1]))
                    .toList();
        }
        return List.of();
    }
}
