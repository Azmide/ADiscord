package com.azmide.adiscord.storage;

import com.azmide.adiscord.config.Settings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Connection pool plus a small thread pool so queries never run on the server thread. */
public final class Database {

    @FunctionalInterface
    public interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    private final String tablePrefix;
    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public Database(File dataFolder, Settings.Storage settings) {
        this.tablePrefix = settings.tablePrefix();

        HikariConfig config = new HikariConfig();
        config.setPoolName("ADiscord");
        int threads;
        if (settings.type() == Settings.Storage.Type.MYSQL) {
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(settings.poolSize());
            settings.properties().forEach(config::addDataSourceProperty);
            threads = Math.min(settings.poolSize(), 4);
        } else {
            // SQLite only handles one writer at a time, a single connection avoids lock errors
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + new File(dataFolder, "data.db").getAbsolutePath());
            config.setMaximumPoolSize(1);
            threads = 1;
        }

        this.dataSource = new HikariDataSource(config);
        this.executor = Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("ADiscord-Database-", 1).daemon(true).factory());
    }

    public void createTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("links") + " ("
                    + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "player_name VARCHAR(32) NOT NULL, "
                    + "discord_id BIGINT NOT NULL UNIQUE, "
                    + "linked_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("rewards") + " ("
                    + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "discord_id BIGINT NOT NULL, "
                    + "claimed_at BIGINT NOT NULL)");
        }
    }

    public String table(String name) {
        return tablePrefix + name;
    }

    public <T> CompletableFuture<T> submit(Query<T> query) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return query.run(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
