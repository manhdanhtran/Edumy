package com.edumy.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Wires the JVM-wide {@link SharedMySql} container into a Spring context as the default {@code edumy} database
 * ({@code @ServiceConnection}). Also used by {@code TestEdumyApplication} to run the app locally.
 * {@code destroyMethod = ""}: a closing context must not stop the container the other contexts still use.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return SharedMySql.INSTANCE;
    }
}
