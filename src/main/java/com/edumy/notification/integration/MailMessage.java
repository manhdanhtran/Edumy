package com.edumy.notification.integration;

import java.util.Objects;

/**
 * One composed HTML email (UTF-8). {@code template} names the template it was built from (e.g. {@code verify-email}),
 * for logs. {@link #toString()} prints neither the body (it may carry a one-time code, NFR-08) nor the recipient.
 */
public record MailMessage(String template, String to, String subject, String htmlBody) {

    public MailMessage {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(htmlBody, "htmlBody");
    }

    @Override
    public String toString() {
        return "MailMessage[template=" + template + ", to=****, subject=" + subject + ", htmlBody=****]";
    }
}
