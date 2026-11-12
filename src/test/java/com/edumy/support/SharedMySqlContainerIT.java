package com.edumy.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Default database {@code edumy}: the shared container, and the seed database next to it (docs/ai/07). */
@MySqlJdbcTest
class SharedMySqlContainerIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void defaultTest_usesDatabaseEdumy() {
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single()).isEqualTo("edumy");
    }

    @Test
    void defaultTest_connectsToTheSharedContainer() {
        String hostname = jdbc.sql("SELECT @@hostname").query(String.class).single();
        assertThat(SharedMySql.INSTANCE.getContainerId()).startsWith(hostname);
    }

    @Test
    void sameServer_hasSeparateSeedDatabase() {
        List<String> schemas = jdbc.sql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA")
                .query(String.class).list();
        assertThat(schemas).contains("edumy", SharedMySql.SEED_DATABASE);
    }
}
