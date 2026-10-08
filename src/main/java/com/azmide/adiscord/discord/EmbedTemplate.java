package com.azmide.adiscord.discord;

import com.azmide.adiscord.util.Placeholders;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An embed read from discord.yml that gets its placeholders filled in right before sending. */
public record EmbedTemplate(
        String color,
        String authorName,
        String authorIcon,
        String authorUrl,
        String title,
        String url,
        String description,
        String thumbnail,
        String image,
        String footerText,
        String footerIcon,
        boolean timestamp,
        List<Field> fields) {

    public record Field(String name, String value, boolean inline) {
    }

    public static EmbedTemplate from(ConfigurationSection section) {
        List<Field> fields = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("fields")) {
            Object inline = raw.get("inline");
            fields.add(new Field(
                    String.valueOf(raw.get("name")),
                    String.valueOf(raw.get("value")),
                    inline instanceof Boolean value && value));
        }

        return new EmbedTemplate(
                section.getString("color", ""),
                section.getString("author.name", ""),
                section.getString("author.icon", ""),
                section.getString("author.url", ""),
                section.getString("title", ""),
                section.getString("url", ""),
                section.getString("description", ""),
                section.getString("thumbnail", ""),
                section.getString("image", ""),
                section.getString("footer.text", ""),
                section.getString("footer.icon", ""),
                section.getBoolean("timestamp"),
                List.copyOf(fields));
    }

    public MessageEmbed build(Placeholders placeholders) {
        EmbedBuilder embed = new EmbedBuilder();

        Integer rgb = parseColor(color);
        if (rgb != null) {
            embed.setColor(rgb);
        }

        String author = text(authorName, placeholders, MessageEmbed.AUTHOR_MAX_LENGTH);
        if (!author.isEmpty()) {
            embed.setAuthor(author, link(authorUrl, placeholders), link(authorIcon, placeholders));
        }

        String titleText = text(title, placeholders, MessageEmbed.TITLE_MAX_LENGTH);
        if (!titleText.isEmpty()) {
            embed.setTitle(titleText, link(url, placeholders));
        }

        embed.setDescription(text(description, placeholders, MessageEmbed.DESCRIPTION_MAX_LENGTH));
        embed.setThumbnail(link(thumbnail, placeholders));
        embed.setImage(link(image, placeholders));

        String footer = text(footerText, placeholders, MessageEmbed.TEXT_MAX_LENGTH);
        if (!footer.isEmpty()) {
            embed.setFooter(footer, link(footerIcon, placeholders));
        }

        for (Field field : fields) {
            String name = text(field.name(), placeholders, MessageEmbed.TITLE_MAX_LENGTH);
            String value = text(field.value(), placeholders, MessageEmbed.VALUE_MAX_LENGTH);
            embed.addField(name.isEmpty() ? "​" : name, value.isEmpty() ? "​" : value, field.inline());
        }

        if (timestamp) {
            embed.setTimestamp(Instant.now());
        }
        if (embed.isEmpty()) {
            embed.setDescription("​");
        }
        return embed.build();
    }

    private static String text(String template, Placeholders placeholders, int maxLength) {
        String value = placeholders.apply(template).strip();
        return value.length() > maxLength ? value.substring(0, maxLength - 3) + "..." : value;
    }

    /** Discord rejects anything that is not an http(s) link, so drop those instead of failing. */
    private static String link(String template, Placeholders placeholders) {
        String value = placeholders.apply(template).strip();
        return value.startsWith("https://") || value.startsWith("http://") ? value : null;
    }

    private static Integer parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(hex.strip().replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
