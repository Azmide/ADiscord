package com.azmide.adiscord.listener;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.util.Placeholders;
import com.azmide.adiscord.util.Players;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

/** Passes chat and player events on to Discord, and syncs roles when a linked player joins. */
public final class PlayerListener implements Listener {

    private final ADiscordPlugin plugin;

    public PlayerListener(ADiscordPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        plugin.bot().chat().toDiscord(event.getPlayer(), plain(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.settings().events().join() && !Players.isVanished(player)) {
            plugin.bot().sendEvent("events.join", placeholders(player));
        }

        // Catches rank changes and expired ranks from while the player was offline
        plugin.links().repository().find(player.getUniqueId()).thenAccept(found -> found.ifPresent(account -> {
            if (!account.name().equals(player.getName())) {
                plugin.links().repository().updateName(account.uuid(), player.getName())
                        .exceptionally(error -> plugin.logError("saving the new name of " + player.getName(), error));
            }
            plugin.roleSync().sync(account.withName(player.getName()));
        })).exceptionally(error -> plugin.logError("loading the link of " + player.getName(), error));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.settings().events().quit() && !Players.isVanished(player)) {
            plugin.bot().sendEvent("events.quit", placeholders(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Component message = event.deathMessage();
        if (!plugin.settings().events().death() || message == null || Players.isVanished(player)) {
            return;
        }
        plugin.bot().sendEvent("events.death", placeholders(player).with("message", plain(message)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Settings.Events events = plugin.settings().events();
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        // No chat message means the advancement is hidden or announcements are turned off
        if (!events.advancement() || display == null || event.message() == null || Players.isVanished(event.getPlayer())) {
            return;
        }
        String frame = display.frame().name().toLowerCase(Locale.ROOT);
        plugin.bot().sendEvent("events.advancement", placeholders(event.getPlayer())
                .with("advancement", plain(display.title()))
                .with("action", plugin.discordMessages().string("events.advancement-actions." + frame)));
    }

    private Placeholders placeholders(Player player) {
        return plugin.discordMessages().player(player.getName(), player.getUniqueId());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
