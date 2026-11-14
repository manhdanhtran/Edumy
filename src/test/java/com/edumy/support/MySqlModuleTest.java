package com.edumy.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Infrastructure of the {@code <Module>ModuleIT} classes (P09-c, docs/ai/07 "Module tests"): the shared MySQL container
 * ({@link TestcontainersConfiguration}, database {@code edumy}, no seed), profile {@code test}, the {@link MutableClock}
 * as the application {@code Clock} (reset after every test), the {@link CapturingMailGateway} as the {@code MailGateway}
 * and the committed-row helpers {@link TestUsers} / {@link CourseFixtures}.
 *
 * <p>It does NOT bootstrap anything by itself: put it next to
 * {@code @ApplicationModuleTest} (which limits the context to the module of the test class' package and, in the default
 * {@code STANDALONE} mode, to that module only), and mock the neighbours' {@code api} interfaces with
 * {@code @MockitoBean} in the class. One module = one cached context; never share a class' mocks across modules.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MutableClock.class, CapturingMailGateway.class, TestUsers.class,
        CourseFixtures.class})
@ExtendWith({MutableClockResetExtension.class, CapturingMailGatewayResetExtension.class})
public @interface MySqlModuleTest {
}
