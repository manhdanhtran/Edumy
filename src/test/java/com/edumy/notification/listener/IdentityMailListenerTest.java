package com.edumy.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import com.edumy.identity.api.EmailVerificationRequested;
import com.edumy.identity.api.PasswordChanged;
import com.edumy.identity.api.PasswordResetRequested;
import com.edumy.identity.api.UserRegistered;
import com.edumy.notification.service.MailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;

/** Event → mail mapping; failures are swallowed and logged with event type + userId only (BR-20, NFR-08). */
@ExtendWith(OutputCaptureExtension.class)
class IdentityMailListenerTest {

    static final String EMAIL = "hoc.vien@edumy.test";
    static final String CODE = "raw-code-123";

    final MailService mails = mock(MailService.class);
    final IdentityMailListener listener = new IdentityMailListener(mails);

    @Test
    void eachEvent_sendsItsMail_UCG05_UCG06_UCG08_UCG12() {
        Instant at = Instant.parse("2026-11-13T00:00:00Z");

        listener.on(new UserRegistered(1L, EMAIL, "A", CODE));
        listener.on(new EmailVerificationRequested(2L, EMAIL, "B", "c2"));
        listener.on(new PasswordResetRequested(3L, EMAIL, "C", "c3"));
        listener.on(new PasswordChanged(4L, EMAIL, "D", at));

        verify(mails).sendEmailVerification(EMAIL, "A", CODE);
        verify(mails).sendEmailVerification(EMAIL, "B", "c2");
        verify(mails).sendPasswordReset(EMAIL, "C", "c3");
        verify(mails).sendPasswordChanged(EMAIL, "D", at);
    }

    @Test
    void sendFails_swallowed_errorLogHasEventAndUserIdOnly_BR20_NFR08(CapturedOutput output) {
        doThrow(new MailSendException("SMTP said no for " + EMAIL + " body " + CODE))
                .when(mails).sendEmailVerification(anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("template broken " + CODE))
                .when(mails).sendPasswordChanged(anyString(), anyString(), any());

        assertThatNoException().isThrownBy(() -> listener.on(new UserRegistered(42L, EMAIL, "A", CODE)));
        assertThatNoException().isThrownBy(
                () -> listener.on(new PasswordChanged(43L, EMAIL, "A", Instant.EPOCH)));

        assertThat(output.getAll())
                .contains("Mail not sent: event=UserRegistered, userId=42, "
                        + "error=org.springframework.mail.MailSendException")
                .contains("Mail not sent: event=PasswordChanged, userId=43, error=java.lang.IllegalStateException")
                .doesNotContain(EMAIL)
                .doesNotContain(CODE);
    }
}
