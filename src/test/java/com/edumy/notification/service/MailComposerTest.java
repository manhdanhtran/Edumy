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
    void coInstructorInvited_vietnameseSubject_namesCourseAndInvitationsLink_UCI13() {
        MailMessage mail = composer.coInstructorInvited("gv@edumy.test", "Trần Thị B", "Nguyễn Văn A",
                "Lập trình Java");

        assertThat(mail.template()).isEqualTo("co-instructor-invited");
        assertThat(mail.to()).isEqualTo("gv@edumy.test");
        assertThat(mail.subject()).isEqualTo("Lời mời làm đồng giảng viên khóa học trên Edumy");
        assertThat(mail.htmlBody())
                .contains("Xin chào Trần Thị B,")
                .contains("<strong>Nguyễn Văn A</strong>")
                .contains("“Lập trình Java”")
                .contains("href=\"https://app.edumy.vn/instructor/invitations\"")
                .doesNotContain("${")
                .doesNotContain("edumy.vn//");
    }

    @Test
    void coInstructorInvited_hostileInviterAndCourseTitle_htmlEscaped() {
        MailMessage mail = composer.coInstructorInvited("gv@edumy.test", "B", HOSTILE_NAME, HOSTILE_NAME);

        assertThat(mail.htmlBody())
                .contains("<strong>" + ESCAPED_NAME + "</strong>")
                .contains("“" + ESCAPED_NAME + "”")
                .doesNotContain("<script>");
    }

    @Test
    void questionAnswered_vietnameseSubject_namesTitlesAndLearnLink_UCI21_D42() {
        MailMessage mail = composer.questionAnswered("hv@edumy.test", "Lê Văn Hỏi", "Phạm Thị Đáp", "Lỗi cài JDK",
                "Lập trình Java", "lap-trinh-java", 77L);

        assertThat(mail.template()).isEqualTo("question-answered");
        assertThat(mail.to()).isEqualTo("hv@edumy.test");
        assertThat(mail.subject()).isEqualTo("Giảng viên đã trả lời câu hỏi của bạn trên Edumy");
        assertThat(mail.htmlBody())
                .contains("Xin chào Lê Văn Hỏi,")
                .contains("<strong>Phạm Thị Đáp</strong>")
                .contains("“Lỗi cài JDK”")
                .contains("“Lập trình Java”")
                .contains("href=\"https://app.edumy.vn/courses/lap-trinh-java/learn?question=77\"")
                .doesNotContain("${");
    }

    @Test
    void questionAnswered_hostileNamesAndTitles_htmlEscaped() {
        MailMessage mail = composer.questionAnswered("a@edumy.test", "B", HOSTILE_NAME, HOSTILE_NAME, HOSTILE_NAME,
                "k", 1L);

        assertThat(mail.htmlBody())
                .contains("<strong>" + ESCAPED_NAME + "</strong>")
                .contains("“" + ESCAPED_NAME + "”")
                .doesNotContain("<script>");
    }

    @Test
    void courseApproved_vietnameseSubject_titleAndPublicCourseLink_UCA10() {
        MailMessage mail = composer.courseApproved("gv@edumy.test", "Nguyễn Văn A", "Lập trình Java",
                "lap-trinh-java");

        assertThat(mail.template()).isEqualTo("course-approved");
        assertThat(mail.to()).isEqualTo("gv@edumy.test");
        assertThat(mail.subject()).isEqualTo("Khóa học của bạn đã được phê duyệt trên Edumy");
        assertThat(mail.htmlBody())
                .contains("Xin chào Nguyễn Văn A,")
                .contains("“Lập trình Java”")
                .contains("href=\"https://app.edumy.vn/courses/lap-trinh-java\"")
                .doesNotContain("${");
    }

    @Test
    void courseRejected_reasonAndInstructorCourseLink_UCA11_D16() {
        MailMessage mail = composer.courseRejected("gv@edumy.test", "Nguyễn Văn A", "Lập trình Java", 12L,
                "Thiếu mô tả chi tiết");

        assertThat(mail.template()).isEqualTo("course-rejected");
        assertThat(mail.subject()).isEqualTo("Khóa học của bạn chưa được phê duyệt trên Edumy");
        assertThat(mail.htmlBody())
                .contains("“Lập trình Java”")
                .contains(">Thiếu mô tả chi tiết</blockquote>")
                .contains("href=\"https://app.edumy.vn/instructor/courses/12\"")
                .doesNotContain("${");
    }

    @Test
    void courseHidden_reasonAndInstructorCourseLink_UCA12() {
        MailMessage mail = composer.courseHidden("gv@edumy.test", "Nguyễn Văn A", "Lập trình Java", 12L,
                "Vi phạm bản quyền");

        assertThat(mail.template()).isEqualTo("course-hidden");
        assertThat(mail.subject()).isEqualTo("Khóa học của bạn đã bị ẩn trên Edumy");
        assertThat(mail.htmlBody())
                .contains("“Lập trình Java”")
                .contains(">Vi phạm bản quyền</blockquote>")
                .contains("Học viên đã ghi danh vẫn tiếp tục học")
                .contains("href=\"https://app.edumy.vn/instructor/courses/12\"")
                .doesNotContain("${");
    }

    @Test
    void courseRejectedAndHidden_hostileReasonAndTitle_htmlEscaped() {
        for (MailMessage mail : new MailMessage[] {
                composer.courseRejected("a@edumy.test", "A", HOSTILE_NAME, 1L, HOSTILE_NAME),
                composer.courseHidden("a@edumy.test", "A", HOSTILE_NAME, 1L, HOSTILE_NAME)}) {
            assertThat(mail.htmlBody()).as(mail.template())
                    .contains("“" + ESCAPED_NAME + "”")
                    .contains(">" + ESCAPED_NAME + "</blockquote>")
                    .doesNotContain("<script>");
        }
    }

    @Test
    void everyTemplate_hostileFullName_htmlEscaped() {
        for (MailMessage mail : new MailMessage[] {
                composer.emailVerification("a@edumy.test", HOSTILE_NAME, CODE),
                composer.passwordReset("a@edumy.test", HOSTILE_NAME, CODE),
                composer.passwordChanged("a@edumy.test", HOSTILE_NAME, Instant.EPOCH),
                composer.coInstructorInvited("a@edumy.test", HOSTILE_NAME, "A", "K"),
                composer.courseApproved("a@edumy.test", HOSTILE_NAME, "K", "k"),
                composer.courseRejected("a@edumy.test", HOSTILE_NAME, "K", 1L, "R"),
                composer.courseHidden("a@edumy.test", HOSTILE_NAME, "K", 1L, "R"),
                composer.questionAnswered("a@edumy.test", HOSTILE_NAME, "G", "Q", "K", "k", 1L)}) {
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
