package com.edumy.support;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Points the datasource at {@link SharedMySql#SEED_DATABASE} in the shared container. Deliberately does NOT import
 * {@link TestcontainersConfiguration}: two {@code @ServiceConnection}/JdbcConnectionDetails beans would clash.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SeedMySqlConfiguration {

    @Bean
    JdbcConnectionDetails seedJdbcConnectionDetails() {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return SharedMySql.INSTANCE.getUsername();
            }

            @Override
            public String getPassword() {
                return SharedMySql.INSTANCE.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return SharedMySql.seedJdbcUrl();
            }
        };
    }
}
