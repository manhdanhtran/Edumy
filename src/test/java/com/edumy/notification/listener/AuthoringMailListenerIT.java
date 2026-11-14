package com.edumy.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import com.edumy.notification.integration.MailMessage;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CapturingMailGateway;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authoring mails end to end: review results (UC-A10…A12, SRS §7 "Kết quả duyệt, từ chối, ẩn khóa") to the main
 * instructor, and the invitation mail (UC-I13 step 5, SRS §7 "Lời mời cộng tác"): HTTP invite → {@code CoInstructorInvited} →
 * {@link AuthoringMailListener} (async, after commit) → names / course title through the owners' {@code api/} → real
 * {@code MailComposer} → {@link CapturingMailGateway}. A mail failure leaves the invitation valid (E4); nothing
 * personal reaches the log (NFR-08).
 */
@MySqlSpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class AuthoringMailListenerIT {

    static final String BASE_URL = "http://localhost:5173"; // edumy.frontend.base-url default
    static final String TEMPLATE = "co-instructor-invited";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CapturingMailGateway mails;

    CourseFixtures fixtures;
    Session main;
    Session invitee;
    long course;
    String courseTitle;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        main = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        invitee = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        jdbc.sql("UPDATE users SET full_name = 'Nguyễn Văn Chính' WHERE id = ?").param(main.userId()).update();
        jdbc.sql("UPDATE users SET full_name = 'Trần Thị Mời' WHERE id = ?").param(invitee.userId()).update();
        course = fixtures.draftCourse(main.userId());
        courseTitle = jdbc.sql("SELECT title FROM courses WHERE id = ?").param(course).query(String.class).single();
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    @Test
    void invite_invitationMailToInvitee_namesCourseAndLink_UCI13() throws Exception {
        invite();

        MailMessage mail = mails.awaitMail(invitee.user().email(), TEMPLATE);

        assertThat(mail.subject()).isEqualTo("Lời mời làm đồng giảng viên khóa học trên Edumy");
        assertThat(mail.htmlBody())
                .contains("Xin chào Trần Thị Mời,")
                .contains("<strong>Nguyễn Văn Chính</strong>")
                .contains("“" + courseTitle + "”")
                .contains("href=\"" + BASE_URL + "/instructor/invitations\"");
        assertThat(mails.sentTo(main.user().email())).isEmpty();
    }

    @Test
    void reinviteAfterDecline_secondMail_D18() throws Exception {
        invite();
        mails.awaitMail(invitee.user().email(), TEMPLATE);
        long id = jdbc.sql("SELECT id FROM course_co_instructors WHERE course_id = ?").param(course)
                .query(Long.class).single();
        mvc.perform(post("/api/v1/instructor/invitations/" + id + "/decline")
                        .header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isNoContent());

        invite();

        assertThat(mails.awaitMails(invitee.user().email(), TEMPLATE, 2)).hasSize(2);
    }

    @Test
    void mailFails_invitationStaysPending_logHasNoAddress_UCI13E4_NFR08(CapturedOutput output) throws Exception {
        mails.failNextSendsTo(invitee.user().email(), 1);

        invite();

        mails.awaitFailuresConsumed(invitee.user().email());
        String failureLine = "Mail not sent: event=CoInstructorInvited, userId=" + invitee.userId() + ",";
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(failureLine));
        assertThat(jdbc.sql("SELECT invitation_status FROM course_co_instructors WHERE course_id = ?").param(course)
                .query(String.class).single()).isEqualTo("pending");
        assertThat(mails.sentTo(invitee.user().email())).isEmpty();
        List<String> failureLines = output.getAll().lines().filter(line -> line.contains(failureLine)).toList();
        assertThat(failureLines).isNotEmpty().allSatisfy(line -> assertThat(line)
                .doesNotContain("@")
                .doesNotContain("Simulated SMTP failure"));
    }

    // -------------------------------------------------------------------- review results (SRS §7, UC-A10…A12)

    @Test
    void approve_resultMailToMainInstructor_publicCourseLink_UCA10() throws Exception {
        fixtures.markPendingReview(course);

        moderate("approve", null);

        MailMessage mail = mails.awaitMail(main.user().email(), "course-approved");
        assertThat(mail.subject()).isEqualTo("Khóa học của bạn đã được phê duyệt trên Edumy");
        assertThat(mail.htmlBody())
                .contains("Xin chào Nguyễn Văn Chính,")
                .contains("“" + courseTitle + "”")
                .contains("href=\"" + BASE_URL + "/courses/" + slug() + "\"");
    }

    @Test
    void reject_mailWithReasonToMainInstructor_UCA11_D16() throws Exception {
        fixtures.markPendingReview(course);

        moderate("reject", "{\"reason\": \"Thiếu mô tả <chi tiết>\"}");

        MailMessage mail = mails.awaitMail(main.user().email(), "course-rejected");
        assertThat(mail.subject()).isEqualTo("Khóa học của bạn chưa được phê duyệt trên Edumy");
        assertThat(mail.htmlBody())
                .contains("“" + courseTitle + "”")
                .contains(">Thiếu mô tả &lt;chi tiết&gt;</blockquote>")
                .contains("href=\"" + BASE_URL + "/instructor/courses/" + course + "\"");
    }

    @Test
    void hide_mailWithReasonToMainInstructor_UCA12() throws Exception {
        fixtures.setStatus(course, "published", false);

        moderate("hide", "{\"reason\": \"Vi phạm bản quyền\"}");

        MailMessage mail = mails.awaitMail(main.user().email(), "course-hidden");
        assertThat(mail.subject()).isEqualTo("Khóa học của bạn đã bị ẩn trên Edumy");
        assertThat(mail.htmlBody())
                .contains(">Vi phạm bản quyền</blockquote>")
                .contains("href=\"" + BASE_URL + "/instructor/courses/" + course + "\"");
    }

    /** UC-A10 E3: a mail failure does not undo the approval; the log names the event and the course only. */
    @Test
    void approveMailFails_courseStaysPublished_logHasNoAddress_UCA10E3_NFR08(CapturedOutput output) throws Exception {
        fixtures.markPendingReview(course);
        mails.failNextSendsTo(main.user().email(), 1);

        moderate("approve", null);

        mails.awaitFailuresConsumed(main.user().email());
        String failureLine = "Mail not sent: event=CourseApproved, courseId=" + course + ",";
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(failureLine));
        assertThat(jdbc.sql("SELECT status FROM courses WHERE id = ?").param(course).query(String.class).single())
                .isEqualTo("published");
        assertThat(output.getAll().lines().filter(line -> line.contains(failureLine)).toList())
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).doesNotContain("@"));
    }

    private void moderate(String action, String body) throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        var request = post("/api/v1/admin/courses/" + course + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, admin.bearer());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    private String slug() {
        return jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();
    }

    private void invite() throws Exception {
        mvc.perform(post("/api/v1/instructor/courses/" + course + "/co-instructors")
                        .header(HttpHeaders.AUTHORIZATION, main.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + invitee.user().email() + "\"}"))
                .andExpect(status().isCreated());
    }
}
