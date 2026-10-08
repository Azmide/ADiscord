package com.azmide.adiscord;

import com.azmide.adiscord.command.DiscordAdminCommand;
import com.azmide.adiscord.command.DiscordCommand;
import com.azmide.adiscord.config.DiscordMessages;
import com.azmide.adiscord.config.Messages;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.discord.DiscordBot;
import com.azmide.adiscord.link.LinkManager;
import com.azmide.adiscord.listener.PlayerListener;
import com.azmide.adiscord.storage.Database;
import com.azmide.adiscord.storage.LinkRepository;
import com.azmide.adiscord.sync.RoleSync;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class ADiscordPlugin extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private DiscordMessages discordMessages;

    private Database database;
    private LinkManager links;
    private RoleSync roleSync;
    private DiscordBot bot;
    private @Nullable ScheduledTask syncTask;

    @Override
    public void onEnable() {
        messages = new Messages(getLogger());
        discordMessages = new DiscordMessages();
        loadFiles();

        try {
            database = new Database(getDataFolder(), settings.storage());
            database.createTables();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not connect to the database, check the database section in config.yml", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        links = new LinkManager(this, new LinkRepository(database));
        roleSync = new RoleSync(this);
        bot = new DiscordBot(this);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(new DiscordCommand(this).build(), "Link your Minecraft account to Discord");
            event.registrar().register(new DiscordAdminCommand(this).build(), "Manage linked Discord accounts");
        });

        bot.start();
        scheduleSync();
    }

    @Override
    public void onDisable() {
        if (syncTask != null) {
            syncTask.cancel();
        }
        if (bot != null) {
            bot.shutdown();
        }
        if (roleSync != null) {
            roleSync.close();
        }
        if (database != null) {
            database.close();
        }
    }

    /** Reloads the three config files. The bot token and database settings need a restart. */
    public void reload() {
        loadFiles();
        bot.refresh();
        scheduleSync();
    }

    /** Logs a failed async task. Returns null so it can be used directly in {@code exceptionally}. */
    public <T> @Nullable T logError(String action, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        getLogger().log(Level.WARNING, "Something went wrong while " + action, cause);
        return null;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public DiscordMessages discordMessages() {
        return discordMessages;
    }

    public LinkManager links() {
        return links;
    }

    public RoleSync roleSync() {
        return roleSync;
    }

    public DiscordBot bot() {
        return bot;
    }

    private void loadFiles() {
        saveDefaultConfig();
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages.load(loadYaml("messages.yml"));
        discordMessages.load(loadYaml("discord.yml"));
    }

    /** Loads a file from the data folder, falling back to the bundled copy for missing keys. */
    private YamlConfiguration loadYaml(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream defaults = getResource(name)) {
            if (defaults != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not read the default " + name, e);
        }
        return yaml;
    }

    /** Full sync of every linked account, picks up changes made while players were offline. */
    private void scheduleSync() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        int interval = settings.roles().interval();
        if (interval <= 0) {
            return;
        }
        syncTask = getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            if (bot.isReady()) {
                roleSync.syncAll().exceptionally(error -> logError("running the scheduled role sync", error));
            }
        }, interval, interval, TimeUnit.MINUTES);
    }
}
