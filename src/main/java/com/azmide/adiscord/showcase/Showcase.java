package com.azmide.adiscord.showcase;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.DiscordMessages;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.image.TooltipImage;
import com.azmide.adiscord.util.Placeholders;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns [item], [inv] and [ender] in chat into something that makes sense on Discord:
 * a picture of the item tooltip, or a list of what is in the inventory.
 */
public final class Showcase {

    /** Marks where a keyword was, so the rest of the message can be escaped without touching it. */
    private static final char MARK = '\u0000';
    private static final String ITEM_FILE = "item.png";

    /** The finished Discord message. The content is already escaped for markdown. */
    public record Output(String content, List<FileUpload> files, List<MessageEmbed> embeds) {

        public static Output text(String message) {
            return new Output(MarkdownSanitizer.escape(message), List.of(), List.of());
        }
    }

    /** Copies of the items, taken on the main thread so they can be drawn on another one. */
    public record Snapshot(String message, @Nullable ItemStack item,
                           @Nullable List<ItemStack> inventory, @Nullable List<ItemStack> enderChest) {
    }

    private final ADiscordPlugin plugin;

    public Showcase(ADiscordPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean matches(String message) {
        Settings.Showcase settings = plugin.settings().chat().showcase();
        return settings.enabled() && (found(settings.item(), message)
                || found(settings.inventory(), message)
                || found(settings.enderChest(), message));
    }

    /** Must be called on the main thread. */
    public Snapshot capture(Player player, String message) {
        Settings.Showcase settings = plugin.settings().chat().showcase();
        ItemStack held = player.getInventory().getItemInMainHand();
        return new Snapshot(message,
                found(settings.item(), message) && !held.isEmpty() ? held.clone() : null,
                found(settings.inventory(), message) ? copy(player.getInventory().getContents()) : null,
                found(settings.enderChest(), message) ? copy(player.getEnderChest().getContents()) : null);
    }

    public Output render(String player, UUID uuid, Snapshot snapshot) {
        Settings.Showcase settings = plugin.settings().chat().showcase();
        DiscordMessages texts = plugin.discordMessages();
        Placeholders playerName = Placeholders.of("player", MarkdownSanitizer.escape(player));

        List<String> replacements = new ArrayList<>();
        List<FileUpload> files = new ArrayList<>();
        List<MessageEmbed> embeds = new ArrayList<>();
        String text = snapshot.message();

        ItemStack item = snapshot.item();
        if (item != null) {
            String format = texts.string(item.getAmount() > 1 ? "showcase.item-stack" : "showcase.item");
            String replacement = Placeholders.of("item", MarkdownSanitizer.escape(plain(item.effectiveName())))
                    .with("amount", item.getAmount())
                    .apply(format);
            text = mark(text, settings.item(), replacement, replacements);
            try {
                files.add(FileUpload.fromData(TooltipImage.render(tooltip(item)), ITEM_FILE));
            } catch (IOException | RuntimeException | LinkageError | InternalError e) {
                plugin.getLogger().log(Level.WARNING, "Could not draw the item picture, sending the name only", e);
            }
        }
        if (snapshot.inventory() != null) {
            text = mark(text, settings.inventory(), playerName.apply(texts.string("showcase.inventory")), replacements);
            embeds.add(itemList(player, uuid, texts.string("showcase.inventory-title"), snapshot.inventory()));
        }
        if (snapshot.enderChest() != null) {
            text = mark(text, settings.enderChest(), playerName.apply(texts.string("showcase.ender-chest")), replacements);
            embeds.add(itemList(player, uuid, texts.string("showcase.ender-chest-title"), snapshot.enderChest()));
        }

        String content = MarkdownSanitizer.escape(text);
        for (int i = 0; i < replacements.size(); i++) {
            content = content.replace(token(i), replacements.get(i));
        }
        return new Output(content, files, embeds);
    }

    private MessageEmbed itemList(String player, UUID uuid, String title, List<ItemStack> items) {
        DiscordMessages texts = plugin.discordMessages();

        // Stacks with the same name are added up, in the order they appear in the inventory
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (ItemStack item : items) {
            amounts.merge(plain(item.effectiveName()), item.getAmount(), Integer::sum);
        }

        String entry = texts.string("showcase.entry");
        String list = amounts.isEmpty()
                ? texts.string("showcase.empty")
                : amounts.entrySet().stream()
                .map(stack -> Placeholders.of("item", MarkdownSanitizer.escape(stack.getKey()))
                        .with("amount", stack.getValue())
                        .apply(entry))
                .collect(Collectors.joining(texts.string("showcase.separator")));

        Placeholders placeholders = texts.player(player, uuid)
                .with("title", Placeholders.of("player", player).apply(title))
                .with("items", list);
        return texts.embed("showcase.embed").build(placeholders);
    }

    /** Name, enchantments and lore, styled like the tooltip in game. */
    private static List<Component> tooltip(ItemStack item) {
        List<Component> lines = new ArrayList<>();

        Component name = item.effectiveName().colorIfAbsent(rarity(item).color());
        if (item.hasData(DataComponentTypes.CUSTOM_NAME)) {
            name = name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.TRUE);
        }
        lines.add(name);

        ItemEnchantments stored = item.getData(DataComponentTypes.STORED_ENCHANTMENTS);
        Map<Enchantment, Integer> enchantments = stored != null ? stored.enchantments() : item.getEnchantments();
        enchantments.forEach((enchantment, level) -> lines.add(enchantment.displayName(level)));

        ItemLore lore = item.getData(DataComponentTypes.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(line.colorIfAbsent(NamedTextColor.DARK_PURPLE)
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.TRUE));
            }
        }
        return lines;
    }

    /** Enchanted items show up one rarity higher, the same as in game. */
    private static ItemRarity rarity(ItemStack item) {
        ItemRarity rarity = Objects.requireNonNullElse(item.getData(DataComponentTypes.RARITY), ItemRarity.COMMON);
        if (item.getEnchantments().isEmpty()) {
            return rarity;
        }
        return switch (rarity) {
            case COMMON, UNCOMMON -> ItemRarity.RARE;
            case RARE, EPIC -> ItemRarity.EPIC;
        };
    }

    private static String mark(String text, @Nullable Pattern keyword, String replacement, List<String> replacements) {
        if (keyword == null) {
            return text;
        }
        String token = token(replacements.size());
        replacements.add(replacement);
        return keyword.matcher(text).replaceAll(Matcher.quoteReplacement(token));
    }

    private static String token(int index) {
        return MARK + String.valueOf(index) + MARK;
    }

    private static boolean found(@Nullable Pattern keyword, String message) {
        return keyword != null && keyword.matcher(message).find();
    }

    private static List<ItemStack> copy(@Nullable ItemStack[] contents) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : contents) {
            if (item != null && !item.isEmpty()) {
                items.add(item.clone());
            }
        }
        return items;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
