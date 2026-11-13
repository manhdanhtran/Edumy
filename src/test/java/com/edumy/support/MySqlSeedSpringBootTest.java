package com.edumy.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Full application context + {@code MockMvc} (whole filter chain) on database {@code edumy_seed} of the shared
 * container, Flyway locations {@code db/migration} + {@code db/seed} (dev seed V1000): one test per GET endpoint that
 * reads the dev seed (docs/ai/07). Same helpers as {@link MySqlSpringBootTest} where they make sense:
 * {@link MutableClock} (reset after every test), {@link CapturingMailGateway}, {@link SqlStatementCounter}, plus
 * {@link SeedUserTokens} (bearer tokens for seed users, no write). The datasource comes from
 * {@link SeedMySqlConfiguration} (not {@link TestcontainersConfiguration}: two {@code JdbcConnectionDetails} would
 * clash), exactly like {@link MySqlSeedTest}.
 * <p><b>Read-only — never write.</b> The seed database is shared by every seed test of the JVM and is not rolled back
 * by this annotation: no POST/PUT/PATCH/DELETE that changes data, no {@code TestUsers}/{@code JwtTestTokens} (not
 * registered here on purpose), no {@code /auth/login} (it inserts a {@code refresh_tokens} row). Writes belong in
 * {@link MySqlSpringBootTest} tests on database {@code edumy}.
 * <p>Seed users (db/seed/V1000__dev_seed.sql, password {@code Edumy@123}; ids as constants in {@link SeedUserTokens}):
 * 1 admin (admin@edumy.vn, admin role only) · 2, 3, 4 student + instructor · 5 student (instructor revoked, D-04) ·
 * 6–17 students · 18, 19 email not verified · 20 locked. Other seed rows: coverage table at the end of
 * docs/ai/progress.md.
 * <p>All tests with this annotation share one cached context (the third one of the suite): never add
 * {@code @MockitoBean}, {@code @TestPropertySource}, {@code @DirtiesContext} or {@code @Import} to a test class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SeedMySqlConfiguration.class, MutableClock.class, CapturingMailGateway.class, SqlStatementCounter.class,
        SeedUserTokens.class})
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
@ExtendWith({MutableClockResetExtension.class, CapturingMailGatewayResetExtension.class})
public @interface MySqlSeedSpringBootTest {
}
