package com.edumy.notification.listener;

import com.edumy.identity.api.EmailVerificationRequested;
import com.edumy.identity.api.PasswordChanged;
import com.edumy.identity.api.PasswordResetRequested;
import com.edumy.identity.api.UserRegistered;
import com.edumy.notification.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails of the identity flows (SRS §7 mail list), sent asynchronously after the publishing transaction committed:
 * {@code @Async} + {@code @TransactionalEventListener} (AFTER_COMMIT), i.e. what {@code @ApplicationModuleListener}
 * composes, without its {@code REQUIRES_NEW} transaction (no mail touches the database). The annotation itself lives in
 * {@code spring-modulith-events-api}, which is not a dependency of this project.
 * <p>BR-20 / UC-G05 E3: every failure (composing or sending) is caught and logged here, so it never reaches the
 * business transaction and nothing retries in a loop; the user asks again (resend, forgot password). The log line
 * carries the event type, the userId and exception class names only: never the address, the code or an exception
 * message, which could quote the recipient or the body (NFR-08).
 */
@Component
public class IdentityMailListener {

    private static final Logger log = LoggerFactory.getLogger(IdentityMailListener.class);

    private final MailService mails;

    public IdentityMailListener(MailService mails) {
        this.mails = mails;
    }

    /** UC-G05 step 6: the verification mail (the SRS mail list has no separate welcome mail). */
    @Async
    @TransactionalEventListener
    public void on(UserRegistered event) {
        deliver("UserRegistered", event.userId(),
                () -> mails.sendEmailVerification(event.email(), event.fullName(), event.rawVerificationCode()));
    }

    /** UC-G06 resend. */
    @Async
    @TransactionalEventListener
    public void on(EmailVerificationRequested event) {
        deliver("EmailVerificationRequested", event.userId(),
                () -> mails.sendEmailVerification(event.email(), event.fullName(), event.rawCode()));
    }

    /** UC-G08 step 3. */
    @Async
    @TransactionalEventListener
    public void on(PasswordResetRequested event) {
        deliver("PasswordResetRequested", event.userId(),
                () -> mails.sendPasswordReset(event.email(), event.fullName(), event.rawCode()));
    }

    /** UC-G08 step 6 (reset) and UC-G12 (change). */
    @Async
    @TransactionalEventListener
    public void on(PasswordChanged event) {
        deliver("PasswordChanged", event.userId(),
                () -> mails.sendPasswordChanged(event.email(), event.fullName(), event.occurredAt()));
    }

    private static void deliver(String eventType, Long userId, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.error("Mail not sent: event={}, userId={}, error={}, cause={}", eventType, userId,
                    e.getClass().getName(), cause.getClass().getName());
        }
    }
}
