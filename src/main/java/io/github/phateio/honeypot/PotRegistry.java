package io.github.phateio.honeypot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The set of marked honeypot positions: individual blocks plus cuboid regions.
 * Persisted to honeypots.yml on every change; imports the original plugin's
 * list.ncsv once when no YAML file exists yet.
 */
public final class PotRegistry {

    private static final String FILE_NAME = "honeypots.yml";
    private static final String LEGACY_FILE_NAME = "list.ncsv";

    private final JavaPlugin plugin;
    private final Set<BlockPos> blocks = new HashSet<>();
    private final List<Region> regions = new ArrayList<>();

    public PotRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isHoneypot(BlockPos pos) {
        if (blocks.contains(pos)) {
            return true;
        }
        for (Region region : regions) {
            if (region.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** @return false if the block was already marked */
    public boolean addBlock(BlockPos pos) {
        if (!blocks.add(pos)) {
            return false;
        }
        save();
        return true;
    }

    /** @return false if the block was not individually marked (region cover doesn't count) */
    public boolean removeBlock(BlockPos pos) {
        if (!blocks.remove(pos)) {
            return false;
        }
        save();
        return true;
    }

    public void addRegion(Region region) {
        regions.add(region);
        save();
    }

    /** @return the removed region, or null if {@code index} is out of range (0-based) */
    public Region removeRegion(int index) {
        if (index < 0 || index >= regions.size()) {
            return null;
        }
        Region removed = regions.remove(index);
        save();
        return removed;
    }

    public List<Region> regions() {
        return List.copyOf(regions);
    }

    public int blockCount() {
        return blocks.size();
    }

    public void load() {
        blocks.clear();
        regions.clear();
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        File legacy = new File(plugin.getDataFolder(), LEGACY_FILE_NAME);
        if (!file.exists()) {
            if (legacy.exists()) {
                importLegacy(legacy);
            }
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String line : yaml.getStringList("blocks")) {
            try {
                blocks.add(BlockPos.deserialize(line));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad block entry in " + FILE_NAME + ": " + line);
            }
        }
        for (String line : yaml.getStringList("regions")) {
            try {
                regions.add(Region.deserialize(line));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad region entry in " + FILE_NAME + ": " + line);
            }
        }
        plugin.getLogger().info("Loaded " + blocks.size() + " honeypot blocks and "
                + regions.size() + " regions.");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        // Blocks are sorted for stable, diffable files; regions keep their
        // insertion order because /hp delregion addresses them by number.
        yaml.set("blocks", blocks.stream().map(BlockPos::serialize).sorted().toList());
        yaml.set("regions", regions.stream().map(Region::serialize).toList());
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + FILE_NAME, e);
        }
    }

    /**
     * One-time import of the original plugin's newline-separated CSV. Line
     * formats: {@code region:world,minX,minY,minZ,maxX,maxY,maxZ},
     * {@code world,x,y,z}, or the ancient 3-column {@code x,y,z} (world
     * "world"). Coordinates may be decimal strings and are floored.
     */
    private void importLegacy(File legacy) {
        List<String> lines;
        try {
            lines = Files.readAllLines(legacy.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read " + LEGACY_FILE_NAME, e);
            return;
        }
        int imported = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith("region:")) {
                    String[] p = line.substring("region:".length()).split(",");
                    if (p.length != 7) {
                        throw new IllegalArgumentException("expected 7 fields");
                    }
                    regions.add(new Region(p[0],
                            floorInt(p[1]), floorInt(p[2]), floorInt(p[3]),
                            floorInt(p[4]), floorInt(p[5]), floorInt(p[6])));
                } else {
                    String[] p = line.split(",");
                    BlockPos pos = switch (p.length) {
                        case 4 -> new BlockPos(p[0], floorInt(p[1]), floorInt(p[2]), floorInt(p[3]));
                        case 3 -> new BlockPos("world", floorInt(p[0]), floorInt(p[1]), floorInt(p[2]));
                        default -> throw new IllegalArgumentException("expected 3 or 4 fields");
                    };
                    blocks.add(pos);
                }
                imported++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad " + LEGACY_FILE_NAME + " line "
                        + (i + 1) + ": " + line);
            }
        }
        save();
        try {
            Files.move(legacy.toPath(), legacy.toPath().resolveSibling(LEGACY_FILE_NAME + ".imported"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not rename " + LEGACY_FILE_NAME
                    + " after import; it will be re-imported on next start if "
                    + FILE_NAME + " is removed", e);
        }
        plugin.getLogger().info("Imported " + imported + " entries from " + LEGACY_FILE_NAME
                + " (" + blocks.size() + " blocks, " + regions.size() + " regions).");
    }

    private static int floorInt(String value) {
        return (int) Math.floor(Double.parseDouble(value.trim()));
    }
}
