package com.azmide.adiscord.discord;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.DiscordMessages;
import com.azmide.adiscord.hook.LuckPermsHook;
import com.azmide.adiscord.image.Heads;
import com.azmide.adiscord.image.StyledText;
import com.azmide.adiscord.image.TabListImage;
import com.azmide.adiscord.util.Placeholders;
import com.azmide.adiscord.util.Players;
import com.azmide.adiscord.util.Text;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** {@code /playerlist} on Discord, as a picture of the tab list or as plain text. */
public final class PlayerListListener extends ListenerAdapter {

    private static final String IMAGE_FILE = "playerlist.png";

    /** What is needed from a player to draw them, read on the main thread. */
    private record Online(String name, UUID uuid, Component displayName, int ping, @Nullable String skin) {
    }

    private final ADiscordPlugin plugin;
    private final Heads heads = new Heads();

    PlayerListListener(ADiscordPlugin plugin) {
        this.plugin = plugin;
    }

    SlashCommandData command() {
        DiscordMessages texts = plugin.discordMessages();
        return Commands.slash(texts.string("commands.playerlist.name"), texts.string("commands.playerlist.description"))
                .setContexts(InteractionContextType.GUILD);
    }

    void close() {
        heads.close();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        DiscordMessages texts = plugin.discordMessages();
        if (!event.getName().equals(texts.string("commands.playerlist.name"))) {
            return;
        }
        event.deferReply(texts.bool("playerlist.ephemeral")).queue();
        InteractionHook hook = event.getHook();

        // Player data is read on the main thread, the picture is drawn off it
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<Online> players = plugin.getServer().getOnlinePlayers().stream()
                    .filter(player -> !Players.isVanished(player))
                    .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(this::snapshot)
                    .toList();
            int max = plugin.getServer().getMaxPlayers();

            if (players.isEmpty() || !texts.bool("playerlist.image.enabled")) {
                hook.editOriginalEmbeds(textList(players, max)).queue();
                return;
            }
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> sendImage(hook, players, max));
        });
    }

    private void sendImage(InteractionHook hook, List<Online> players, int max) {
        DiscordMessages texts = plugin.discordMessages();
        List<CompletableFuture<@Nullable BufferedImage>> faces = players.stream()
                .map(player -> heads.get(player.skin(), texts.avatar(player.name(), player.uuid())))
                .toList();

        // Players whose head takes too long are drawn without one
        CompletableFuture.allOf(faces.toArray(CompletableFuture[]::new))
                .completeOnTimeout(null, 6, TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> {
                    try {
                        List<TabListImage.Entry> entries = new ArrayList<>();
                        for (int i = 0; i < players.size(); i++) {
                            Online player = players.get(i);
                            entries.add(new TabListImage.Entry(
                                    StyledText.of(player.displayName(), NamedTextColor.WHITE),
                                    player.ping(),
                                    faces.get(i).getNow(null)));
                        }
                        byte[] image = TabListImage.render(lines("header", players.size(), max), entries,
                                lines("footer", players.size(), max));

                        MessageEmbed embed = texts.embed("playerlist.image.embed").build(
                                counts(players.size(), max).with("image", "attachment://" + IMAGE_FILE));
                        hook.editOriginalEmbeds(embed).setFiles(List.of(FileUpload.fromData(image, IMAGE_FILE))).queue();
                    } catch (IOException | RuntimeException | LinkageError | InternalError e) {
                        plugin.getLogger().log(Level.WARNING, "Could not draw the player list, sending it as text", e);
                        hook.editOriginalEmbeds(textList(players, max)).queue();
                    }
                });
    }

    private Online snapshot(Player player) {
        LuckPermsHook luckPerms = plugin.luckPerms();
        String prefix = luckPerms != null ? luckPerms.prefix(player).strip() : "";
        String suffix = luckPerms != null ? luckPerms.suffix(player).strip() : "";

        Component displayName = MiniMessage.miniMessage().deserialize(
                plugin.discordMessages().string("playerlist.image.name"),
                Placeholder.component("prefix", prefix.isEmpty() ? Component.empty() : Text.colored(prefix + " ")),
                Placeholder.component("suffix", suffix.isEmpty() ? Component.empty() : Text.colored(" " + suffix)),
                Placeholder.unparsed("name", player.getName()));

        URL skin = player.getPlayerProfile().getTextures().getSkin();
        return new Online(player.getName(), player.getUniqueId(), displayName, player.getPing(),
                skin != null ? skin.toString() : null);
    }

    private List<StyledText> lines(String key, int online, int max) {
        return plugin.discordMessages().list("playerlist.image." + key).stream()
                .map(line -> MiniMessage.miniMessage().deserialize(line,
                        Placeholder.unparsed("online", String.valueOf(online)),
                        Placeholder.unparsed("max", String.valueOf(max))))
                .map(line -> StyledText.of(line, NamedTextColor.WHITE))
                .toList();
    }

    private MessageEmbed textList(List<Online> players, int max) {
        DiscordMessages texts = plugin.discordMessages();
        String format = texts.string("playerlist.player");
        String names = players.isEmpty()
                ? texts.string("playerlist.empty")
                : players.stream()
                .map(player -> Placeholders.of("player", player.name()).apply(format))
                .collect(Collectors.joining(texts.string("playerlist.separator")));
        return texts.embed("playerlist.embed").build(counts(players.size(), max).with("players", names));
    }

    private static Placeholders counts(int online, int max) {
        return Placeholders.of("online", online).with("max", max);
    }
}
