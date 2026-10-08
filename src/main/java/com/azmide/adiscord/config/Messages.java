package com.azmide.adiscord.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Logger;

/** In-game messages from messages.yml, written in MiniMessage. */
public final class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String DEFAULT_DATE_FORMAT = "dd MMM yyyy, HH:mm";

    private final Logger logger;
    private ConfigurationSection config;
    private TagResolver prefix = TagResolver.empty();
    private DateTimeFormatter dateFormat = formatter(DEFAULT_DATE_FORMAT);

    public Messages(Logger logger) {
        this.logger = logger;
    }

    public void load(ConfigurationSection config) {
        this.config = config;
        this.prefix = Placeholder.component("prefix", MINI_MESSAGE.deserialize(config.getString("prefix", "")));
        try {
            this.dateFormat = formatter(config.getString("date-format", DEFAULT_DATE_FORMAT));
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid date-format in messages.yml, using the default one.");
            this.dateFormat = formatter(DEFAULT_DATE_FORMAT);
        }
    }

    public void send(Audience audience, String path, TagResolver... placeholders) {
        Component message = get(path, placeholders);
        if (message != null) {
            audience.sendMessage(message);
        }
    }

    /** Returns {@code null} when the message has been left empty on purpose. */
    public @Nullable Component get(String path, TagResolver... placeholders) {
        String raw = raw(path);
        return raw.isEmpty() ? null : parse(raw, placeholders);
    }

    public Component parse(String raw, TagResolver... placeholders) {
        return MINI_MESSAGE.deserialize(raw, TagResolver.resolver(prefix, TagResolver.resolver(placeholders)));
    }

    public String raw(String path) {
        if (config.isList(path)) {
            return String.join("\n", config.getStringList(path));
        }
        String value = config.getString(path);
        if (value == null) {
            logger.warning("Missing message '" + path + "' in messages.yml");
            return "";
        }
        return value;
    }

    public String date(Instant instant) {
        return dateFormat.format(instant);
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withZone(ZoneId.systemDefault());
    }
}
