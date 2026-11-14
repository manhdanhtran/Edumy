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
 * "Phản hồi hỏi đáp" mail end to end (UC-I21 step 5, SRS §7, D-42): HTTP answer by an instructor →
 * {@code QuestionAnsweredByInstructor} → {@link LearningMailListener} (async, after commit) → names / course title through
 * the owners' {@code api/} → real {@code MailComposer} → {@link CapturingMailGateway}. A student's follow-up sends
 * nothing; a mail failure leaves the answer saved (E3); nothing personal reaches the log (NFR-08).
 */
@MySqlSpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LearningMailListenerIT {

    static final String BASE_URL = "http://localhost:5173"; // edumy.frontend.base-url default
    static final String TEMPLATE = "question-answered";

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
    Session student;
    Session instructor;
    long course;
    long question;
    String courseTitle;
    String slug;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        student = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        jdbc.sql("UPDATE users SET full_name = 'Lê Văn Hỏi' WHERE id = ?").param(student.userId()).update();
        jdbc.sql("UPDATE users SET full_name = 'Phạm Thị Đáp' WHERE id = ?").param(instructor.userId()).update();
        course = fixtures.draftCourse(instructor.userId(), fixtures.category(true));
        fixtures.lesson(fixtures.chapter(course));
        fixtures.setStatus(course, "published", false);
        courseTitle = jdbc.sql("SELECT title FROM courses WHERE id = ?").param(course).query(String.class).single();
        slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();
        question = fixtures.question(fixtures.enrollment(student.userId(), course));
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    @Test
    void instructorAnswer_mailToTheAsker_namesTitlesAndLink_UCI21_D42() throws Exception {
        answer(instructor);

        MailMessage mail = mails.awaitMail(student.user().email(), TEMPLATE);

        assertThat(mail.subject()).isEqualTo("Giảng viên đã trả lời câu hỏi của bạn trên Edumy");
        assertThat(mail.htmlBody())
                .contains("Xin chào Lê Văn Hỏi,")
                .contains("<strong>Phạm Thị Đáp</strong>")
                .contains("“Câu hỏi”")
                .contains("“" + courseTitle + "”")
                .contains("href=\"" + BASE_URL + "/courses/" + slug + "/learn?question=" + question + "\"")
                .doesNotContain("Cài JDK 21 nhé"); // the answer text stays out of the mail
        assertThat(mails.sentTo(instructor.user().email())).isEmpty();
    }

    @Test
    void askersFollowUp_noMail_D42() throws Exception {
        answer(student);
        answer(instructor);

        // the instructor answer's mail arrives; the follow-up produced none (exactly one mail to the asker)
        mails.awaitMail(student.user().email(), TEMPLATE);
        assertThat(mails.sentTo(student.user().email())).hasSize(1);
    }

    @Test
    void mailFails_answerStaysSaved_logHasNoAddress_UCI21E3_NFR08(CapturedOutput output) throws Exception {
        mails.failNextSendsTo(student.user().email(), 1);

        answer(instructor);

        mails.awaitFailuresConsumed(student.user().email());
        String failureLine = "Mail not sent: event=QuestionAnsweredByInstructor, userId=" + student.userId() + ",";
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(failureLine));
        assertThat(jdbc.sql("SELECT visibility_status FROM question_answers WHERE question_id = ?").param(question)
                .query(String.class).single()).isEqualTo("visible");
        assertThat(mails.sentTo(student.user().email())).isEmpty();
        List<String> failureLines = output.getAll().lines().filter(line -> line.contains(failureLine)).toList();
        assertThat(failureLines).isNotEmpty().allSatisfy(line -> assertThat(line)
                .doesNotContain("@")
                .doesNotContain("Simulated SMTP failure"));
    }

    private void answer(Session who) throws Exception {
        mvcPost(who).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions mvcPost(Session who) throws Exception {
        return mvc.perform(post("/api/v1/questions/" + question + "/answers")
                .header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\": \"Cài JDK 21 nhé\"}"));
    }
}
