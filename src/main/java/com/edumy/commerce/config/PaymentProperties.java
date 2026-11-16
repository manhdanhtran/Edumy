package com.edumy.commerce.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code edumy.payment.*} of the commerce module.
 * <ul>
 *   <li>{@code provider}: informative; the gateway bean is chosen by profile ({@code payos} = real adapter, later;
 *   anything else = {@code FakeQrPaymentGateway}).</li>
 *   <li>{@code webhookSecret}: HMAC-SHA256 key of the fake provider's webhook ({@code POST /webhooks/payments/fake});
 *   NO default, set only in {@code application-dev.yml} / {@code application-test.yml} (blank = every webhook is
 *   refused).</li>
 *   <li>{@code qrTtl}: QR validity, 15 minutes (BR-13). {@code orderTtl}: a pending order is cancelled after 24 hours
 *   (BR-29, AC-22).</li>
 *   <li>{@code txTimeout}: timeout of the transactions that call the provider while they hold an order lock (ADR-011);
 *   {@code connectTimeout} / {@code readTimeout}: HTTP timeouts a real adapter must use (default 2 s + 3 s; their sum
 *   must stay below {@code txTimeout}, checked at start-up); the fake ignores them.</li>
 * </ul>
 */
@ConfigurationProperties("edumy.payment")
public record PaymentProperties(
        @DefaultValue("fake") String provider,
        String webhookSecret,
        @DefaultValue("15m") Duration qrTtl,
        @DefaultValue("24h") Duration orderTtl,
        @DefaultValue("10s") Duration txTimeout,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout) {

    /**
     * Review P11-R-06: a provider call made while an order lock is held must end well inside the transaction timeout, so
     * {@code connectTimeout + readTimeout} has to be strictly below {@code txTimeout}; the application refuses to start
     * otherwise.
     */
    public PaymentProperties {
        if (connectTimeout != null && readTimeout != null && txTimeout != null
                && connectTimeout.plus(readTimeout).compareTo(txTimeout) >= 0) {
            throw new IllegalArgumentException("edumy.payment: connect-timeout + read-timeout must be below tx-timeout");
        }
    }

    /** Defaults for everything but the provider and the secret (unit tests). */
    public static PaymentProperties of(String provider, String webhookSecret) {
        return new PaymentProperties(provider, webhookSecret, Duration.ofMinutes(15), Duration.ofHours(24),
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    /** {@link #txTimeout} in whole seconds, at least 1 (Spring's transaction timeout unit). */
    public int txTimeoutSeconds() {
        return (int) Math.max(1, txTimeout.toSeconds());
    }

    @Override
    public String toString() {
        return "PaymentProperties[provider=" + provider + ", webhookSecret=****, qrTtl=" + qrTtl + ", orderTtl="
                + orderTtl + ", txTimeout=" + txTimeout + ", connectTimeout=" + connectTimeout + ", readTimeout="
                + readTimeout + "]"; // NFR-08
    }
}
