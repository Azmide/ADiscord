package com.azmide.adiscord.command;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.Messages;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** {@code /discord} for players: link, unlink, info and reload. */
public final class DiscordCommand {

    private final ADiscordPlugin plugin;

    public DiscordCommand(ADiscordPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        Predicate<CommandSourceStack> canLink = source -> source.getSender() instanceof Player player
                && player.hasPermission("adiscord.command.link");

        return Commands.literal("discord")
                .executes(context -> {
                    plugin.messages().send(context.getSource().getSender(), "discord");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("link").requires(canLink).executes(context -> asPlayer(context, this::link)))
                .then(Commands.literal("unlink").requires(canLink).executes(context -> asPlayer(context, this::unlink)))
                .then(Commands.literal("info").requires(canLink).executes(context -> asPlayer(context, this::info)))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission("adiscord.command.reload"))
                        .executes(context -> {
                            plugin.reload();
                            plugin.messages().send(context.getSource().getSender(), "reload");
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void link(Player player) {
        Messages messages = plugin.messages();
        if (!plugin.bot().isReady()) {
            messages.send(player, "error.bot-offline");
            return;
        }

        plugin.links().repository().find(player.getUniqueId()).whenComplete((account, error) -> {
            if (error != null) {
                plugin.logError("looking up the link of " + player.getName(), error);
                messages.send(player, "error.generic");
                return;
            }
            if (account.isPresent()) {
                plugin.bot().userName(account.get().discordId()).thenAccept(name ->
                        messages.send(player, "link.already-linked", Placeholder.unparsed("discord", name)));
                return;
            }

            String code = plugin.links().createCode(player.getUniqueId(), player.getName());
            Component clickable = messages.parse(messages.raw("link.code-style"), Placeholder.unparsed("code", code))
                    .clickEvent(ClickEvent.copyToClipboard(code))
                    .hoverEvent(HoverEvent.showText(messages.parse(messages.raw("link.code-hover"))));

            messages.send(player, "link.code",
                    Placeholder.component("code", clickable),
                    Placeholder.unparsed("code_text", code),
                    Placeholder.unparsed("expiry", describe(plugin.settings().linking().codeExpiry())));
        });
    }

    private void unlink(Player player) {
        Messages messages = plugin.messages();
        // Without the bot the roles would stay on Discord, so wait until it is back
        if (!plugin.bot().isReady()) {
            messages.send(player, "error.bot-offline");
            return;
        }

        plugin.links().unlink(player.getUniqueId()).whenComplete((removed, error) -> {
            if (error != null) {
                plugin.logError("unlinking " + player.getName(), error);
                messages.send(player, "error.generic");
                return;
            }
            messages.send(player, removed.isPresent() ? "unlink.success" : "unlink.not-linked");
        });
    }

    private void info(Player player) {
        Messages messages = plugin.messages();
        plugin.links().repository().find(player.getUniqueId()).whenComplete((account, error) -> {
            if (error != null) {
                plugin.logError("looking up the link of " + player.getName(), error);
                messages.send(player, "error.generic");
                return;
            }
            if (account.isEmpty()) {
                messages.send(player, "info.not-linked");
                return;
            }

            long discordId = account.get().discordId();
            plugin.bot().userName(discordId).thenAccept(name -> messages.send(player, "info.linked",
                    Placeholder.unparsed("discord", name),
                    Placeholder.unparsed("discord_id", String.valueOf(discordId)),
                    Placeholder.unparsed("date", messages.date(account.get().linkedAt()))));
        });
    }

    private static int asPlayer(CommandContext<CommandSourceStack> context, Consumer<Player> action) {
        if (context.getSource().getSender() instanceof Player player) {
            action.accept(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String describe(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 60 != 0) {
            return seconds + " seconds";
        }
        long minutes = seconds / 60;
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
