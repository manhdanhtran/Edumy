package com.edumy.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** NFR-08: the raw emailed code travels in the event (needed for the link) but never shows in toString / logs. */
class IdentityEventsTest {

    private static final String RAW = "rAw-C0de_9f8e7d6c5b4a3210fedcba9876543210abcdefg";

    @Test
    void userRegistered_toStringMasksCode_NFR08() {
        UserRegistered event = new UserRegistered(7L, "a@example.com", "Nguyễn Văn A", RAW);

        assertThat(event.rawVerificationCode()).isEqualTo(RAW);
        assertThat(event.toString()).doesNotContain(RAW).contains("userId=7").contains("****");
    }

    @Test
    void emailVerificationRequested_toStringMasksCode_NFR08() {
        EmailVerificationRequested event = new EmailVerificationRequested(7L, "a@example.com", "A", RAW);

        assertThat(event.rawCode()).isEqualTo(RAW);
        assertThat(event.toString()).doesNotContain(RAW).contains("****");
    }

    @Test
    void passwordResetRequested_toStringMasksCode_NFR08() {
        PasswordResetRequested event = new PasswordResetRequested(7L, "a@example.com", "A", RAW);

        assertThat(event.rawCode()).isEqualTo(RAW);
        assertThat(event.toString()).doesNotContain(RAW).contains("****");
    }

    @Test
    void passwordChanged_carriesNoSecret() {
        Instant at = Instant.parse("2026-11-13T03:00:00Z");
        PasswordChanged event = new PasswordChanged(7L, "a@example.com", "A", at);

        assertThat(event.occurredAt()).isEqualTo(at);
        assertThat(event.toString()).contains("userId=7");
    }
}
