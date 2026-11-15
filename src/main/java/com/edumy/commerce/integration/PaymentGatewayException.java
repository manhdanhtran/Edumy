package com.edumy.commerce.integration;

import java.util.regex.Pattern;

/**
 * The provider could not be reached or refused the call (timeout, 5xx, network). Services answer 503
 * {@code PAYMENT_PROVIDER_UNAVAILABLE} and change nothing (UC-S05 E1, UC-S16 E2); timers keep the row for the next run.
 * <p>NFR-08 (review P11-R-07): an adapter's message may carry URLs, parameters or response bodies, so callers never
 * log {@link #getMessage()}; they log {@link #describe} — the exception type and a short sanitised {@link #code()}
 * (e.g. {@code timeout}, {@code http_503}) that the adapter sets.
 */
public class PaymentGatewayException extends RuntimeException {

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,40}");

    private final String code;

    public PaymentGatewayException(String code) {
        super(code);
        this.code = sanitize(code);
    }

    public PaymentGatewayException(String code, Throwable cause) {
        super(code, cause);
        this.code = sanitize(code);
    }

    /** Short error code chosen by the adapter, or {@code unknown} when it did not fit {@code [A-Za-z0-9_.-]{1,40}}. */
    public String code() {
        return code;
    }

    /** What may be logged: {@code <ExceptionType>[<code>]}, never the message or the cause's message. */
    public static String describe(PaymentGatewayException e) {
        return e.getClass().getSimpleName() + "[" + e.code() + "]";
    }

    private static String sanitize(String code) {
        return code != null && SAFE_CODE.matcher(code).matches() ? code : "unknown";
    }
}
