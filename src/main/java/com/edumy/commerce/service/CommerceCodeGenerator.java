package com.edumy.commerce.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

/**
 * Codes of commerce rows (ADR-011, P11 decisions Q5 / R2), from {@link SecureRandom} (not guessable, NFR-01):
 * <ul>
 *   <li>order code {@code ORD-<yyyyMMdd>-<8 characters>} (UTC day, Crockford base32 without I, L, O, U): 21 characters,
 *   fits {@code orders.order_code VARCHAR(30)}, keeps the seed's {@code ORD-<yyyyMMdd>-} prefix;</li>
 *   <li>transaction reference: 15 decimal digits in [10^14, 10^15) — no leading zero, below 2^53, so a provider that
 *   wants a numeric reference (payOS) can take it as a number and the string maps back exactly.</li>
 * </ul>
 * Uniqueness: the callers pre-check, {@code uq_orders_order_code} / {@code uq_payment_ref} decide under concurrency
 * (a collision is practically impossible: 32^8 per day, 9·10^14).
 */
@Component
class CommerceCodeGenerator {

    static final long REFERENCE_MIN = 100_000_000_000_000L;        // 10^14
    static final long REFERENCE_BOUND = 1_000_000_000_000_000L;    // 10^15 (exclusive)
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final RandomGenerator random;

    CommerceCodeGenerator() {
        this(new SecureRandom());
    }

    CommerceCodeGenerator(RandomGenerator random) {
        this.random = random;
    }

    String newOrderCode(Instant now) {
        StringBuilder code = new StringBuilder("ORD-").append(DAY.format(now)).append('-');
        for (int i = 0; i < 8; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    String newTransactionRef() {
        return Long.toString(random.nextLong(REFERENCE_MIN, REFERENCE_BOUND));
    }
}
