package com.azmide.adiscord.sync;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Everything that touches the LuckPerms API lives here, so the plugin still loads
 * when LuckPerms is not installed.
 */
final class LuckPermsHook {

    private final LuckPerms luckPerms;
    private final EventSubscription<NodeMutateEvent> subscription;

    LuckPermsHook(Plugin plugin, Consumer<UUID> onGroupChange) {
        this.luckPerms = LuckPermsProvider.get();
        // Fires for commands, other plugins and temporary groups running out
        this.subscription = luckPerms.getEventBus().subscribe(plugin, NodeMutateEvent.class, event -> {
            if (event.isUser() && changesGroups(event)) {
                onGroupChange.accept(((User) event.getTarget()).getUniqueId());
            }
        });
    }

    CompletableFuture<Set<String>> groups(UUID uuid, GroupMode mode) {
        UserManager users = luckPerms.getUserManager();
        User loaded = users.getUser(uuid);
        CompletableFuture<User> user = loaded != null ? CompletableFuture.completedFuture(loaded) : users.loadUser(uuid);
        return user.thenApply(value -> groups(value, mode));
    }

    void close() {
        subscription.close();
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
