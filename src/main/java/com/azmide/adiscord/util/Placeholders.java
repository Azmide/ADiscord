package com.azmide.adiscord.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Replaces {@code {key}} style placeholders in the texts the bot sends to Discord. */
public final class Placeholders {

    private final Map<String, String> values = new LinkedHashMap<>();

    public static Placeholders of(String key, Object value) {
        return new Placeholders().with(key, value);
    }

    public static Placeholders empty() {
        return new Placeholders();
    }

    public Placeholders with(String key, Object value) {
        values.put("{" + key + "}", String.valueOf(value));
        return this;
    }

    public String apply(String text) {
        if (text == null || text.isEmpty() || text.indexOf('{') < 0) {
            return text == null ? "" : text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
