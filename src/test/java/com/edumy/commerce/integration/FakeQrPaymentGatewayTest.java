package com.edumy.commerce.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.edumy.commerce.config.PaymentProperties;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The in-process provider of dev / tests: QR payload, lookups, webhook signature and parsing, scripted outages. */
class FakeQrPaymentGatewayTest {

    static final String SECRET = "unit-secret";
    static final Instant NOW = Instant.parse("2026-11-15T08:00:00Z");
    static final jakarta.validation.ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    final FakeQrPaymentGateway gateway = new FakeQrPaymentGateway(
            new WebhookSignatureVerifier(PaymentProperties.of("fake", SECRET)), JsonMapper.builder().build(),
            VALIDATION.getValidator(), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterAll
    static void close() {
        VALIDATION.close();
    }

    static Map<String, String> signed(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("x-signature", HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))));
        return headers;
    }

    @Test
    void createQr_payloadCarriesTheTransactionReference_notTheOrderCode() {
        assertThat(gateway.createQr("261115482019377", 500_000, NOW.plusSeconds(900)).qrData())
                .isEqualTo("FAKEQR|EDUMY|261115482019377|500000");
    }

    @Test
    void query_notPaidByDefault_thenScriptedOutcomes_andCounted() {
        assertThat(gateway.query("R1")).isInstanceOf(PaymentLookup.NotPaid.class);
        gateway.simulatePaid("R1", 300_000);
        assertThat(gateway.query("R1")).isEqualTo(new PaymentLookup.Paid(300_000, NOW));
        gateway.simulateFailed("R2");
        assertThat(gateway.query("R2")).isInstanceOf(PaymentLookup.Failed.class);
        assertThat(gateway.queryCount("R1")).isEqualTo(2);
        assertThat(gateway.totalQueries()).isEqualTo(3);
        gateway.reset();
        assertThat(gateway.query("R1")).isInstanceOf(PaymentLookup.NotPaid.class);
        assertThat(gateway.queryCount("R1")).isEqualTo(1);
    }

    @Test
    void unavailable_everyCallThrows_untilReset() {
        gateway.setUnavailable(true);
        assertThatThrownBy(() -> gateway.query("R1")).isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> gateway.createQr("R1", 1, NOW.plusSeconds(1)))
                .isInstanceOf(PaymentGatewayException.class);
        gateway.reset();
        assertThat(gateway.query("R1")).isInstanceOf(PaymentLookup.NotPaid.class);
    }

    @Test
    void verifyWebhook_signedSuccess_parsed_andRememberedForLookups() throws Exception {
        String body = "{\"transactionRef\":\"R9\",\"orderCode\":\"ORD-1\",\"amount\":450000,\"status\":\"success\","
                + "\"paidAt\":\"2026-11-15T07:59:00Z\"}";

        PaymentResult result = gateway.verifyWebhook(body.getBytes(StandardCharsets.UTF_8), signed(body));

        assertThat(result).isEqualTo(new PaymentResult("R9", "ORD-1", 450_000, true,
                Instant.parse("2026-11-15T07:59:00Z")));
        assertThat(gateway.query("R9"))
                .isEqualTo(new PaymentLookup.Paid(450_000, Instant.parse("2026-11-15T07:59:00Z")));
    }

    @Test
    void verifyWebhook_badSignature_401_beforeParsing() {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("X-Signature", "00");
        assertThatThrownBy(() -> gateway.verifyWebhook("not json".getBytes(StandardCharsets.UTF_8), headers))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> gateway.verifyWebhook(new byte[0], Map.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void paymentResult_fromLookup() {
        assertThat(PaymentResult.fromLookup("R", new PaymentLookup.Paid(5, NOW)))
                .isEqualTo(new PaymentResult("R", null, 5, true, NOW));
        assertThat(PaymentResult.fromLookup("R", new PaymentLookup.Failed()).success()).isFalse();
        assertThat(PaymentResult.fromLookup("R", new PaymentLookup.NotPaid())).isNull();
    }
}
