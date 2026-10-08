package com.azmide.adiscord.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

/** Reads prefixes from LuckPerms, which can be written with legacy codes (&a) or MiniMessage. */
public final class Text {

    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§](?:#[0-9a-f]{6}|[0-9a-fk-orx])");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    public static Component colored(String text) {
        if (text.isEmpty()) {
            return Component.empty();
        }
        if (LEGACY_CODE.matcher(text).find()) {
            return LEGACY.deserialize(text.replace('§', '&'));
        }
        return MiniMessage.miniMessage().deserialize(text);
    }

    public static String plain(String text) {
        return PlainTextComponentSerializer.plainText().serialize(colored(text)).strip();
    }
}
