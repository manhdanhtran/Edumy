package com.edumy.shared.error;

import java.time.Duration;
import java.util.Objects;

/**
 * A {@link BusinessException} (normally a 429 code) that also tells the client when to try again:
 * {@link GlobalExceptionHandler} adds the {@code Retry-After} header (RFC 9110 §10.2.3, delay in whole seconds) to the
 * usual ProblemDetail. Used by the abuse limiters (docs/ai/04 §4.4), from services and, through
 * {@link ProblemResponder}, from filters.
 */
public class RetryAfterException extends BusinessException {

    private final Duration retryAfter;

    public RetryAfterException(ErrorCode code, Duration retryAfter) {
        super(code);
        Objects.requireNonNull(retryAfter, "retryAfter");
        this.retryAfter = retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** {@code Retry-After} value: the delay rounded up to whole seconds, at least 1 (never "retry now"). */
    public long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
