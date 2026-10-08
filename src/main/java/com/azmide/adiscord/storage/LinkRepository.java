package com.azmide.adiscord.storage;

import com.azmide.adiscord.link.LinkedAccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Reads and writes linked accounts. Every method runs off the main thread. */
public final class LinkRepository {

    private final Database database;
    private final String links;
    private final String rewards;

    public LinkRepository(Database database) {
        this.database = database;
        this.links = database.table("links");
        this.rewards = database.table("rewards");
    }

    public CompletableFuture<Optional<LinkedAccount>> find(UUID uuid) {
        return database.submit(connection -> findOne(connection, "player_uuid = ?", uuid.toString()));
    }

    public CompletableFuture<Optional<LinkedAccount>> findByDiscord(long discordId) {
        return database.submit(connection -> findOne(connection, "discord_id = ?", discordId));
    }

    public CompletableFuture<Optional<LinkedAccount>> findByName(String name) {
        return database.submit(connection -> findOne(connection, "LOWER(player_name) = LOWER(?)", name));
    }

    public CompletableFuture<List<LinkedAccount>> findAll() {
        return database.submit(connection -> {
            List<LinkedAccount> accounts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + links);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    accounts.add(read(result));
                }
            }
            return accounts;
        });
    }

    /** Saves the link, unless the player or the Discord account is already linked. */
    public CompletableFuture<Boolean> insert(LinkedAccount account) {
        return database.submit(connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT 1 FROM " + links + " WHERE player_uuid = ? OR discord_id = ?",
                    account.uuid().toString(), account.discordId());
                 ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return false;
                }
            }
            try (PreparedStatement statement = prepare(connection,
                    "INSERT INTO " + links + " (player_uuid, player_name, discord_id, linked_at) VALUES (?, ?, ?, ?)",
                    account.uuid().toString(), account.name(), account.discordId(), account.linkedAt().toEpochMilli())) {
                statement.executeUpdate();
            }
            return true;
        });
    }

    public CompletableFuture<Optional<LinkedAccount>> remove(UUID uuid) {
        return removeWhere("player_uuid = ?", uuid.toString());
    }

    public CompletableFuture<Optional<LinkedAccount>> removeByDiscord(long discordId) {
        return removeWhere("discord_id = ?", discordId);
    }

    public CompletableFuture<Void> updateName(UUID uuid, String name) {
        return database.submit(connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "UPDATE " + links + " SET player_name = ? WHERE player_uuid = ?", name, uuid.toString())) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Marks the link reward as claimed. Returns false when either account already got it once. */
    public CompletableFuture<Boolean> claimReward(UUID uuid, long discordId) {
        return database.submit(connection -> {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT 1 FROM " + rewards + " WHERE player_uuid = ? OR discord_id = ?",
                    uuid.toString(), discordId);
                 ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return false;
                }
            }
            try (PreparedStatement statement = prepare(connection,
                    "INSERT INTO " + rewards + " (player_uuid, discord_id, claimed_at) VALUES (?, ?, ?)",
                    uuid.toString(), discordId, System.currentTimeMillis())) {
                statement.executeUpdate();
            }
            return true;
        });
    }

    private CompletableFuture<Optional<LinkedAccount>> removeWhere(String condition, Object value) {
        return database.submit(connection -> {
            Optional<LinkedAccount> account = findOne(connection, condition, value);
            if (account.isPresent()) {
                try (PreparedStatement statement = prepare(connection,
                        "DELETE FROM " + links + " WHERE " + condition, value)) {
                    statement.executeUpdate();
                }
            }
            return account;
        });
    }

    private Optional<LinkedAccount> findOne(Connection connection, String condition, Object value) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "SELECT * FROM " + links + " WHERE " + condition, value);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(read(result)) : Optional.empty();
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
        return statement;
    }

    private static LinkedAccount read(ResultSet result) throws SQLException {
        return new LinkedAccount(
                UUID.fromString(result.getString("player_uuid")),
                result.getString("player_name"),
                result.getLong("discord_id"),
                Instant.ofEpochMilli(result.getLong("linked_at")));
    }
}
