package io.github.phateio.honeypot;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.util.DiscordUtil;

/**
 * DiscordSRV integration, kept in its own class so it is loaded only when
 * DiscordSRV is actually installed. {@link Honeypot#notifyDiscord} guards every
 * call behind a plugin-presence check, so a server without DiscordSRV never
 * links against these classes (no {@code NoClassDefFoundError}).
 */
final class DiscordNotifier {

    private DiscordNotifier() {
    }

    /** Whether DiscordSRV has finished connecting to Discord. */
    static boolean ready() {
        return DiscordSRV.isReady;
    }

    /**
     * Sends a plain message to the DiscordSRV game channel named {@code channel}
     * (a key under DiscordSRV's {@code Channels:}, not a raw channel ID). No-op
     * when that channel is not linked. Sending is asynchronous.
     */
    static void send(String channel, String message) {
        TextChannel target = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(channel);
        if (target != null) {
            DiscordUtil.sendMessage(target, message);
        }
    }
}
