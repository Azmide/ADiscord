package com.azmide.adiscord.config;

import com.azmide.adiscord.discord.EmbedTemplate;
import com.azmide.adiscord.util.Placeholders;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Texts and embeds from discord.yml. */
public final class DiscordMessages {

    private final Map<String, EmbedTemplate> embeds = new ConcurrentHashMap<>();
    private ConfigurationSection config = new MemoryConfiguration();

    public void load(ConfigurationSection config) {
        this.config = config;
        this.embeds.clear();
    }

    public String string(String path) {
        String value = config.getString(path);
        return value == null ? "" : value;
    }

    public boolean bool(String path) {
        return config.getBoolean(path);
    }

    public EmbedTemplate embed(String path) {
        return embeds.computeIfAbsent(path, key -> {
            ConfigurationSection section = config.getConfigurationSection(key);
            return EmbedTemplate.from(section != null ? section : new MemoryConfiguration());
        });
    }

    /** {player}, {uuid} and {avatar} for the given player. */
    public Placeholders player(String name, UUID uuid) {
        return Placeholders.of("player", name)
                .with("uuid", uuid)
                .with("avatar", avatar(name, uuid));
    }

    public String avatar(String name, UUID uuid) {
        return Placeholders.of("uuid", uuid).with("player", name).apply(string("avatar-url"));
    }
}
