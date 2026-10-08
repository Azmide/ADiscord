package com.azmide.adiscord.discord;

import com.azmide.adiscord.ADiscordPlugin;
import com.azmide.adiscord.config.DiscordMessages;
import com.azmide.adiscord.config.Settings;
import com.azmide.adiscord.link.LinkManager;
import com.azmide.adiscord.link.LinkedAccount;
import com.azmide.adiscord.util.Placeholders;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;

/** Handles /verify and the verify panel with its button and popup. */
public final class VerifyListener extends ListenerAdapter {

    private static final String BUTTON_ID = "adiscord:verify";
    private static final String MODAL_ID = "adiscord:verify-modal";
    private static final String INPUT_ID = "code";

    private final ADiscordPlugin plugin;

    VerifyListener(ADiscordPlugin plugin) {
        this.plugin = plugin;
    }

    SlashCommandData command() {
        DiscordMessages texts = plugin.discordMessages();
        return Commands.slash(texts.string("commands.verify.name"), texts.string("commands.verify.description"))
                .addOption(OptionType.STRING, texts.string("commands.verify.option"),
                        texts.string("commands.verify.option-description"), true)
                .setContexts(InteractionContextType.GUILD);
    }

    /** Posts the panel, or updates the one the bot posted earlier so the channel stays clean. */
    void postPanel(Guild guild) {
        Settings.Linking linking = plugin.settings().linking();
        if (!linking.verifyPanel() || linking.verifyChannelId() == 0) {
            return;
        }
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, linking.verifyChannelId());
        if (channel == null) {
            return;
        }

        DiscordMessages texts = plugin.discordMessages();
        MessageEmbed embed = texts.embed("panel.embed").build(Placeholders.empty());
        ActionRow row = ActionRow.of(Button.primary(BUTTON_ID, orDefault(texts.string("panel.button"), "Verify")));
        long selfId = guild.getSelfMember().getIdLong();

        try {
            channel.getIterableHistory().takeAsync(50).thenAccept(history -> {
                Message existing = history.stream()
                        .filter(message -> message.getAuthor().getIdLong() == selfId)
                        .findFirst()
                        .orElse(null);
                if (existing != null) {
                    existing.editMessageEmbeds(embed).setComponents(row).queue();
                } else {
                    channel.sendMessageEmbeds(embed).setComponents(row).queue();
                }
            }).exceptionally(error -> plugin.logError("posting the verify panel", error));
        } catch (InsufficientPermissionException e) {
            plugin.getLogger().warning("Can not post the verify panel, the bot is missing the "
                    + e.getPermission().getName() + " permission in #" + channel.getName() + ".");
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        DiscordMessages texts = plugin.discordMessages();
        if (!event.getName().equals(texts.string("commands.verify.name")) || event.getMember() == null) {
            return;
        }

        long verifyChannel = plugin.settings().linking().verifyChannelId();
        if (verifyChannel != 0 && event.getChannelIdLong() != verifyChannel) {
            Placeholders placeholders = Placeholders.of("channel", "<#" + verifyChannel + ">");
            event.replyEmbeds(texts.embed("verify.wrong-channel").build(placeholders)).setEphemeral(true).queue();
            return;
        }

        String code = event.getOption(texts.string("commands.verify.option"), "", OptionMapping::getAsString);
        verify(event, event.getMember(), code);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!BUTTON_ID.equals(event.getComponentId())) {
            return;
        }
        DiscordMessages texts = plugin.discordMessages();
        TextInput.Builder input = TextInput.create(INPUT_ID, TextInputStyle.SHORT).setRequiredRange(1, 32);
        String placeholder = texts.string("panel.modal.placeholder");
        if (!placeholder.isBlank()) {
            input.setPlaceholder(limit(placeholder, TextInput.MAX_PLACEHOLDER_LENGTH));
        }

        Modal modal = Modal.create(MODAL_ID, limit(orDefault(texts.string("panel.modal.title"), "Verify"), Modal.MAX_TITLE_LENGTH))
                .addComponents(Label.of(limit(orDefault(texts.string("panel.modal.label"), "Code"), Label.LABEL_MAX_LENGTH), input.build()))
                .build();
        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!MODAL_ID.equals(event.getModalId()) || event.getMember() == null) {
            return;
        }
        ModalMapping code = event.getValue(INPUT_ID);
        verify(event, event.getMember(), code == null ? "" : code.getAsString());
    }

    private void verify(IReplyCallback callback, Member member, String code) {
        callback.deferReply(true).queue();

        plugin.links().redeem(code, member.getIdLong()).whenComplete((outcome, error) -> {
            DiscordMessages texts = plugin.discordMessages();
            if (error != null) {
                plugin.logError("linking " + member.getUser().getName(), error);
                callback.getHook().editOriginalEmbeds(texts.embed("verify.error").build(Placeholders.empty())).queue();
                return;
            }

            String path = switch (outcome.status()) {
                case LINKED -> "verify.success";
                case INVALID_CODE -> "verify.invalid-code";
                case DISCORD_LINKED -> "verify.already-linked";
                case PLAYER_LINKED -> "verify.player-linked";
            };
            callback.getHook().editOriginalEmbeds(texts.embed(path).build(placeholders(outcome, member))).queue();
        });
    }

    private Placeholders placeholders(LinkManager.Outcome outcome, Member member) {
        LinkedAccount account = outcome.account();
        Placeholders placeholders = account != null
                ? plugin.discordMessages().player(account.name(), account.uuid())
                : Placeholders.empty();
        return placeholders.with("mention", member.getAsMention());
    }

    private static String orDefault(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private static String limit(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
