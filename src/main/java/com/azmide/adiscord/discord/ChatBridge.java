package com.azmide.adiscord.discord;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.DiscordMessages;
import com.azmide.adiscord.config.Messages;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.hook.LuckPermsHook;
import com.azmide.adiscord.showcase.Showcase;
import com.azmide.adiscord.util.InteractiveChatTags;
import com.azmide.adiscord.util.Placeholders;
import com.azmide.adiscord.util.Text;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.RoleColors;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.WebhookType;
import net.dv8tion.jda.api.entities.channel.attribute.IWebhookContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends chat both ways between the game and the Discord chat channel. */
public final class ChatBridge extends ListenerAdapter {

    // Webhook names may not contain the word "discord"
    private static final String WEBHOOK_NAME = "Minecraft Chat";
    private static final Pattern RESERVED_NAMES = Pattern.compile("(?i)discord|clyde");
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_WEBHOOK_NAME_LENGTH = 80;

    private final ADiscordPlugin plugin;
    private final Showcase showcase;
    private volatile @Nullable Webhook webhook;

    ChatBridge(ADiscordPlugin plugin) {
        this.plugin = plugin;
        this.showcase = new Showcase(plugin);
    }

    /** Finds the webhook the bot made earlier, or creates one. */
    void setup(Guild guild) {
        webhook = null;
        Settings.Chat chat = plugin.settings().chat();
        if (!chat.enabled() || !chat.toDiscord() || !chat.webhook()) {
            return;
        }
        GuildMessageChannel channel = plugin.bot().channel(chat.channelId());
        if (!(channel instanceof IWebhookContainer container)) {
            return;
        }
        if (!guild.getSelfMember().hasPermission(container, Permission.MANAGE_WEBHOOKS)) {
            plugin.getLogger().warning("The bot needs the Manage Webhooks permission in #" + channel.getName()
                    + " to show player heads in chat. Falling back to normal messages.");
            return;
        }

        long selfId = guild.getSelfMember().getIdLong();
        container.retrieveWebhooks().queue(hooks -> {
            Webhook own = hooks.stream()
                    .filter(hook -> hook.getType() == WebhookType.INCOMING && hook.getToken() != null)
                    .filter(hook -> hook.getOwnerAsUser() != null && hook.getOwnerAsUser().getIdLong() == selfId)
                    .findFirst()
                    .orElse(null);
            if (own != null) {
                webhook = own;
            } else {
                container.createWebhook(WEBHOOK_NAME).queue(created -> webhook = created,
                        error -> plugin.getLogger().warning("Could not create the chat webhook: " + error.getMessage()));
            }
        }, error -> plugin.getLogger().warning("Could not load the chat webhooks: " + error.getMessage()));
    }

