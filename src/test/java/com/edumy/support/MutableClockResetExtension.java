package com.edumy.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Part of {@link MySqlSpringBootTest}: after every test (after the test's own {@code @AfterEach} methods) the
 * {@link MutableClock} of the shared context is reset to the real time.
 */
public class MutableClockResetExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context).getBean(MutableClock.class).reset();
    }
}
