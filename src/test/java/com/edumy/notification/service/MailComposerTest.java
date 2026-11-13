package com.edumy.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import com.edumy.notification.integration.MailMessage;
import com.edumy.shared.config.EdumyProperties;
import org.junit.jupiter.api.Test;

/** Mail templates (Q4): subjects, links (Q5), HTML escaping and fail-fast placeholders. */
class MailComposerTest {

    static final String CODE = "Ab+c/d=e&f_G-h"; // characters a URL must encode (real codes are base64url)
    static final String ENCODED_CODE = "Ab%2Bc%2Fd%3De%26f_G-h";
    static final String HOSTILE_NAME = "<script>alert(1)</script> & \"x\"";
    static final String ESCAPED_NAME = "&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;";

    final MailComposer composer = composer("https://app.edumy.vn/");

    @Test
    void emailVerification_vietnameseSubject_linkWithEncodedCode_24h_UCG05_UCG06() {
        MailMessage mail = composer.emailVerification("a@edumy.test", "Nguyễn Văn A", CODE);

        assertThat(mail.template()).isEqualTo("verify-email");
        assertThat(mail.to()).isEqualTo("a@edumy.test");
        assertThat(mail.subject()).isEqualTo("Xác thực địa chỉ email tài khoản Edumy");
        assertThat(mail.htmlBody())
                .contains("href=\"https://app.edumy.vn/verify-email?token=" + ENCODED_CODE + "\"")
                .contains("Xin chào Nguyễn Văn A,")
                .contains("24 giờ")
                .doesNotContain(CODE)
                .doesNotContain("${")
                .doesNotContain("edumy.vn//");
    }

    @Test
    void passwordReset_vietnameseSubject_linkWithEncodedCode_30min_UCG08() {
        MailMessage mail = composer.passwordReset("a@edumy.test", "Nguyễn Văn A", CODE);

        assertThat(mail.template()).isEqualTo("password-reset");
        assertThat(mail.subject()).isEqualTo("Đặt lại mật khẩu tài khoản Edumy");
        assertThat(mail.htmlBody())
                .contains("href=\"https://app.edumy.vn/reset-password?token=" + ENCODED_CODE + "\"")
                .contains("30 phút")
                .doesNotContain(CODE)
                .doesNotContain("${");
    }

    @Test
    void passwordChanged_noticeWithVietnamTime_noLinkWithCode_UCG08_UCG12() {
        MailMessage mail = composer.passwordChanged("a@edumy.test", "Nguyễn Văn A",
                Instant.parse("2026-11-13T03:05:00Z"));

        assertThat(mail.template()).isEqualTo("password-changed");
        assertThat(mail.subject()).isEqualTo("Mật khẩu tài khoản Edumy đã được thay đổi");
        assertThat(mail.htmlBody())
                .contains("10:05 ngày 13/11/2026 (giờ Việt Nam)")
                .doesNotContain("token=")
                .doesNotContain("${");
    }

    @Test
    void everyTemplate_hostileFullName_htmlEscaped() {
        for (MailMessage mail : new MailMessage[] {
                composer.emailVerification("a@edumy.test", HOSTILE_NAME, CODE),
                composer.passwordReset("a@edumy.test", HOSTILE_NAME, CODE),
                composer.passwordChanged("a@edumy.test", HOSTILE_NAME, Instant.EPOCH)}) {
            assertThat(mail.htmlBody()).as(mail.template())
                    .contains("Xin chào " + ESCAPED_NAME + ",")
                    .doesNotContain("<script>")
                    .doesNotContain("\"x\"");
        }
    }

    @Test
    void fullNameContainingPlaceholder_notExpandedAgain() {
        MailMessage mail = composer.emailVerification("a@edumy.test", "${verifyUrl}", CODE);

        assertThat(mail.htmlBody()).contains("Xin chào ${verifyUrl},");
    }

    @Test
    void baseUrlTrailingSlashes_stripped() {
        MailMessage mail = composer("http://localhost:5173//").passwordReset("a@edumy.test", "A", "abc");

        assertThat(mail.htmlBody()).contains("href=\"http://localhost:5173/reset-password?token=abc\"");
    }

    @Test
    void fill_placeholderWithoutValue_throwsWithoutValues() {
        assertThatThrownBy(() -> MailComposer.fill("t", "<p>${fullName} ${code}</p>",
                Map.of("fullName", "secret-name")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("${code}")
                .hasMessageNotContaining("secret-name");
    }

    @Test
    void fill_valueWithoutPlaceholder_throws() {
        assertThatThrownBy(() -> MailComposer.fill("t", "<p>${fullName}</p>",
                Map.of("fullName", "A", "code", "secret-code")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code")
                .hasMessageNotContaining("secret-code");
    }

    @Test
    void fill_malformedPlaceholder_throws() {
        assertThatThrownBy(() -> MailComposer.fill("t", "<p>${fullName} ${ broken }</p>", Map.of("fullName", "A")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void fill_escapesEveryValue() {
        assertThat(MailComposer.fill("t", "<a href=\"${url}\">${name}</a>",
                Map.of("url", "https://x/?a=1&b=\"2\"", "name", "<b>")))
                .isEqualTo("<a href=\"https://x/?a=1&amp;b=&quot;2&quot;\">&lt;b&gt;</a>");
    }

    @Test
    void mailMessageToString_hidesBodyAndRecipient_NFR08() {
        MailMessage mail = composer.passwordReset("a@edumy.test", "A", CODE);

        assertThat(mail.toString()).doesNotContain(ENCODED_CODE).doesNotContain(CODE).doesNotContain("a@edumy.test")
                .contains("password-reset");
    }

    private static MailComposer composer(String baseUrl) {
        return new MailComposer(new EdumyProperties(null, null, null, null,
                new EdumyProperties.Frontend(baseUrl), new EdumyProperties.Mail("no-reply@edumy.test")));
    }
}
