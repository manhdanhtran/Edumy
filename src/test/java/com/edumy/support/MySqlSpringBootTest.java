package com.edumy.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context on the shared MySQL container, database {@code edumy} (migrations only, no dev seed), with
 * {@code MockMvc} (whole filter chain: TraceIdFilter, Spring Security) and the test helpers {@link TestUsers} and
 * {@link JwtTestTokens}. All tests with this annotation share one cached Spring context: never add
 * {@code @MockitoBean}, {@code @TestPropertySource}, {@code @DirtiesContext} or {@code @Import} to a test class.
 * See docs/ai/07-testing.md.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestUsers.class, JwtTestTokens.class})
public @interface MySqlSpringBootTest {
}
