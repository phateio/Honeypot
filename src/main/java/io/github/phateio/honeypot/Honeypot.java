package io.github.phateio.honeypot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Honeypot extends JavaPlugin {

    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Logout sweep interval: 1200 ticks = 60 seconds. */
    private static final long SWEEP_PERIOD_TICKS = 1200;

    private final Set<UUID> selecting = new HashSet<>();
    private HoneypotConfig settings;
    private PotRegistry registry;
    private OffenseTracker tracker;
    private Punisher punisher;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = HoneypotConfig.load(this);
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
        if (registry != null) {
            registry.save();
        }
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
    }

    /** @return the new state: true if selection mode is now on */
    public boolean toggleSelecting(UUID player) {
        if (selecting.add(player)) {
            return true;
        }
        selecting.remove(player);
        return false;
    }

    /** Logs to the console and, when enabled, appends to honeypot.log. */
    public void logEvent(String message) {
        getLogger().info(message);
        if (settings == null || !settings.logToFile()) {
            return;
        }
        Path logFile = getDataFolder().toPath().resolve("honeypot.log");
        String line = "[" + LocalDateTime.now().format(LOG_STAMP) + "] " + message + "\n";
        try {
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not write honeypot.log", e);
        }
    }
}
