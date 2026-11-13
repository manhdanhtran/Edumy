package com.edumy.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Part of {@link MySqlSpringBootTest}: after every test the {@link CapturingMailGateway} of the shared context forgets
 * its mails and armed failures, so nothing leaks into the next test.
 */
public class CapturingMailGatewayResetExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context).getBean(CapturingMailGateway.class).reset();
    }
}
