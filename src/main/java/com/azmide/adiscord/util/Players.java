package com.azmide.adiscord.util;

import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

public final class Players {

    private Players() {
    }

    /**
     * Vanish plugins (SuperVanish, PremiumVanish, Essentials) still share their state through
     * the "vanished" metadata, even though Paper has deprecated the metadata API.
     */
    @SuppressWarnings("deprecation")
    public static boolean isVanished(Player player) {
        return player.getMetadata("vanished").stream().anyMatch(MetadataValue::asBoolean);
    }
}
