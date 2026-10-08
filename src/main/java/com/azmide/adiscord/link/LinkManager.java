package com.azmide.adiscord.link;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.storage.LinkRepository;
import com.azmide.adiscord.util.Placeholders;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Hands out link codes and takes care of everything that happens when accounts get linked or unlinked. */
public final class LinkManager {

    public enum Status {
        LINKED,
        INVALID_CODE,
        DISCORD_LINKED,
        PLAYER_LINKED
    }

    /** The account is the new link, or the existing one that got in the way. */
    public record Outcome(Status status, @Nullable LinkedAccount account) {
    }

    private record PendingCode(UUID uuid, String name, Instant expiresAt) {

        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final ADiscordPlugin plugin;
    private final LinkRepository repository;
    private final Map<String, PendingCode> codes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public LinkManager(ADiscordPlugin plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public LinkRepository repository() {
        return repository;
    }

    /** Creates a fresh code for the player. Any older code they had stops working. */
    public String createCode(UUID uuid, String name) {
        codes.values().removeIf(pending -> pending.expired() || pending.uuid().equals(uuid));

        Settings.Linking settings = plugin.settings().linking();
        String code;
        do {
            code = randomCode(settings.codeCharacters(), settings.codeLength());
        } while (codes.containsKey(code));

        codes.put(code, new PendingCode(uuid, name, Instant.now().plus(settings.codeExpiry())));
        return code;
    }

    public CompletableFuture<Outcome> redeem(String input, long discordId) {
        String code = input.strip().toUpperCase(Locale.ROOT);
        PendingCode pending = codes.get(code);
        if (pending == null || pending.expired()) {
            if (pending != null) {
                codes.remove(code, pending);
            }
            return CompletableFuture.completedFuture(new Outcome(Status.INVALID_CODE, null));
        }

        return repository.findByDiscord(discordId).thenCompose(existing -> {
            if (existing.isPresent()) {
                return CompletableFuture.completedFuture(new Outcome(Status.DISCORD_LINKED, existing.get()));
            }
            // Someone else may have used the same code in the meantime
            if (!codes.remove(code, pending)) {
                return CompletableFuture.completedFuture(new Outcome(Status.INVALID_CODE, null));
            }
            return link(new LinkedAccount(pending.uuid(), pending.name(), discordId, Instant.now()));
        });
    }

    /** Saves a link directly. Used by redeemed codes and by admins. */
    public CompletableFuture<Outcome> link(LinkedAccount account) {
        return repository.insert(account).thenApply(saved -> {
            if (!saved) {
                return new Outcome(Status.PLAYER_LINKED, account);
            }
            onLinked(account);
            return new Outcome(Status.LINKED, account);
        });
    }

    /** Removes the link and takes the synced roles and nickname away on Discord. */
    public CompletableFuture<Optional<LinkedAccount>> unlink(UUID uuid) {
        return repository.remove(uuid).thenApply(removed -> {
            removed.ifPresent(account -> {
                plugin.roleSync().clear(account);
                plugin.getLogger().info(account.name() + " unlinked Discord account " + account.discordId() + ".");
            });
            return removed;
        });
    }

    private void onLinked(LinkedAccount account) {
        plugin.getLogger().info(account.name() + " linked Discord account " + account.discordId() + ".");
        plugin.roleSync().sync(account);

        plugin.bot().userName(account.discordId()).thenAccept(discordName -> {
            Player player = plugin.getServer().getPlayer(account.uuid());
            if (player != null) {
                plugin.messages().send(player, "link.success", Placeholder.unparsed("discord", discordName));
            }
            giveRewards(account, discordName);
        });
    }

    private void giveRewards(LinkedAccount account, String discordName) {
        List<String> commands = plugin.settings().linking().rewards();
        if (commands.isEmpty()) {
            return;
        }

        repository.claimReward(account.uuid(), account.discordId()).thenAccept(claimed -> {
            if (!claimed) {
                return;
            }
            Placeholders placeholders = Placeholders.of("player", account.name())
                    .with("uuid", account.uuid())
                    .with("discord_id", account.discordId())
                    .with("discord_name", discordName);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (String command : commands) {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), placeholders.apply(command));
                }
            });
        }).exceptionally(error -> plugin.logError("giving link rewards to " + account.name(), error));
    }

    private String randomCode(String characters, int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(characters.charAt(random.nextInt(characters.length())));
        }
        return code.toString();
    }
}
