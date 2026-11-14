package com.edumy.commerce.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code edumy.payment.*} of the commerce module. {@code webhookSecret} is the HMAC-SHA256 key of the fake provider's
 * webhook ({@code POST /webhooks/payments/fake}); it has NO default and is set only in {@code application-dev.yml} /
 * {@code application-test.yml} (blank = every webhook is refused). {@code qrTtl} = QR validity, 15 minutes (BR-13).
 */
@ConfigurationProperties("edumy.payment")
public record PaymentProperties(
        @DefaultValue("fake") String provider,
        String webhookSecret,
        @DefaultValue("15m") Duration qrTtl) {

    @Override
    public String toString() {
        return "PaymentProperties[provider=" + provider + ", webhookSecret=****, qrTtl=" + qrTtl + "]"; // NFR-08
    }
}
