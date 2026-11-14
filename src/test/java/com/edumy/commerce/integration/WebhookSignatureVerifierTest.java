package com.edumy.commerce.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.edumy.commerce.config.PaymentProperties;
import org.junit.jupiter.api.Test;

/** HMAC-SHA256 check of the fake provider webhook. Reference vector: RFC 4231, test case 2. */
class WebhookSignatureVerifierTest {

    static final byte[] BODY = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
    static final String RFC4231_CASE2 = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";

    private static WebhookSignatureVerifier verifier(String secret) {
        return new WebhookSignatureVerifier(new PaymentProperties("fake", secret, Duration.ofMinutes(15)));
    }

    @Test
    void knownVector_verifies_lowerAndUpperCase() {
        assertThat(verifier("Jefe").verify(BODY, RFC4231_CASE2)).isTrue();
        assertThat(verifier("Jefe").verify(BODY, RFC4231_CASE2.toUpperCase())).isTrue();
        assertThat(verifier("Jefe").verify(BODY, "  " + RFC4231_CASE2 + " ")).isTrue();
    }

    @Test
    void wrongKeyOrTamperedBodyOrMalformedSignature_false() {
        assertThat(verifier("other").verify(BODY, RFC4231_CASE2)).isFalse();
        assertThat(verifier("Jefe").verify("what do ya want for nothing".getBytes(StandardCharsets.UTF_8),
                RFC4231_CASE2)).isFalse();
        assertThat(verifier("Jefe").verify(BODY, RFC4231_CASE2.substring(2))).isFalse();
        assertThat(verifier("Jefe").verify(BODY, "zz" + RFC4231_CASE2.substring(2))).isFalse();
        assertThat(verifier("Jefe").verify(BODY, "")).isFalse();
        assertThat(verifier("Jefe").verify(BODY, null)).isFalse();
        assertThat(verifier("Jefe").verify(null, RFC4231_CASE2)).isFalse();
    }

    @Test
    void blankSecret_failsClosed() {
        assertThat(verifier(null).verify(BODY, RFC4231_CASE2)).isFalse();
        assertThat(verifier("  ").verify(BODY, RFC4231_CASE2)).isFalse();
    }

    @Test
    void propertiesToString_hidesTheSecret() {
        assertThat(new PaymentProperties("fake", "s3cr3t", Duration.ofMinutes(15)).toString())
                .doesNotContain("s3cr3t");
    }
}
