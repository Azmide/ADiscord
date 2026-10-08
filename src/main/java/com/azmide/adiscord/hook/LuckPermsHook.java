package com.azmide.adiscord.hook;

import com.azmide.adiscord.sync.GroupMode;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Everything that touches the LuckPerms API lives here, so the plugin still loads
 * when LuckPerms is not installed.
 */
public final class LuckPermsHook {

    private final LuckPerms luckPerms = LuckPermsProvider.get();
    private @Nullable EventSubscription<NodeMutateEvent> subscription;

    /** Calls the listener when a player's groups change, including temporary groups running out. */
    public void onGroupChange(Plugin plugin, Consumer<UUID> listener) {
        subscription = luckPerms.getEventBus().subscribe(plugin, NodeMutateEvent.class, event -> {
            if (event.isUser() && changesGroups(event)) {
                listener.accept(((User) event.getTarget()).getUniqueId());
            }
        });
    }

    public CompletableFuture<Set<String>> groups(UUID uuid, GroupMode mode) {
        UserManager users = luckPerms.getUserManager();
        User loaded = users.getUser(uuid);
        CompletableFuture<User> user = loaded != null ? CompletableFuture.completedFuture(loaded) : users.loadUser(uuid);
        return user.thenApply(value -> groups(value, mode));
    }

    /** The raw prefix, still with its color codes, or an empty string. */
    public String prefix(Player player) {
        return Objects.requireNonNullElse(meta(player).getPrefix(), "");
    }

    public String suffix(Player player) {
        return Objects.requireNonNullElse(meta(player).getSuffix(), "");
    }

    public void close() {
        if (subscription != null) {
            subscription.close();
        }
    }

    private CachedMetaData meta(Player player) {
        return luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
    }

    private static Set<String> groups(User user, GroupMode mode) {
        return switch (mode) {
            case PRIMARY -> Set.of(user.getPrimaryGroup());
            case DIRECT -> user.getNodes(NodeType.INHERITANCE).stream()
                    .filter(node -> !node.hasExpired())
                    .map(InheritanceNode::getGroupName)
                    .collect(Collectors.toSet());
            case ALL -> user.getInheritedGroups(user.getQueryOptions()).stream()
                    .map(Group::getName)
                    .collect(Collectors.toSet());
        };
    }

    private static boolean changesGroups(NodeMutateEvent event) {
        if (event instanceof NodeAddEvent add) {
            return add.getNode() instanceof InheritanceNode;
        }
        if (event instanceof NodeRemoveEvent remove) {
            return remove.getNode() instanceof InheritanceNode;
        }
        return true;
    }
}
