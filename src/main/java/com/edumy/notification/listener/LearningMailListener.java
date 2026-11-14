package com.edumy.notification.listener;

import com.edumy.learning.api.QuestionAnsweredByInstructor;
import com.edumy.notification.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mails of the learning flows (SRS §7 "Phản hồi hỏi đáp", D-42), sent asynchronously after the answering transaction
 * committed ({@code @Async} + {@code @TransactionalEventListener} AFTER_COMMIT, like {@link AuthoringMailListener}):
 * the asker is told that a course instructor answered (UC-I21 step 5). A student's follow-up publishes no event.
 * <p>UC-I21 E3: a failure (data gone, composing, sending) is caught and logged here and never touches the answer,
 * already committed. The log line carries the event type, the asker's userId and exception class names only (NFR-08).
 */
@Component
public class LearningMailListener {

    private static final Logger log = LoggerFactory.getLogger(LearningMailListener.class);

    private final MailService mails;

    public LearningMailListener(MailService mails) {
        this.mails = mails;
    }

    /** UC-I21 step 5: "an instructor answered your question" to the asker. */
    @Async
    @TransactionalEventListener
    public void on(QuestionAnsweredByInstructor event) {
        try {
            mails.sendQuestionAnswered(event.courseId(), event.questionId(), event.questionTitle(), event.askerId(),
                    event.instructorId());
        } catch (RuntimeException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.error("Mail not sent: event={}, userId={}, error={}, cause={}", "QuestionAnsweredByInstructor",
                    event.askerId(), e.getClass().getName(), cause.getClass().getName());
        }
    }
}
