package com.edumy.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code @MySqlSeedTest}: database {@code edumy_seed} in the SAME container, migrated on its own (docs/ai/07). */
@MySqlSeedTest
class SeedDatabaseIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void seedTest_usesDatabaseEdumySeed() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo(SharedMySql.SEED_DATABASE);
    }

    @Test
    void seedTest_connectsToTheSameContainerAsDefaultTests() {
        String hostname = jdbc.sql("SELECT @@hostname").query(String.class).single();
        assertThat(SharedMySql.INSTANCE.getContainerId()).startsWith(hostname);
    }

    @Test
    void seedTest_hasItsOwnFlywayHistory() {
        Long applied = jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1")
                .query(Long.class).single();
        assertThat(applied).isEqualTo(1L);
    }
}
