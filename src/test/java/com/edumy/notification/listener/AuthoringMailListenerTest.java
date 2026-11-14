package com.edumy.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.edumy.authoring.api.CoInstructorInvited;
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
