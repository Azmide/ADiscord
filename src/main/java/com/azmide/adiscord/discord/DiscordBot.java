package com.azmide.adiscord.discord;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.util.Placeholders;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Owns the JDA connection and everything that has to be set up once the bot is online. */
public final class DiscordBot extends ListenerAdapter {

    private static final Permission[] PERMISSIONS = {
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_HISTORY,
            Permission.MANAGE_ROLES,
            Permission.NICKNAME_MANAGE,
            Permission.MANAGE_WEBHOOKS
    };

    private final ADiscordPlugin plugin;
    private final VerifyListener verify;
    private final PlayerListListener playerList;
    private final ChatBridge chat;
    private final AtomicBoolean announcedStart = new AtomicBoolean();
    private final Object activityLock = new Object();

    private volatile @Nullable JDA jda;
    private volatile @Nullable String lastActivity;
    private @Nullable ScheduledTask activityTask;
    private boolean closed;

    public DiscordBot(ADiscordPlugin plugin) {
        this.plugin = plugin;
        this.verify = new VerifyListener(plugin);
        this.playerList = new PlayerListListener(plugin);
        this.chat = new ChatBridge(plugin);
    }

    public void start() {
        Settings.Bot settings = plugin.settings().bot();
        if (!settings.hasToken()) {
            plugin.getLogger().warning("No bot token in config.yml. Add one and restart the server to enable Discord.");
            return;
        }
        if (settings.guildId() == 0) {
            plugin.getLogger().warning("No guild-id in config.yml. Add your Discord server ID and restart the server.");
            return;
        }
        // Logging in makes a web request, keep it off the main thread
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> connect(settings.token()));
    }

    private synchronized void connect(String token) {
        if (closed) {
            return;
        }
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .addEventListeners(this, verify, playerList, chat)
                    .build();
        } catch (InvalidTokenException e) {
            plugin.getLogger().severe("Discord rejected the bot token, check bot.token in config.yml.");
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not start the Discord bot", e);
        }
    }

    public synchronized void shutdown() {
        closed = true;
        stopActivity();
        playerList.close();
        JDA current = jda;
        if (current == null) {
            return;
        }

        if (plugin.settings().events().serverStop()) {
            GuildMessageChannel channel = channel(plugin.settings().eventChannelId());
            if (channel != null && channel.canTalk()) {
                MessageEmbed embed = plugin.discordMessages().embed("events.server-stop").build(Placeholders.empty());
                try {
                    channel.sendMessageEmbeds(embed).submit().get(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
                    // The server is stopping either way
                }
            }
        }

        jda = null;
        current.shutdown();
        try {
            if (!current.awaitShutdown(Duration.ofSeconds(5))) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Applies config changes after /discord reload. */
    public void refresh() {
        Guild guild = guild();
        if (guild != null) {
            setup(guild);
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA current = event.getJDA();
        current.setRequiredScopes("applications.commands");

        Guild guild = guild();
        if (guild == null) {
            plugin.getLogger().severe("The bot is not in the server set as bot.guild-id. Invite it with: "
                    + current.getInviteUrl(PERMISSIONS));
            return;
        }

        plugin.getLogger().info("Connected to Discord as " + current.getSelfUser().getName() + " on " + guild.getName() + ".");
        setup(guild);

        if (plugin.settings().events().serverStart() && announcedStart.compareAndSet(false, true)) {
            sendEvent("events.server-start", Placeholders.empty());
        }
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        if (event.getCloseCode() == CloseCode.DISALLOWED_INTENTS) {
            plugin.getLogger().severe("Discord refused to connect. Turn on the SERVER MEMBERS and MESSAGE CONTENT "
                    + "intents for your bot at https://discord.com/developers/applications and restart.");
        }
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (event.getGuild().getIdLong() != plugin.settings().bot().guildId()) {
            return;
        }
        // Members who left and came back get their roles again
        plugin.links().repository().findByDiscord(event.getUser().getIdLong())
                .thenAccept(account -> account.ifPresent(plugin.roleSync()::sync))
                .exceptionally(error -> plugin.logError("syncing a returning member", error));
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        if (event.getGuild().getIdLong() != plugin.settings().bot().guildId()
                || !plugin.settings().linking().unlinkOnLeave()) {
            return;
        }
        plugin.links().repository().removeByDiscord(event.getUser().getIdLong())
                .thenAccept(account -> account.ifPresent(removed -> plugin.getLogger().info(
                        "Unlinked " + removed.name() + " because they left the Discord server.")))
                .exceptionally(error -> plugin.logError("unlinking a member who left", error));
    }

    public boolean isReady() {
        return guild() != null;
    }

    public @Nullable Guild guild() {
        JDA current = jda;
        return current == null ? null : current.getGuildById(plugin.settings().bot().guildId());
    }

    public @Nullable GuildMessageChannel channel(long id) {
        Guild guild = guild();
        return guild == null || id == 0 ? null : guild.getChannelById(GuildMessageChannel.class, id);
    }

    public ChatBridge chat() {
        return chat;
    }

    /** The Discord username for an ID, or the ID itself when it can not be looked up. */
    public CompletableFuture<String> userName(long discordId) {
        JDA current = jda;
        if (current == null) {
            return CompletableFuture.completedFuture(String.valueOf(discordId));
        }
        return current.retrieveUserById(discordId).submit()
                .thenApply(User::getName)
                .exceptionally(error -> String.valueOf(discordId));
    }

    /** Posts one of the embeds under "events" in discord.yml to the event channel. */
    public void sendEvent(String path, Placeholders placeholders) {
        GuildMessageChannel channel = channel(plugin.settings().eventChannelId());
        if (channel == null || !channel.canTalk()) {
            return;
        }
        try {
            channel.sendMessageEmbeds(plugin.discordMessages().embed(path).build(placeholders))
                    .queue(null, error -> plugin.getLogger().warning("Could not send to #" + channel.getName() + ": " + error.getMessage()));
        } catch (InsufficientPermissionException e) {
            plugin.getLogger().warning("Missing the " + e.getPermission().getName() + " permission in #" + channel.getName() + ".");
        }
    }

    private void setup(Guild guild) {
        try {
            guild.updateCommands()
                    .addCommands(verify.command(), playerList.command())
                    .queue(null, error -> plugin.getLogger().warning("Could not register slash commands: " + error.getMessage()));
        } catch (IllegalArgumentException e) {
            // Names must be lowercase without spaces, descriptions up to 100 characters
            plugin.getLogger().warning("Invalid slash command in discord.yml: " + e.getMessage());
        }

        verify.postPanel(guild);
        chat.setup(guild);
        plugin.roleSync().resetWarnings();

        Settings settings = plugin.settings();
        warnIfMissing(guild, settings.linking().verifyChannelId(), "linking.verify-channel-id");
        if (settings.chat().enabled()) {
            warnIfMissing(guild, settings.chat().channelId(), "chat.channel-id");
        }
        warnIfMissing(guild, settings.eventChannelId(), "events.channel-id");

        startActivity();
    }

    private void warnIfMissing(Guild guild, long channelId, String path) {
        if (channelId != 0 && guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            plugin.getLogger().warning("Could not find the channel set as " + path + " in config.yml.");
        }
    }

    private void startActivity() {
        synchronized (activityLock) {
            stopActivity();
            JDA current = jda;
            if (current == null) {
                return;
            }
            Settings.Bot settings = plugin.settings().bot();
            if (!settings.activityEnabled()) {
                current.getPresence().setActivity(null);
                return;
            }
            activityTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> updateActivity(),
                    1, settings.activityInterval(), TimeUnit.SECONDS);
        }
    }

    private void stopActivity() {
        synchronized (activityLock) {
            if (activityTask != null) {
                activityTask.cancel();
                activityTask = null;
            }
            lastActivity = null;
        }
    }

    private void updateActivity() {
        JDA current = jda;
        if (current == null) {
            return;
        }
        Settings.Bot settings = plugin.settings().bot();
        String text = Placeholders.of("online", plugin.getServer().getOnlinePlayers().size())
                .with("max", plugin.getServer().getMaxPlayers())
                .apply(settings.activityText())
                .strip();
        if (text.isEmpty() || text.equals(lastActivity)) {
            return;
        }
        lastActivity = text;
        current.getPresence().setActivity(Activity.of(settings.activityType(), text));
    }
}
