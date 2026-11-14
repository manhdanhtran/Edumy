package com.edumy.notification.listener;

import com.edumy.authoring.api.CoInstructorInvited;
import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.notification.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails of the authoring flows (SRS §7 mail list), sent asynchronously after the publishing transaction committed
 * ({@code @Async} + {@code @TransactionalEventListener} AFTER_COMMIT, like {@link IdentityMailListener}):
 * "Lời mời cộng tác" (UC-I13) and "Kết quả duyệt, từ chối, ẩn khóa" to the main instructor (UC-A10, A11, A12).
 * UC-A16 (unhide) sends no mail (SRS §7).
 * <p>UC-I13 E4 / UC-A10 E3 / BR-20: a failure (data gone, composing, sending) is caught and logged here and never
 * touches the invitation or the review decision, already committed. The log line carries the event type, the
 * invitee's userId or the courseId, and exception class names only (NFR-08).
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

    /** UC-A10 step 5: approval result to the main instructor. */
    @Async
    @TransactionalEventListener
    public void on(CourseApproved event) {
        sendForCourse("CourseApproved", event.courseId(), () -> mails.sendCourseApproved(event.courseId()));
    }

    /** UC-A11 / D-16: rejection with its reason to the main instructor. */
    @Async
    @TransactionalEventListener
    public void on(CourseRejected event) {
        sendForCourse("CourseRejected", event.courseId(),
                () -> mails.sendCourseRejected(event.courseId(), event.reason()));
    }

    /** UC-A12 step 6: hiding notice with its reason to the main instructor. */
    @Async
    @TransactionalEventListener
    public void on(CourseHidden event) {
        sendForCourse("CourseHidden", event.courseId(), () -> mails.sendCourseHidden(event.courseId(), event.reason()));
    }

    private static void sendForCourse(String eventName, Long courseId, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.error("Mail not sent: event={}, courseId={}, error={}, cause={}", eventName, courseId,
                    e.getClass().getName(), cause.getClass().getName());
        }
    }
}
