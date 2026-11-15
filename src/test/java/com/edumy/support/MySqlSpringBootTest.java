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

/**
 * Full application context on the shared MySQL container, database {@code edumy} (migrations only, no dev seed), with
 * {@code MockMvc} (whole filter chain: TraceIdFilter, Spring Security), the test helpers {@link TestUsers} and
 * {@link JwtTestTokens}, and the {@link MutableClock} as the application {@code Clock} (reset to the real time after
 * every test by {@link MutableClockResetExtension}), and the {@link CapturingMailGateway} as the application
 * {@code MailGateway} (records sent mails; cleared after every test by {@link CapturingMailGatewayResetExtension}),
 * and the test-only {@link AccessProbeController} ({@code /api/v1/test-probe/**}, one endpoint per access bean),
 * and the {@link SqlStatementCounter} (JDBC statements per thread, for the no-N+1 tests of list endpoints),
 * and {@link CatalogCaches} (empties the four {@code catalog.*} caches before every test),
 * and {@link PaymentTestHooks} (OrderPaid recorder / NFR-04 failure probe; with the fake payment gateway reset after every test).
 * All tests with this annotation share one cached Spring context:
 * never add {@code @MockitoBean}, {@code @TestPropertySource}, {@code @DirtiesContext} or {@code @Import} to a test
 * class.
 * See docs/ai/07-testing.md.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestUsers.class, JwtTestTokens.class, MutableClock.class,
        CapturingMailGateway.class, AccessProbeController.class, SqlStatementCounter.class, PaymentTestHooks.class})
@ExtendWith({MutableClockResetExtension.class, CapturingMailGatewayResetExtension.class, CatalogCaches.class,
        PaymentTestHooksResetExtension.class})
public @interface MySqlSpringBootTest {
}
