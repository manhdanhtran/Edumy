package com.edumy.identity.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** NFR-08: request records never print passwords or emailed codes; emails are stripped before validation. */
class IdentityRequestsTest {

    private static final String SECRET = "S3cretValue-xyz";

    @Test
    void toString_masksPasswordsAndCodes_NFR08() {
        assertThat(new RegisterRequest("a@edumy.vn", SECRET, "A").toString()).doesNotContain(SECRET);
        assertThat(new VerifyEmailRequest(SECRET).toString()).doesNotContain(SECRET);
        assertThat(new ResetPasswordRequest(SECRET, SECRET + "2").toString()).doesNotContain(SECRET);
        assertThat(new ChangePasswordRequest(SECRET, SECRET + "2").toString()).doesNotContain(SECRET);
    }

    @Test
    void email_isStripped() {
        assertThat(new RegisterRequest("  A@Edumy.vn ", SECRET, "A").email()).isEqualTo("A@Edumy.vn");
        assertThat(new ForgotPasswordRequest(" a@edumy.vn\t").email()).isEqualTo("a@edumy.vn");
        assertThat(new ForgotPasswordRequest(null).email()).isNull();
    }
}
