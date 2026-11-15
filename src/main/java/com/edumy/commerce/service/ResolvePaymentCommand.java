package com.edumy.commerce.service;

/**
 * UC-A18 input (SC-M4: services take commands): {@code action} is a {@code handled_action} code already validated by
 * the controller ({@code activated|refund_outside}); {@code note} is required (E3), trimmed by the entity.
 */
public record ResolvePaymentCommand(Long adminId, Long paymentId, String action, String note) {
}
