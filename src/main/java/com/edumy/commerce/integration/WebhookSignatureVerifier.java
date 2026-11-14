package com.edumy.commerce.integration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.edumy.commerce.config.PaymentProperties;
import org.springframework.stereotype.Component;

/**
 * Checks the {@code X-Signature} header of a payment webhook: lower- or upper-case hex of HMAC-SHA256 over the RAW
 * request body with {@code edumy.payment.webhook-secret}. The comparison is constant-time. A blank secret, a missing or
 * malformed signature never verifies (fail closed). Neither the secret nor the signature is ever logged (NFR-08).
 */
@Component
public class WebhookSignatureVerifier {

    public static final String HEADER = "X-Signature";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public WebhookSignatureVerifier(PaymentProperties properties) {
        String configured = properties.webhookSecret();
        this.secret = configured == null || configured.isBlank() ? new byte[0]
                : configured.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code true} only when {@code signature} is the HMAC of {@code body}. */
    public boolean verify(byte[] body, String signature) {
        if (secret.length == 0 || body == null || signature == null) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(body), provided);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
