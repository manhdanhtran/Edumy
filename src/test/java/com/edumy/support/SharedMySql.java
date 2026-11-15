package com.edumy.support;

import java.io.IOException;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The ONE MySQL 8.4 container of a test JVM (docs/ai/07). Started the first time this class is touched and left running
 * until the JVM exits (Ryuk removes it); never stopped by a Spring context, so every cached context shares it.
 * Same server settings as compose.yaml: UTC session time zone, utf8mb4 / utf8mb4_0900_ai_ci (docs/ai/03 §3.1),
 * log-bin-trust-function-creators (non-SUPER user may create the V5 triggers).
 *
 * <p>Two databases live in it: {@code edumy} (migrations only) and {@link #SEED_DATABASE} (migrations + dev seed).
 * Use the annotations {@link MySqlSpringBootTest}, {@link MySqlJdbcTest}, {@link MySqlSeedTest}, not this class.
 */
public final class SharedMySql {

    /** Database for tests that need the dev seed (V1000+). Keeps seed rows out of the default database. */
    public static final String SEED_DATABASE = "edumy_seed";

    public static final MySQLContainer INSTANCE = startContainer();

    private SharedMySql() {
    }

    /** JDBC URL of {@link #SEED_DATABASE}: same host, port and UTC parameters as the default database. */
    public static String seedJdbcUrl() {
        String url = INSTANCE.getJdbcUrl();
        String seedUrl = url.replace("/" + INSTANCE.getDatabaseName() + "?", "/" + SEED_DATABASE + "?");
        if (seedUrl.equals(url)) {
            throw new IllegalStateException("Cannot derive the seed database URL from " + url);
        }
        return seedUrl;
    }

    private static MySQLContainer startContainer() {
        MySQLContainer container = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("edumy")
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true")
                // log-bin-trust-function-creators: binary logging is on by default in MySQL 8.4, and without this a
                // non-SUPER user (the Testcontainers user) cannot CREATE TRIGGER (error 1419, V5__triggers.sql).
                .withCommand(
                        "--default-time-zone=+00:00",
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci",
                        "--log-bin-trust-function-creators=1");
        container.start();
        createSeedDatabase(container);
        return container;
    }

    /**
     * Testcontainers' default user owns only {@code edumy}; create the second database as root and grant it. Also
     * grants {@code PROCESS} (test only): concurrency tests read {@code information_schema.innodb_trx} to wait until a
     * request really blocks on a row lock ({@code support/LockWaits}), instead of sleeping.
     */
    private static void createSeedDatabase(MySQLContainer container) {
        String sql = "CREATE DATABASE IF NOT EXISTS " + SEED_DATABASE
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
                + " GRANT ALL PRIVILEGES ON " + SEED_DATABASE + ".* TO '" + container.getUsername() + "'@'%';"
                + " GRANT PROCESS ON *.* TO '" + container.getUsername() + "'@'%';";
        try {
            var result = container.execInContainer("mysql", "-uroot", "-p" + container.getPassword(), "-e", sql);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Creating " + SEED_DATABASE + " failed: " + result.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Creating " + SEED_DATABASE + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating " + SEED_DATABASE, e);
        }
    }
}
