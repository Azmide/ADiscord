package com.azmide.adiscord.discord;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.DiscordMessages;
import com.azmide.adiscord.util.Placeholders;
import com.azmide.adiscord.util.Players;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/** {@code /playerlist} on Discord. */
public final class PlayerListListener extends ListenerAdapter {

    private final ADiscordPlugin plugin;

    PlayerListListener(ADiscordPlugin plugin) {
        this.plugin = plugin;
    }

    SlashCommandData command() {
        DiscordMessages texts = plugin.discordMessages();
        return Commands.slash(texts.string("commands.playerlist.name"), texts.string("commands.playerlist.description"))
                .setContexts(InteractionContextType.GUILD);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        DiscordMessages texts = plugin.discordMessages();
        if (!event.getName().equals(texts.string("commands.playerlist.name"))) {
            return;
        }

        // Player data is read on the main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<String> names = plugin.getServer().getOnlinePlayers().stream()
                    .filter(player -> !Players.isVanished(player))
                    .map(Player::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();

            String format = texts.string("playerlist.player");
            String players = names.isEmpty()
                    ? texts.string("playerlist.empty")
                    : names.stream()
                    .map(name -> Placeholders.of("player", name).apply(format))
                    .collect(Collectors.joining(texts.string("playerlist.separator")));

            Placeholders placeholders = Placeholders.of("online", names.size())
                    .with("max", plugin.getServer().getMaxPlayers())
                    .with("players", players);
            event.replyEmbeds(texts.embed("playerlist.embed").build(placeholders))
                    .setEphemeral(texts.bool("playerlist.ephemeral"))
                    .queue();
        });
    }
}
