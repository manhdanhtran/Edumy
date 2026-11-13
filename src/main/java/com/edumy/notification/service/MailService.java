package com.edumy.notification.service;

import java.time.Instant;

import com.edumy.notification.integration.MailGateway;
import org.springframework.stereotype.Service;

/**
 * Composes a mail ({@link MailComposer}) and hands it to the {@link MailGateway}. No database, so no transaction.
 * Exceptions propagate to the caller (the mail listeners), which log and swallow them (BR-20).
 */
@Service
public class MailService {

    private final MailComposer composer;
    private final MailGateway gateway;

    public MailService(MailComposer composer, MailGateway gateway) {
        this.composer = composer;
        this.gateway = gateway;
    }

    /** UC-G05 / UC-G06: verification link (24 h code). */
    public void sendEmailVerification(String to, String fullName, String rawCode) {
        gateway.send(composer.emailVerification(to, fullName, rawCode));
    }

    /** UC-G08: password-reset link (30 min code). */
    public void sendPasswordReset(String to, String fullName, String rawCode) {
        gateway.send(composer.passwordReset(to, fullName, rawCode));
    }

    /** UC-G08 step 6 / UC-G12: "your password was changed" notice. */
    public void sendPasswordChanged(String to, String fullName, Instant changedAt) {
        gateway.send(composer.passwordChanged(to, fullName, changedAt));
    }
}
