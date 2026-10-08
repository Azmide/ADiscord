package com.azmide.adiscord.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * InteractiveChat marks chat messages with tags such as {@code <chat=uuid:[item]:>} so it can tell
 * who sent them. Those tags are meant for InteractiveChat only and look broken on Discord.
 */
public final class InteractiveChatTags {

    private static final Pattern SENDER = Pattern.compile(
            "<(?:cmd|chat)=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?::(.*?):)?>");
    private static final Pattern MENTION = Pattern.compile("<IC\\^(.*?)>");

    private InteractiveChatTags() {
    }

    public static String strip(String message) {
        if (message.indexOf('<') < 0) {
            return message;
        }
        String result = SENDER.matcher(message).replaceAll(match -> match.group(1) == null
                ? ""
                : Matcher.quoteReplacement(match.group(1).replace("\\>", ">")));
        return MENTION.matcher(result).replaceAll("$1").strip();
    }
}
