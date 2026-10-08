package com.azmide.adiscord.sync;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.hook.LuckPermsHook;
import com.azmide.adiscord.link.LinkedAccount;
import com.azmide.adiscord.util.Placeholders;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Discord roles and nickname of linked members in line with their account in game.
 * Only roles listed in config.yml are touched, anything else a member has is left alone.
 */
public final class RoleSync {

    private final ADiscordPlugin plugin;
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();

    public RoleSync(ADiscordPlugin plugin) {
        this.plugin = plugin;
        LuckPermsHook luckPerms = plugin.luckPerms();
        if (luckPerms != null) {
            luckPerms.onGroupChange(plugin, this::queue);
        }
    }

    /** Syncs the player a second later, so a burst of rank changes ends up as a single update. */
    public void queue(UUID uuid) {
        if (!queued.add(uuid)) {
            return;
        }
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> {
            queued.remove(uuid);
            plugin.links().repository().find(uuid)
                    .thenAccept(account -> account.ifPresent(this::sync))
                    .exceptionally(error -> plugin.logError("syncing roles", error));
        }, 1, TimeUnit.SECONDS);
    }

    public CompletableFuture<Void> sync(LinkedAccount account) {
        Guild guild = plugin.bot().guild();
        if (guild == null) {
            return CompletableFuture.completedFuture(null);
        }
        return groups(account.uuid())
                .thenCompose(groups -> guild.retrieveMemberById(account.discordId()).submit()
                        .thenAccept(member -> {
                            updateRoles(guild, member, groups, true);
                            updateNickname(guild, member, account.name());
                        }))
                .exceptionally(error -> {
                    if (!isUnknownMember(error)) {
                        plugin.logError("syncing roles for " + account.name(), error);
                    }
                    return null;
                });
    }

    /** Syncs every linked account and returns how many there are. */
    public CompletableFuture<Integer> syncAll() {
        return plugin.links().repository().findAll().thenApply(accounts -> {
            accounts.forEach(this::sync);
            return accounts.size();
        });
    }

    /** Removes every synced role and the nickname, used after unlinking. */
    public void clear(LinkedAccount account) {
        Guild guild = plugin.bot().guild();
        if (guild == null) {
            return;
        }
        guild.retrieveMemberById(account.discordId()).queue(member -> {
            updateRoles(guild, member, Set.of(), false);
            Settings.Nickname nickname = plugin.settings().nickname();
            if (nickname.enabled() && member.getNickname() != null && canRename(guild, member)) {
                guild.modifyNickname(member, null).queue(null, error -> warn("nickname", error.getMessage()));
            }
        }, error -> {
            if (!isUnknownMember(error)) {
                plugin.logError("removing roles from " + account.name(), error);
            }
        });
    }

    private CompletableFuture<Set<String>> groups(UUID uuid) {
        Settings.Roles roles = plugin.settings().roles();
        LuckPermsHook luckPerms = plugin.luckPerms();
        if (!roles.enabled() || roles.groups().isEmpty() || luckPerms == null) {
            return CompletableFuture.completedFuture(Set.of());
        }
        return luckPerms.groups(uuid, roles.mode());
    }

    private void updateRoles(Guild guild, Member member, Set<String> groups, boolean linked) {
        Set<Role> managed = new HashSet<>();
        Set<Role> wanted = new HashSet<>();

        Role verified = guild.getRoleById(plugin.settings().linking().verifiedRoleId());
        if (verified != null) {
            managed.add(verified);
            if (linked) {
                wanted.add(verified);
            }
        }

        Settings.Roles roles = plugin.settings().roles();
        if (roles.enabled()) {
            roles.groups().forEach((group, roleId) -> {
                Role role = guild.getRoleById(roleId);
                if (role == null) {
                    warn("role:" + roleId, "Role " + roleId + " for group '" + group + "' does not exist on Discord.");
                    return;
                }
                managed.add(role);
                if (linked && groups.contains(group)) {
                    wanted.add(role);
                }
            });
        }

        List<Role> current = member.getRoles();
        List<Role> add = new ArrayList<>();
        List<Role> remove = new ArrayList<>();
        for (Role role : managed) {
            boolean has = current.contains(role);
            boolean wants = wanted.contains(role);
            if (has == wants) {
                continue;
            }
            if (!guild.getSelfMember().canInteract(role)) {
                warn("hierarchy:" + role.getId(), "Can not manage the role '" + role.getName()
                        + "', move the bot's role above it in your Discord server settings.");
                continue;
            }
            (wants ? add : remove).add(role);
        }

        if (add.isEmpty() && remove.isEmpty()) {
            return;
        }
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            warn("manage-roles", "The bot needs the Manage Roles permission to sync roles.");
            return;
        }
        guild.modifyMemberRoles(member, add, remove)
                .reason("ADiscord role sync")
                .queue(null, error -> warn("roles", "Could not update roles: " + error.getMessage()));
    }

    private void updateNickname(Guild guild, Member member, String player) {
        Settings.Nickname nickname = plugin.settings().nickname();
        if (!nickname.enabled() || !canRename(guild, member)) {
            return;
        }
        String name = Placeholders.of("player", player).apply(nickname.format()).strip();
        if (name.length() > Member.MAX_NICKNAME_LENGTH) {
            name = name.substring(0, Member.MAX_NICKNAME_LENGTH);
        }
        if (!name.isEmpty() && !name.equals(member.getNickname())) {
            guild.modifyNickname(member, name).queue(null, error -> warn("nickname", error.getMessage()));
        }
    }

    private boolean canRename(Guild guild, Member member) {
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.NICKNAME_MANAGE)) {
            warn("manage-nicknames", "The bot needs the Manage Nicknames permission to sync nicknames.");
            return false;
        }
        // The owner and members above the bot can not be renamed, which is expected
        return self.canInteract(member);
    }

    /** Logs a problem once instead of on every sync. Cleared when the plugin is reloaded. */
    private void warn(String key, String message) {
        if (warnings.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    public void resetWarnings() {
        warnings.clear();
    }

    private static boolean isUnknownMember(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof ErrorResponseException response
                && (response.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER
                || response.getErrorResponse() == ErrorResponse.UNKNOWN_USER);
    }
}
