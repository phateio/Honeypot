package io.github.phateio.honeypot;

import java.util.Date;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Applies the configured punishment and broadcast when a player trips a honeypot. */
public final class Punisher {

    private final Honeypot plugin;

    public Punisher(Honeypot plugin) {
        this.plugin = plugin;
    }

    public void punish(Player player) {
        HoneypotConfig config = plugin.settings();
        // Alert Discord BEFORE punishing. A ban-commands entry routed through a ban
        // alias announces itself, so punishing first would put that announcement
        // ahead of ours and read backwards; and a punishment that throws would
        // swallow the alert entirely. The send is asynchronous, so this costs the
        // punishment nothing. The in-game broadcast stays after the punishment, as
        // before, so the offender is already gone when it goes out.
        if (config.discordNotify()) {
            plugin.notifyDiscord(config.discordCaughtMessage().replace("<player>", player.getName()));
        }
        switch (config.action()) {
            case BAN -> {
                if (config.banCommands().isEmpty()) {
                    player.ban(config.banReason(), (Date) null, config.banSource(), true);
                } else {
                    dispatch(config.banCommands(), player, config.banReason());
                }
            }
            case KICK -> {
                if (config.kickCommands().isEmpty()) {
                    player.kick(Component.text(config.kickMessage()));
                } else {
                    dispatch(config.kickCommands(), player, config.kickMessage());
                }
            }
            case NONE -> { }
        }
        if (config.broadcast()) {
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(config.broadcastMessage(),
                    Placeholder.unparsed("player", player.getName())));
        }
    }

    private static void dispatch(List<String> commands, Player player, String reason) {
        for (String command : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    command.replace("%player%", player.getName()).replace("%reason%", reason));
        }
    }
}
