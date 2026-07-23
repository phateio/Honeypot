package io.github.phateio.honeypot;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Immutable snapshot of config.yml, validated at load time. */
public record HoneypotConfig(
        Action action,
        boolean broadcast,
        String banReason,
        String kickMessage,
        String banSource,
        String broadcastMessage,
        List<String> banCommands,
        List<String> kickCommands,
        int offensePoints,
        Map<Material, Integer> offensePointMap,
        boolean logToFile,
        boolean discordNotify,
        String discordChannel,
        String discordMessage) {

    /** What happens when a player trips the honeypot. */
    public enum Action { BAN, KICK, NONE }

    /** Blocks not listed in offensePointMap are worth 1 point. */
    public int pointsFor(Material material) {
        return offensePointMap.getOrDefault(material, 1);
    }

    public static HoneypotConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        String rawAction = config.getString("action", "ban");
        Action action;
        try {
            action = Action.valueOf(rawAction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown action '" + rawAction + "'; falling back to ban.");
            action = Action.BAN;
        }
        Map<Material, Integer> points = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("offense-point-map");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                // Exact Material names only (legacy numeric IDs are gone from the API).
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning("Ignoring unknown material in offense-point-map: " + key);
                    continue;
                }
                points.put(material, section.getInt(key));
            }
        }
        return new HoneypotConfig(
                action,
                config.getBoolean("broadcast", true),
                config.getString("ban-reason", "griefing"),
                config.getString("kick-message", "You have been caught destroying a honeypot block."),
                config.getString("ban-source", "Server"),
                config.getString("broadcast-message",
                        "<dark_red>[Honeypot]<gray> Player <dark_red><player><gray>"
                                + " was caught breaking a honeypot block."),
                List.copyOf(config.getStringList("ban-commands")),
                List.copyOf(config.getStringList("kick-commands")),
                config.getInt("offense-points", 32),
                Map.copyOf(points),
                config.getBoolean("log-to-file", true),
                config.getBoolean("discord-notify", true),
                config.getString("discord-channel", "global"),
                config.getString("discord-message",
                        ":rotating_light: **<player>** was caught breaking a honeypot block."));
    }
}
