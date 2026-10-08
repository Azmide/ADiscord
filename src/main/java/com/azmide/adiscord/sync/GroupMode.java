package com.azmide.adiscord.sync;

/** Which LuckPerms groups are taken into account when syncing roles. */
public enum GroupMode {

    /** Groups given directly to the player, temporary ones included. */
    DIRECT,

    /** Every group the player has, including inherited ones. */
    ALL,

    /** Only the player's primary group. */
    PRIMARY
}
