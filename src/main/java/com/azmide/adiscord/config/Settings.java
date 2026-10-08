package com.azmide.adiscord.config;

import com.azmide.adiscord.sync.GroupMode;
import net.dv8tion.jda.api.entities.Activity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Typed view over config.yml. */
public record Settings(
        Bot bot,
        Storage storage,
        Linking linking,
        Nickname nickname,
        Roles roles,
        Chat chat,
        Events events) {

    public record Bot(String token, long guildId, boolean activityEnabled,
                      Activity.ActivityType activityType, String activityText, int activityInterval) {

        public boolean hasToken() {
            return !token.isBlank() && !token.startsWith("PASTE-");
        }
    }

    public record Storage(Type type, String tablePrefix, String host, int port, String database,
                          String username, String password, int poolSize, Map<String, String> properties) {

        public enum Type {
            SQLITE, MYSQL
        }
    }

    public record Linking(String codeCharacters, int codeLength, Duration codeExpiry, long verifyChannelId,
                          long verifiedRoleId, boolean verifyPanel, boolean unlinkOnLeave, List<String> rewards) {
    }

    public record Nickname(boolean enabled, String format) {
    }

    public record Roles(boolean enabled, GroupMode mode, int interval, Map<String, Long> groups) {
    }

    public record Chat(boolean enabled, long channelId, boolean toDiscord, boolean toMinecraft,
                       boolean webhook, int maxLength, Showcase showcase) {
    }

    /** Keywords for [item], [inv] and [ender]. A keyword is null when it is turned off. */
    public record Showcase(boolean enabled, @Nullable Pattern item, @Nullable Pattern inventory,
                           @Nullable Pattern enderChest) {
    }

    public record Events(long channelId, boolean join, boolean quit, boolean death, boolean advancement,
                         boolean serverStart, boolean serverStop) {
    }

    public static Settings load(ConfigurationSection config, Logger logger) {
        ConfigurationSection mysql = section(config, "database.mysql");
        Map<String, String> properties = new LinkedHashMap<>();
        ConfigurationSection rawProperties = mysql.getConfigurationSection("properties");
        if (rawProperties != null) {
            for (String key : rawProperties.getKeys(false)) {
                properties.put(key, String.valueOf(rawProperties.get(key)));
            }
        }

        Map<String, Long> groups = new LinkedHashMap<>();
        ConfigurationSection rawGroups = config.getConfigurationSection("role-sync.groups");
        if (rawGroups != null) {
            for (String group : rawGroups.getKeys(false)) {
                long roleId = id(rawGroups.getString(group));
                if (roleId != 0) {
                    groups.put(group.toLowerCase(Locale.ROOT), roleId);
                }
            }
        }

        String characters = config.getString("linking.code-characters", "");
        if (characters.isBlank()) {
            characters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        }

        return new Settings(
                new Bot(
                        config.getString("bot.token", "").trim(),
                        id(config.getString("bot.guild-id")),
                        config.getBoolean("bot.activity.enabled"),
                        enumValue(config.getString("bot.activity.type"), Activity.ActivityType.PLAYING, logger),
                        config.getString("bot.activity.text", ""),
                        Math.max(30, config.getInt("bot.activity.interval", 60))),
                new Storage(
                        enumValue(config.getString("database.type"), Storage.Type.SQLITE, logger),
                        config.getString("database.table-prefix", "adiscord_"),
                        mysql.getString("host", "localhost"),
                        mysql.getInt("port", 3306),
                        mysql.getString("database", "minecraft"),
                        mysql.getString("username", "root"),
                        mysql.getString("password", ""),
                        Math.max(1, mysql.getInt("pool-size", 6)),
                        properties),
                new Linking(
                        characters.toUpperCase(Locale.ROOT),
                        Math.clamp(config.getInt("linking.code-length", 6), 4, 16),
                        Duration.ofSeconds(Math.max(30, config.getInt("linking.code-expiry", 300))),
                        id(config.getString("linking.verify-channel-id")),
                        id(config.getString("linking.verified-role-id")),
                        config.getBoolean("linking.verify-panel"),
                        config.getBoolean("linking.unlink-on-leave"),
                        config.getStringList("linking.rewards")),
                new Nickname(
                        config.getBoolean("nickname.enabled"),
                        config.getString("nickname.format", "{player}")),
                new Roles(
                        config.getBoolean("role-sync.enabled"),
                        enumValue(config.getString("role-sync.mode"), GroupMode.DIRECT, logger),
                        Math.max(0, config.getInt("role-sync.interval", 30)),
                        Map.copyOf(groups)),
                new Chat(
                        config.getBoolean("chat.enabled"),
                        id(config.getString("chat.channel-id")),
                        config.getBoolean("chat.minecraft-to-discord"),
                        config.getBoolean("chat.discord-to-minecraft"),
                        config.getBoolean("chat.webhook"),
                        Math.max(16, config.getInt("chat.max-length", 256)),
                        new Showcase(
                                config.getBoolean("chat.showcase.enabled"),
                                pattern(config.getString("chat.showcase.item"), logger),
                                pattern(config.getString("chat.showcase.inventory"), logger),
                                pattern(config.getString("chat.showcase.ender-chest"), logger))),
                new Events(
                        id(config.getString("events.channel-id")),
                        config.getBoolean("events.join"),
                        config.getBoolean("events.quit"),
                        config.getBoolean("events.death"),
                        config.getBoolean("events.advancement"),
                        config.getBoolean("events.server-start"),
                        config.getBoolean("events.server-stop")));
    }

    /** Channel used for join, quit and other server events. */
    public long eventChannelId() {
        return events.channelId() != 0 ? events.channelId() : chat.channelId();
    }

    private static ConfigurationSection section(ConfigurationSection config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section != null ? section : new MemoryConfiguration();
    }

    private static @Nullable Pattern pattern(String value, Logger logger) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            logger.warning("Invalid showcase keyword '" + value + "' in config.yml: " + e.getDescription());
            return null;
        }
    }

    private static long id(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static <E extends Enum<E>> E enumValue(String value, E fallback, Logger logger) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown value '" + value + "' in config.yml, using " + fallback.name() + ".");
            return fallback;
        }
    }
}
