package com.edumy.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Like {@link MySqlJdbcTest} but on database {@code edumy_seed} of the same container, with Flyway locations
 * {@code db/migration} + {@code db/seed} (dev seed V1000+). Required for any test that reads seeded data: running the
 * seed in the default database would make Flyway validation fail ("applied migration not resolved locally") in every
 * context that lacks {@code db/seed}. Never use the default database for seed tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(SeedMySqlConfiguration.class)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
public @interface MySqlSeedTest {
}
