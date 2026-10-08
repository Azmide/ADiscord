package com.azmide.adiscord.command;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.link.LinkManager;
import com.azmide.adiscord.link.LinkedAccount;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** {@code /discordadmin} for looking up, linking, unlinking and syncing any player. */
public final class DiscordAdminCommand {

    private final ADiscordPlugin plugin;

    public DiscordAdminCommand(ADiscordPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        SuggestionProvider<CommandSourceStack> onlinePlayers = (context, builder) -> {
            String input = builder.getRemainingLowerCase();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                    builder.suggest(player.getName());
                }
            }
            return builder.buildFuture();
        };

        return Commands.literal("discordadmin")
                .requires(source -> source.getSender().hasPermission("adiscord.command.admin"))
                .executes(context -> {
                    plugin.messages().send(context.getSource().getSender(), "admin.usage");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("lookup")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(onlinePlayers)
                                .executes(this::lookup)))
                .then(Commands.literal("link")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(onlinePlayers)
                                .then(Commands.argument("discord", LongArgumentType.longArg(1))
                                        .executes(this::link))))
                .then(Commands.literal("unlink")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(onlinePlayers)
                                .executes(this::unlink)))
                .then(Commands.literal("sync")
                        .executes(this::syncAll)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(onlinePlayers)
                                .executes(this::syncOne)))
                .build();
    }

    private int lookup(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String target = StringArgumentType.getString(context, "target");

        // Discord IDs are long numbers, anything else is treated as a player name
        CompletableFuture<Optional<LinkedAccount>> search = target.length() >= 15 && target.chars().allMatch(Character::isDigit)
                ? plugin.links().repository().findByDiscord(Long.parseLong(target))
                : plugin.links().repository().findByName(target);

        search.whenComplete((found, error) -> {
            if (failed(sender, error, "looking up " + target)) {
                return;
            }
            if (found.isEmpty()) {
                plugin.messages().send(sender, "admin.not-linked", Placeholder.unparsed("target", target));
                return;
            }
            LinkedAccount account = found.get();
            plugin.bot().userName(account.discordId()).thenAccept(name -> plugin.messages().send(sender, "admin.lookup",
                    Placeholder.unparsed("player", account.name()),
                    Placeholder.unparsed("uuid", account.uuid().toString()),
                    Placeholder.unparsed("discord", name),
                    Placeholder.unparsed("discord_id", String.valueOf(account.discordId())),
                    Placeholder.unparsed("date", plugin.messages().date(account.linkedAt()))));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int link(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "player");
        long discordId = LongArgumentType.getLong(context, "discord");

        OfflinePlayer player = plugin.getServer().getPlayerExact(name);
        if (player == null) {
            player = plugin.getServer().getOfflinePlayerIfCached(name);
        }
        if (player == null || player.getName() == null) {
            plugin.messages().send(sender, "admin.unknown-player", Placeholder.unparsed("target", name));
            return Command.SINGLE_SUCCESS;
        }

        LinkedAccount account = new LinkedAccount(player.getUniqueId(), player.getName(), discordId, Instant.now());
        plugin.links().link(account).whenComplete((outcome, error) -> {
            if (failed(sender, error, "linking " + account.name())) {
                return;
            }
            if (outcome.status() == LinkManager.Status.LINKED) {
                plugin.messages().send(sender, "admin.linked",
                        Placeholder.unparsed("player", account.name()),
                        Placeholder.unparsed("discord_id", String.valueOf(discordId)));
            } else {
                plugin.messages().send(sender, "admin.link-failed");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int unlink(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "player");

        plugin.links().repository().findByName(name)
                .thenCompose(found -> found.isPresent()
                        ? plugin.links().unlink(found.get().uuid())
                        : CompletableFuture.completedFuture(Optional.<LinkedAccount>empty()))
                .whenComplete((removed, error) -> {
                    if (failed(sender, error, "unlinking " + name)) {
                        return;
                    }
                    if (removed.isPresent()) {
                        plugin.messages().send(sender, "admin.unlinked", Placeholder.unparsed("player", removed.get().name()));
                    } else {
                        plugin.messages().send(sender, "admin.not-linked", Placeholder.unparsed("target", name));
                    }
                });
        return Command.SINGLE_SUCCESS;
    }

    private int syncAll(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!botReady(sender)) {
            return Command.SINGLE_SUCCESS;
        }
        plugin.roleSync().syncAll().whenComplete((count, error) -> {
            if (!failed(sender, error, "syncing roles")) {
                plugin.messages().send(sender, "admin.synced", Placeholder.unparsed("count", String.valueOf(count)));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int syncOne(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "player");
        if (!botReady(sender)) {
            return Command.SINGLE_SUCCESS;
        }
        plugin.links().repository().findByName(name).whenComplete((found, error) -> {
            if (failed(sender, error, "syncing " + name)) {
                return;
            }
            if (found.isEmpty()) {
                plugin.messages().send(sender, "admin.not-linked", Placeholder.unparsed("target", name));
                return;
            }
            plugin.roleSync().sync(found.get());
            plugin.messages().send(sender, "admin.synced", Placeholder.unparsed("count", "1"));
        });
        return Command.SINGLE_SUCCESS;
    }

    private boolean botReady(CommandSender sender) {
        if (plugin.bot().isReady()) {
            return true;
        }
        plugin.messages().send(sender, "error.bot-offline");
        return false;
    }

    private boolean failed(CommandSender sender, Throwable error, String action) {
        if (error == null) {
            return false;
        }
        plugin.logError(action, error);
        plugin.messages().send(sender, "error.generic");
        return true;
    }
}
