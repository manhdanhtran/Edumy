package com.edumy.commerce.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Review P11-R-06: provider timeouts must end inside the transaction timeout. */
class PaymentPropertiesTest {

    static PaymentProperties with(int connect, int read, int tx) {
        return new PaymentProperties("fake", "s", Duration.ofMinutes(15), Duration.ofHours(24), Duration.ofSeconds(tx),
                Duration.ofSeconds(connect), Duration.ofSeconds(read));
    }

    @Test
    void defaults_2sPlus3s_belowThe10sTransaction() {
        PaymentProperties defaults = PaymentProperties.of("fake", "s");
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(defaults.txTimeoutSeconds()).isEqualTo(10);
    }

    @Test
    void sumEqualToOrAboveTheTransactionTimeout_refusedAtStartUp() {
        assertThatIllegalArgumentException().isThrownBy(() -> with(5, 5, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> with(6, 5, 10));
        assertThat(with(4, 5, 10).readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void toString_hidesTheSecret() {
        assertThat(PaymentProperties.of("fake", "s3cr3t").toString()).doesNotContain("s3cr3t");
    }
}