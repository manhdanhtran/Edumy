package com.edumy.support;

import com.edumy.commerce.integration.FakeQrPaymentGateway;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Part of {@link MySqlSpringBootTest}: after every test the {@link FakeQrPaymentGateway} of the shared context forgets
 * its scripted outcomes, call counts and outage, and {@link PaymentTestHooks} its events and armed failure.
 */
public class PaymentTestHooksResetExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        ApplicationContext app = SpringExtension.getApplicationContext(context);
        app.getBean(FakeQrPaymentGateway.class).reset();
        app.getBean(PaymentTestHooks.class).reset();
    }
}
