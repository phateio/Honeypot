package io.github.phateio.honeypot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The set of named honeypots. Each {@link Pot} holds any mix of individually
 * marked blocks and cuboid regions. Persisted to honeypots.yml on every change;
 * imports the original plugin's list.ncsv once when no YAML file exists yet.
 */
public final class PotRegistry {

    private static final String FILE_NAME = "honeypots.yml";
    private static final String LEGACY_FILE_NAME = "list.ncsv";
    /** Bucket for ad-hoc marks and for wrapping legacy flat/ncsv data. */
    public static final String DEFAULT_POT = "default";

    private final JavaPlugin plugin;
    private final Map<String, Pot> pots = new LinkedHashMap<>();

    public PotRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isHoneypot(BlockPos pos) {
        for (Pot pot : pots.values()) {
            if (pot.blocks().contains(pos)) {
                return true;
            }
            for (Region region : pot.regions()) {
                if (region.contains(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Pot potFor(String name) {
        return pots.computeIfAbsent(name, Pot::new);
    }

    /** @return false if the block is already individually marked in some pot */
    public boolean addBlock(String potName, BlockPos pos) {
        for (Pot pot : pots.values()) {
            if (pot.blocks().contains(pos)) {
                return false;
            }
        }
        potFor(potName).blocks().add(pos);
        save();
        return true;
    }

    /** Removes an individually marked block (region coverage doesn't count).
     * @return the name of the pot it belonged to, or null if not individually marked */
    public String removeBlock(BlockPos pos) {
        for (Pot pot : pots.values()) {
            if (pot.blocks().contains(pos)) {
                pot.blocks().remove(pos);
                dropIfEmpty(pot);
                save();
                return pot.name();
            }
        }
        return null;
    }

    public void addRegion(String potName, Region region) {
        potFor(potName).regions().add(region);
        save();
    }

    /** @return the removed pot, or null if no pot has that name */
    public Pot deletePot(String name) {
        Pot removed = pots.remove(name);
        if (removed != null) {
            save();
        }
        return removed;
    }

    /** @return the removed region, or null if the pot or index (0-based) is invalid */
    public Region deleteRegion(String potName, int index) {
        Pot pot = pots.get(potName);
        if (pot == null || index < 0 || index >= pot.regions().size()) {
            return null;
        }
        Region removed = pot.regions().remove(index);
        dropIfEmpty(pot);
        save();
        return removed;
    }

    private void dropIfEmpty(Pot pot) {
        if (pot.isEmpty()) {
            pots.remove(pot.name());
        }
    }

    public Collection<Pot> pots() {
        return List.copyOf(pots.values());
    }

    public Pot pot(String name) {
        return pots.get(name);
    }

    public void load() {
        pots.clear();
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        File legacy = new File(plugin.getDataFolder(), LEGACY_FILE_NAME);
        if (!file.exists()) {
            if (legacy.exists()) {
                importLegacy(legacy);
            }
            logLoaded();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("honeypots");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(name);
                if (section == null) {
                    continue;
                }
                Pot pot = potFor(name);
                readBlocks(pot, section.getStringList("blocks"), name);
                readRegions(pot, section.getStringList("regions"), name);
                dropIfEmpty(pot);
            }
        } else if (yaml.isList("blocks") || yaml.isList("regions")) {
            // Back-compat: pre-2.1 flat lists. Wrap into the default pot and
            // rewrite in the named format.
            Pot pot = potFor(DEFAULT_POT);
            readBlocks(pot, yaml.getStringList("blocks"), DEFAULT_POT);
            readRegions(pot, yaml.getStringList("regions"), DEFAULT_POT);
            dropIfEmpty(pot);
            save();
        }
        logLoaded();
    }

    private void readBlocks(Pot pot, List<String> lines, String potName) {
        for (String line : lines) {
            try {
                pot.blocks().add(BlockPos.deserialize(line));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad block in honeypot '" + potName + "': " + line);
            }
        }
    }

    private void readRegions(Pot pot, List<String> lines, String potName) {
        for (String line : lines) {
            try {
                pot.regions().add(Region.deserialize(line));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad region in honeypot '" + potName + "': " + line);
            }
        }
    }

    private void logLoaded() {
        int blocks = pots.values().stream().mapToInt(p -> p.blocks().size()).sum();
        int regions = pots.values().stream().mapToInt(p -> p.regions().size()).sum();
        plugin.getLogger().info("Loaded " + pots.size() + " honeypot(s) ("
                + blocks + " blocks, " + regions + " regions).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        // Build a nested map so nothing depends on '.' not appearing in pot
        // names, and LinkedHashMap keeps the pots in their existing order.
        Map<String, Object> root = new LinkedHashMap<>();
        for (Pot pot : pots.values()) {
            Map<String, Object> section = new LinkedHashMap<>();
            if (!pot.regions().isEmpty()) {
                section.put("regions", pot.regions().stream().map(Region::serialize).toList());
            }
            if (!pot.blocks().isEmpty()) {
                // Sorted for a stable, diffable file.
                section.put("blocks", pot.blocks().stream().map(BlockPos::serialize).sorted().toList());
            }
            if (!section.isEmpty()) {
                root.put(pot.name(), section);
            }
        }
        yaml.set("honeypots", root);
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + FILE_NAME, e);
        }
    }

    /**
     * One-time import of the original plugin's newline-separated CSV into the
     * default pot. Line formats: {@code region:world,minX,minY,minZ,maxX,maxY,maxZ},
     * {@code world,x,y,z}, or the ancient 3-column {@code x,y,z} (world "world").
     * Coordinates may be decimal strings and are floored.
     */
    private void importLegacy(File legacy) {
        List<String> lines;
        try {
            lines = Files.readAllLines(legacy.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read " + LEGACY_FILE_NAME, e);
            return;
        }
        Pot pot = potFor(DEFAULT_POT);
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
                    pot.regions().add(new Region(p[0],
                            floorInt(p[1]), floorInt(p[2]), floorInt(p[3]),
                            floorInt(p[4]), floorInt(p[5]), floorInt(p[6])));
                } else {
                    String[] p = line.split(",");
                    BlockPos block = switch (p.length) {
                        case 4 -> new BlockPos(p[0], floorInt(p[1]), floorInt(p[2]), floorInt(p[3]));
                        case 3 -> new BlockPos("world", floorInt(p[0]), floorInt(p[1]), floorInt(p[2]));
                        default -> throw new IllegalArgumentException("expected 3 or 4 fields");
                    };
                    pot.blocks().add(block);
                }
                imported++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bad " + LEGACY_FILE_NAME + " line "
                        + (i + 1) + ": " + line);
            }
        }
        dropIfEmpty(pot);
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
                + " into honeypot '" + DEFAULT_POT + "'.");
    }

    private static int floorInt(String value) {
        return (int) Math.floor(Double.parseDouble(value.trim()));
    }
}
