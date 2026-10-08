package com.azmide.adiscord.link;

import java.time.Instant;
import java.util.UUID;

public record LinkedAccount(UUID uuid, String name, long discordId, Instant linkedAt) {

    public LinkedAccount withName(String newName) {
        return new LinkedAccount(uuid, newName, discordId, linkedAt);
    }
}
