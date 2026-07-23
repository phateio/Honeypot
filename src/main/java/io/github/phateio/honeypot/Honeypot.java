package io.github.phateio.honeypot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Honeypot extends JavaPlugin {

    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Logout sweep interval: 1200 ticks = 60 seconds. */
    private static final long SWEEP_PERIOD_TICKS = 1200;

    private final Set<UUID> selecting = new HashSet<>();
    private final Map<UUID, String> activePot = new HashMap<>();
    private HoneypotConfig settings;
    private PotRegistry registry;
    private OffenseTracker tracker;
    private Punisher punisher;
    private boolean discordSrvPresent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = HoneypotConfig.load(this);
        // softdepend guarantees DiscordSRV, if present, is loaded before us.
        discordSrvPresent = getServer().getPluginManager().getPlugin("DiscordSRV") != null;
        registry = new PotRegistry(this);
        registry.load();
        tracker = new OffenseTracker(this);
        punisher = new Punisher(this);
        getServer().getPluginManager().registerEvents(new BlockListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        HoneypotCommand executor = new HoneypotCommand(this);
        PluginCommand command = Objects.requireNonNull(getCommand("honeypot"));
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getScheduler().runTaskTimer(this,
                () -> tracker.sweep(System.currentTimeMillis()),
                SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            tracker.rollbackAll(); // don't let below-threshold breaks become permanent
        }
        // No save here: every mutation already persists immediately, so a
        // shutdown save would only rewrite an unchanged file and strip any
        // hand-added YAML comments.
    }

    /** Re-reads config.yml and honeypots.yml from disk. */
    public void reloadAll() {
        reloadConfig();
        settings = HoneypotConfig.load(this);
        registry.load();
    }

    public HoneypotConfig settings() {
        return settings;
    }

    public PotRegistry registry() {
        return registry;
    }

    public OffenseTracker tracker() {
        return tracker;
    }

    public Punisher punisher() {
        return punisher;
    }

    public boolean isSelecting(UUID player) {
        return selecting.contains(player);
    }

    public void clearSelecting(UUID player) {
        selecting.remove(player);
        activePot.remove(player);
    }

    /** The pot new marks and regions go into for this player (default bucket if unset). */
    public String activePot(UUID player) {
        return activePot.getOrDefault(player, PotRegistry.DEFAULT_POT);
    }

    public void setActivePot(UUID player, String name) {
        activePot.put(player, name);
    }

    /** @return the new state: true if selection mode is now on */
    public boolean toggleSelecting(UUID player) {
        if (selecting.add(player)) {
            return true;
        }
        selecting.remove(player);
        return false;
    }

    /**
     * Sends an alert to Discord through DiscordSRV when it is installed and
     * connected. {@link DiscordNotifier} is referenced only past the presence
     * check, so a server without DiscordSRV never loads that class.
     */
    public void notifyDiscord(String message) {
        if (!discordSrvPresent) {
            return;
        }
        try {
            if (DiscordNotifier.ready()) {
                DiscordNotifier.send(settings.discordChannel(), message);
            }
        } catch (Throwable t) {
            // A Discord hiccup must never interfere with the punishment itself.
            getLogger().log(Level.WARNING, "Could not send honeypot alert to Discord", t);
        }
    }

    /** Logs to the console and, when enabled, appends to logs/honeypot.log. */
    public void logEvent(String message) {
        getLogger().info(message);
        appendLogFile(message);
    }

    /** Like {@link #logEvent}, but red in the console so catches stand out. */
    public void logAlert(String message) {
        getComponentLogger().info(Component.text(message, NamedTextColor.RED));
        appendLogFile(message);
    }

    private void appendLogFile(String message) {
        if (settings == null || !settings.logToFile()) {
            return;
        }
        // Relative to the server working directory — the same logs/ directory
        // as Paper's own latest.log, where the original plugin also wrote.
        Path logFile = Path.of("logs", "honeypot.log");
        String line = "[" + LocalDateTime.now().format(LOG_STAMP) + "] " + message + "\n";
        try {
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write honeypot.log", e);
        }
    }
}
