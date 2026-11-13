package com.edumy.notification.integration;

/**
 * Port to the email service (ADR-006). Implementations throw an unchecked exception when the mail cannot be handed
 * over; callers (the mail listeners) catch it, so a mail failure never affects the business transaction (BR-20).
 */
public interface MailGateway {

    void send(MailMessage message);
}
