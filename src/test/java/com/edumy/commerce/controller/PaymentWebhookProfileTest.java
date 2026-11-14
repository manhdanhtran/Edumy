package com.edumy.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.edumy.commerce.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

/**
 * "Dev only": {@code PaymentWebhookController} is a bean under the profiles dev and test, and not under any other
 * (production) profile, so the path is a 404 there. A full application context per profile would be too heavy; the
 * {@code @Profile} condition is evaluated by a minimal context that only registers the controller.
 */
class PaymentWebhookProfileTest {

    private static boolean registeredUnder(String... profiles) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.registerBean(PaymentService.class, () -> mock(PaymentService.class));
            context.register(PaymentWebhookController.class);
            context.refresh();
            return context.getBeanNamesForType(PaymentWebhookController.class).length == 1;
        }
    }

    @Test
    void annotatedForDevAndTestOnly() {
        assertThat(PaymentWebhookController.class.getAnnotation(Profile.class).value())
                .containsExactlyInAnyOrder("dev", "test");
    }

    @Test
    void registeredUnderDevAndTest() {
        assertThat(registeredUnder("dev")).isTrue();
        assertThat(registeredUnder("test")).isTrue();
    }

    @Test
    void notRegisteredUnderProdOrWithoutProfile() {
        assertThat(registeredUnder("prod")).isFalse();
        assertThat(registeredUnder("staging", "docker")).isFalse();
        assertThat(registeredUnder()).isFalse();
    }
}
