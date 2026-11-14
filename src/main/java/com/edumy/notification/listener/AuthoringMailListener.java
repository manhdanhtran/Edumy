package com.edumy.notification.listener;

import com.edumy.authoring.api.CoInstructorInvited;
import com.edumy.notification.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails of the authoring flows (SRS §7 mail list), sent asynchronously after the publishing transaction committed
 * ({@code @Async} + {@code @TransactionalEventListener} AFTER_COMMIT, like {@link IdentityMailListener}). So far:
 * "Lời mời cộng tác" (UC-I13). Review result mails (UC-A10…A12) come with their events.
 * <p>UC-I13 E4 / BR-20: a failure (data gone, composing, sending) is caught and logged here and never touches the
 * invitation, which stays valid. The log line carries the event type, the invitee's userId and exception class names
 * only (NFR-08).
 */
@Component
public class AuthoringMailListener {

    private static final Logger log = LoggerFactory.getLogger(AuthoringMailListener.class);

    private final MailService mails;

    public AuthoringMailListener(MailService mails) {
        this.mails = mails;
    }

    /** UC-I13 step 5: the invitation mail to the invited instructor (first invitation or re-invitation, D-18). */
    @Async
    @TransactionalEventListener
    public void on(CoInstructorInvited event) {
        try {
            mails.sendCoInstructorInvited(event.courseId(), event.instructorId(), event.invitedBy());
        } catch (RuntimeException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.error("Mail not sent: event={}, userId={}, error={}, cause={}", "CoInstructorInvited",
                    event.instructorId(), e.getClass().getName(), cause.getClass().getName());
        }
    }
}
