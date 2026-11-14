package com.edumy.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.edumy.authoring.api.CoInstructorInvited;
import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.notification.service.MailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;

/** Event → mail mapping; failures are swallowed and logged with event type + userId only (UC-I13 E4, NFR-08). */
@ExtendWith(OutputCaptureExtension.class)
class AuthoringMailListenerTest {

    static final String EMAIL = "giang.vien@edumy.test";

    final MailService mails = mock(MailService.class);
    final AuthoringMailListener listener = new AuthoringMailListener(mails);

    @Test
    void coInstructorInvited_sendsTheInvitationMail_UCI13() {
        listener.on(new CoInstructorInvited(9L, 7L, 5L, 3L));

        verify(mails).sendCoInstructorInvited(7L, 5L, 3L);
    }

    @Test
    void courseApproved_sendsTheApprovalMail_UCA10() {
        listener.on(new CourseApproved(7L, "lap-trinh"));

        verify(mails).sendCourseApproved(7L);
    }

    @Test
    void courseRejected_sendsTheRejectionMailWithReason_UCA11() {
        listener.on(new CourseRejected(7L, "lap-trinh", "Thiếu mô tả"));

        verify(mails).sendCourseRejected(7L, "Thiếu mô tả");
    }

    @Test
    void courseHidden_sendsTheHidingMailWithReason_UCA12() {
        listener.on(new CourseHidden(7L, "lap-trinh", "Vi phạm"));

        verify(mails).sendCourseHidden(7L, "Vi phạm");
    }

    @Test
    void reviewMailFails_swallowed_errorLogHasEventAndCourseIdOnly_UCA10E3_NFR08(CapturedOutput output) {
        doThrow(new MailSendException("SMTP said no for " + EMAIL)).when(mails).sendCourseApproved(anyLong());
        doThrow(new MailSendException("SMTP said no for " + EMAIL))
                .when(mails).sendCourseHidden(anyLong(), anyString());

        assertThatNoException().isThrownBy(() -> listener.on(new CourseApproved(77L, "k")));
        assertThatNoException().isThrownBy(() -> listener.on(new CourseHidden(78L, "k", "Lý do")));

        assertThat(output.getAll())
                .contains("Mail not sent: event=CourseApproved, courseId=77, "
                        + "error=org.springframework.mail.MailSendException")
                .contains("Mail not sent: event=CourseHidden, courseId=78, ")
                .doesNotContain(EMAIL);
    }

    @Test
    void sendFails_swallowed_errorLogHasEventAndUserIdOnly_UCI13E4_NFR08(CapturedOutput output) {
        doThrow(new MailSendException("SMTP said no for " + EMAIL))
                .when(mails).sendCoInstructorInvited(anyLong(), anyLong(), anyLong());

        assertThatNoException().isThrownBy(() -> listener.on(new CoInstructorInvited(9L, 7L, 42L, 3L)));

        assertThat(output.getAll())
                .contains("Mail not sent: event=CoInstructorInvited, userId=42, "
                        + "error=org.springframework.mail.MailSendException")
                .doesNotContain(EMAIL);
    }
}