    public void toDiscord(Player player, String rawMessage) {
        Settings.Chat chat = plugin.settings().chat();
        String message = InteractiveChatTags.strip(rawMessage);
        if (!chat.enabled() || !chat.toDiscord() || message.isBlank()) {
            return;
        }

        String name = player.getName();
        UUID uuid = player.getUniqueId();
        LuckPermsHook luckPerms = plugin.luckPerms();
        String prefix = luckPerms != null ? Text.plain(luckPerms.prefix(player)) : "";

        if (!showcase.matches(message)) {
            send(name, uuid, prefix, Showcase.Output.text(message));
            return;
        }
        // Inventories can only be read on the main thread, the picture is drawn off it again
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Showcase.Snapshot snapshot = showcase.capture(player, message);
            plugin.getServer().getAsyncScheduler().runNow(plugin, task ->
                    send(name, uuid, prefix, showcase.render(name, uuid, snapshot)));
        });
    }

    private void send(String name, UUID uuid, String prefix, Showcase.Output output) {
        DiscordMessages texts = plugin.discordMessages();
        String content = limit(output.content(), MAX_MESSAGE_LENGTH);

        Webhook hook = webhook;
        if (hook != null) {
            String username = Placeholders.of("prefix", decorate(prefix, texts.string("chat.webhook-prefix")))
                    .with("player", name)
                    .apply(texts.string("chat.webhook-name"))
                    .strip();
            String avatar = texts.avatar(name, uuid);
            hook.sendMessage(content)
                    .setUsername(limit(safeUsername(username.isEmpty() ? name : username), MAX_WEBHOOK_NAME_LENGTH))
                    .setAvatarUrl(avatar.isBlank() ? null : avatar)
                    .addFiles(output.files())
                    .addEmbeds(output.embeds())
                    .queue(null, error -> {
                        if (error instanceof ErrorResponseException response
                                && response.getErrorResponse() == ErrorResponse.UNKNOWN_WEBHOOK) {
                            // Someone deleted the webhook, use plain messages until the next reload
                            webhook = null;
                            plugin.getLogger().warning("The chat webhook was deleted, sending chat as the bot until /discord reload.");
                        } else {
                            plugin.getLogger().warning("Could not send chat to Discord: " + error.getMessage());
                        }
                    });
            return;
        }

        GuildMessageChannel channel = plugin.bot().channel(plugin.settings().chat().channelId());
        if (channel == null || !channel.canTalk()) {
            return;
        }
        String formatted = texts.player(name, uuid)
                .with("prefix", decorate(MarkdownSanitizer.escape(prefix), texts.string("chat.prefix")))
                .with("message", content)
                .apply(texts.string("chat.format"));
        try {
            channel.sendMessage(limit(formatted, MAX_MESSAGE_LENGTH))
                    .addFiles(output.files())
                    .addEmbeds(output.embeds())
                    .queue();
        } catch (InsufficientPermissionException e) {
            plugin.getLogger().warning("Missing the " + e.getPermission().getName() + " permission in #" + channel.getName() + ".");
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Settings.Chat chat = plugin.settings().chat();
        if (!chat.enabled() || !chat.toMinecraft() || !event.isFromGuild()
                || event.getChannel().getIdLong() != chat.channelId()) {
            return;
        }
        Member member = event.getMember();
        if (member == null || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        Message message = event.getMessage();
        String content = message.getContentDisplay().strip();
        if (content.length() > chat.maxLength()) {
            content = content.substring(0, chat.maxLength()) + "...";
        }
        boolean attachments = !message.getAttachments().isEmpty();
        if (content.isEmpty() && !attachments) {
            return;
        }

        Messages messages = plugin.messages();
        Component body = Component.text(content);
        if (attachments) {
            Component attachment = messages.parse(messages.raw("chat.attachment"));
            body = content.isEmpty() ? attachment : body.appendSpace().append(attachment);
        }

        List<Role> roles = member.getRoles();
        RoleColors colors = member.getColors();
        TextColor color = colors.isDefault() ? NamedTextColor.WHITE : TextColor.color(colors.getPrimaryRaw());

        Component line = messages.parse(messages.raw("chat.format"),
                Placeholder.unparsed("name", member.getEffectiveName()),
                Placeholder.unparsed("username", member.getUser().getName()),
                Placeholder.unparsed("role", roles.isEmpty() ? "" : roles.getFirst().getName()),
                Placeholder.styling("role_color", color),
                Placeholder.component("message", body));

        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getServer().broadcast(line));
    }

    /** Puts the prefix into its format, or leaves it out entirely when the player has none. */
    private static String decorate(String prefix, String format) {
        return prefix.isEmpty() ? "" : Placeholders.of("prefix", prefix).apply(format);
    }

    /** Discord refuses webhook names that contain "discord" or "clyde". */
    private static String safeUsername(String name) {
        return RESERVED_NAMES.matcher(name).replaceAll(match -> Matcher.quoteReplacement(match.group()
                .replace('o', '0').replace('O', '0')
                .replace('e', '3').replace('E', '3')));
    }

    private static String limit(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength - 3) + "..." : value;
    }
}
