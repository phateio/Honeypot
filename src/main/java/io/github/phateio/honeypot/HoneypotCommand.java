package io.github.phateio.honeypot;

import java.util.ArrayList;
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
            List.of("pos1", "pos2", "create", "region", "list", "delete", "save", "reload");

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
                    ? "selection mode on: right-click blocks with an empty main hand to mark them into '"
                            + plugin.activePot(player.getUniqueId()) + "'."
                    : "selection mode off."));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "pos1" -> setCorner(sender, args, pos1, "pos1");
            case "pos2" -> setCorner(sender, args, pos2, "pos2");
            case "create" -> create(sender, args);
            case "region" -> createRegion(sender);
            case "list" -> list(sender, args);
            case "delete" -> delete(sender, args);
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

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cplayers only.");
            return;
        }
        if (args.length != 2) {
            sender.sendMessage("§cusage: /hp create <name>");
            return;
        }
        String name = args[1];
        plugin.setActivePot(player.getUniqueId(), name);
        if (!plugin.isSelecting(player.getUniqueId())) {
            plugin.toggleSelecting(player.getUniqueId());
        }
        Pot existing = plugin.registry().pot(name);
        sender.sendMessage(PREFIX + (existing == null ? "now marking into new honeypot '" : "now marking into '")
                + name + "': right-click blocks or set pos1/pos2 and /hp region.");
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
        String potName = plugin.activePot(player.getUniqueId());
        plugin.registry().addRegion(potName, new Region(a.world(), a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
        Pot pot = plugin.registry().pot(potName);
        sender.sendMessage(PREFIX + "added region #" + pot.regions().size() + " to '" + potName + "': "
                + describe(pot.regions().getLast()));
    }

    private void list(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            listOne(sender, args[1]);
            return;
        }
        var pots = plugin.registry().pots();
        if (pots.isEmpty()) {
            sender.sendMessage(PREFIX + "no honeypots.");
            return;
        }
        sender.sendMessage(PREFIX + pots.size() + " honeypot(s):");
        for (Pot pot : pots) {
            sender.sendMessage("§f- " + pot.name() + ": " + pot.regions().size() + " region(s), "
                    + pot.blocks().size() + " block(s)");
        }
    }

    private void listOne(CommandSender sender, String name) {
        Pot pot = plugin.registry().pot(name);
        if (pot == null) {
            sender.sendMessage("§cno honeypot named '" + name + "'.");
            return;
        }
        sender.sendMessage(PREFIX + "'" + name + "': " + pot.regions().size() + " region(s), "
                + pot.blocks().size() + " block(s)");
        List<Region> regions = pot.regions();
        for (int i = 0; i < regions.size(); i++) {
            sender.sendMessage("§f  R" + (i + 1) + ". " + describe(regions.get(i)));
        }
        for (BlockPos block : pot.blocks()) {
            sender.sendMessage("§f  B. " + block.serialize());
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cusage: /hp delete <name> [region-number]");
            return;
        }
        String name = args[1];
        if (args.length == 2) {
            Pot removed = plugin.registry().deletePot(name);
            sender.sendMessage(removed == null
                    ? "§cno honeypot named '" + name + "'."
                    : PREFIX + "deleted honeypot '" + name + "'.");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cregion number must be an integer.");
            return;
        }
        Region removed = plugin.registry().deleteRegion(name, index - 1);
        sender.sendMessage(removed == null
                ? "§cno region #" + index + " in '" + name + "' (see /hp list " + name + ")."
                : PREFIX + "deleted region #" + index + " from '" + name + "': " + describe(removed));
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
        if ((sub.equals("create") || sub.equals("list") || sub.equals("delete")) && args.length == 2) {
            return potNames(args[1]);
        }
        if (sub.equals("delete") && args.length == 3) {
            Pot pot = plugin.registry().pot(args[1]);
            int count = pot == null ? 0 : pot.regions().size();
            return IntStream.rangeClosed(1, count)
                    .mapToObj(String::valueOf)
                    .filter(number -> number.startsWith(args[2]))
                    .toList();
        }
        return List.of();
    }

    private List<String> potNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (Pot pot : plugin.registry().pots()) {
            if (pot.name().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                names.add(pot.name());
            }
        }
        return names;
    }
}
