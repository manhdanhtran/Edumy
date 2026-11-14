package com.edumy.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * UC-A18 {@code POST /admin/payments/{id}/resolve}: {@code action} = {@code activated} (a) or {@code refund_outside}
 * (b); {@code note} is required (step 3, E3, D-29), trimmed, stored in {@code handled_note} (TEXT: ≤16 383 characters
 * fit whatever the characters, the SRS names no smaller limit).
 */
public record ResolvePaymentRequest(
        @NotBlank @Pattern(regexp = "activated|refund_outside") String action,
        @NotBlank @Size(max = 16_383) String note) {
}
