package com.edumy.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.edumy.authoring.api.CoInstructorInvited;
import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.authoring.api.CourseUnhidden;
import com.edumy.identity.api.EmailVerificationRequested;
import com.edumy.identity.api.PasswordChanged;
import com.edumy.identity.api.PasswordResetRequested;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserRegistered;
import com.edumy.identity.api.UserSummary;
import com.edumy.learning.api.QuestionAnsweredByInstructor;
import com.edumy.notification.integration.MailMessage;
import com.edumy.support.CapturingMailGateway;
import com.edumy.support.MySqlModuleTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Module test of {@code notification} (identity and authoring read through mocked {@code api} interfaces): each mail
 * listener receives the module events of docs/ai/01 §1.10 published through {@link Scenario} (after commit, async) and
 * the {@link CapturingMailGateway} gets the right template and recipient (SRS §7); a gateway failure is swallowed and
 * logged without the address (BR-20, NFR-08). The database is not used by this module; the full HTTP-to-mail chains are
 * in {@code AuthoringMailListenerIT}, {@code LearningMailListenerIT} and {@code IdentityMailListenerIT}.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@MySqlModuleTest
@ExtendWith(OutputCaptureExtension.class)
class NotificationModuleIT {

    @MockitoBean
    UserQueryApi users;
    @MockitoBean
    CourseQueryApi courseQuery;

    @Autowired
    CapturingMailGateway mails;

    static final long COURSE = 4_001L;
    static final long MAIN = 4_002L;
    static final long OTHER = 4_003L;

    String mainEmail;
    String otherEmail;

    @BeforeEach
    void setUp() {
        mainEmail = "chinh-" + UUID.randomUUID() + "@edumy.test";
        otherEmail = "khac-" + UUID.randomUUID() + "@edumy.test";
        given(courseQuery.findSummaries(anyCollection())).willReturn(Map.of(COURSE,
                new CourseSummary(COURSE, "Lập trình Java", "lap-trinh-java", null, 499_000, true, MAIN)));
        given(users.findSummaries(anyCollection())).willAnswer(call -> Map.of(
                MAIN, new UserSummary(MAIN, "Nguyễn Văn Chính", mainEmail, "active"),
                OTHER, new UserSummary(OTHER, "Trần Thị Khác", otherEmail, "active")));
    }

    // ------------------------------------------------------------------------------------------ IdentityMailListener

    @Test
    void identityEvents_sendVerifyResetAndChangedMailsToTheEventAddress_UCG05_UCG06_UCG08_UCG12(Scenario scenario) {
        String to = "moi-" + UUID.randomUUID() + "@edumy.test";

        scenario.publish(new UserRegistered(1L, to, "Lê Minh", "CODE-REG-1"))
                .andWaitForStateChange(() -> mails.sentTo(to), list -> list.size() == 1)
                .andVerify(sent -> {
                    assertThat(sent.getFirst().template()).isEqualTo("verify-email");
                    assertThat(sent.getFirst().htmlBody()).contains("CODE-REG-1");
                });
        scenario.publish(new EmailVerificationRequested(1L, to, "Lê Minh", "CODE-RESEND"))
                .andWaitForStateChange(() -> mails.sentTo(to), list -> list.size() == 2)
                .andVerify(sent -> assertThat(sent.getLast().htmlBody()).contains("CODE-RESEND"));
        scenario.publish(new PasswordResetRequested(1L, to, "Lê Minh", "CODE-RESET"))
                .andWaitForStateChange(() -> mails.sentTo(to), list -> list.size() == 3)
                .andVerify(sent -> {
                    assertThat(sent.getLast().template()).isEqualTo("password-reset");
                    assertThat(sent.getLast().htmlBody()).contains("CODE-RESET");
                });
        scenario.publish(new PasswordChanged(1L, to, "Lê Minh", Instant.parse("2026-11-15T01:02:03Z")))
                .andWaitForStateChange(() -> mails.sentTo(to), list -> list.size() == 4)
                .andVerify(sent -> assertThat(sent.getLast().template()).isEqualTo("password-changed"));
    }

    // ------------------------------------------------------------------------------------------ AuthoringMailListener

    @Test
    void courseApprovedRejectedHidden_mailTheMainInstructorWithTheirTemplates_UCA10_UCA11_UCA12(Scenario scenario) {
        scenario.publish(new CourseApproved(COURSE, "lap-trinh-java"))
                .andWaitForStateChange(() -> mails.sentTo(mainEmail), list -> list.size() == 1)
                .andVerify(sent -> {
                    assertThat(sent.getFirst().template()).isEqualTo("course-approved");
                    assertThat(sent.getFirst().htmlBody()).contains("Lập trình Java", "Nguyễn Văn Chính");
                });
        scenario.publish(new CourseRejected(COURSE, "lap-trinh-java", "Thiếu bài giảng"))
                .andWaitForStateChange(() -> mails.sentTo(mainEmail), list -> list.size() == 2)
                .andVerify(sent -> {
                    assertThat(sent.getLast().template()).isEqualTo("course-rejected");
                    assertThat(sent.getLast().htmlBody()).contains("Thiếu bài giảng");
                });
        scenario.publish(new CourseHidden(COURSE, "lap-trinh-java", "Vi phạm bản quyền"))
                .andWaitForStateChange(() -> mails.sentTo(mainEmail), list -> list.size() == 3)
                .andVerify(sent -> {
                    assertThat(sent.getLast().template()).isEqualTo("course-hidden");
                    assertThat(sent.getLast().htmlBody()).contains("Vi phạm bản quyền");
                });
        assertThat(mails.sentTo(otherEmail)).isEmpty();
    }

    @Test
    void courseUnhidden_sendsNoMail_UCA16(Scenario scenario) {
        scenario.publish(new CourseUnhidden(COURSE, "lap-trinh-java")).andWaitForStateChange(() -> Boolean.TRUE)
                .andVerify(ok -> { });
        // a mail that does exist, published afterwards: it must be the only one
        scenario.publish(new CourseApproved(COURSE, "lap-trinh-java"))
                .andWaitForStateChange(() -> mails.sentTo(mainEmail), list -> !list.isEmpty())
                .andVerify(sent -> assertThat(sent).extracting(MailMessage::template).containsExactly("course-approved"));
    }

    @Test
    void coInstructorInvited_mailsTheInvitee_UCI13(Scenario scenario) {
        scenario.publish(new CoInstructorInvited(7L, COURSE, OTHER, MAIN))
                .andWaitForStateChange(() -> mails.sentTo(otherEmail), list -> list.size() == 1)
                .andVerify(sent -> {
                    assertThat(sent.getFirst().template()).isEqualTo("co-instructor-invited");
                    assertThat(sent.getFirst().htmlBody()).contains("Nguyễn Văn Chính", "Lập trình Java");
                });
        assertThat(mails.sentTo(mainEmail)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ LearningMailListener

    @Test
    void questionAnsweredByInstructor_mailsTheAsker_UCI21_D42(Scenario scenario) {
        scenario.publish(new QuestionAnsweredByInstructor(11L, 12L, COURSE, OTHER, MAIN, "Làm sao dùng Stream?"))
                .andWaitForStateChange(() -> mails.sentTo(otherEmail), list -> list.size() == 1)
                .andVerify(sent -> {
                    assertThat(sent.getFirst().template()).isEqualTo("question-answered");
                    assertThat(sent.getFirst().htmlBody()).contains("Làm sao dùng Stream?", "Nguyễn Văn Chính");
                });
        assertThat(mails.sentTo(mainEmail)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ failures (BR-20)

    @Test
    void gatewayFailure_isSwallowedAndLoggedWithoutTheAddress_BR20_NFR08(Scenario scenario, CapturedOutput output) {
        mails.failNextSendsTo(mainEmail, 1);

        scenario.publish(new CourseApproved(COURSE, "lap-trinh-java"))
                .andWaitForStateChange(() -> mails.failedTo(mainEmail), list -> !list.isEmpty())
                .andVerify(failed -> { });

        String failureLine = "Mail not sent: event=CourseApproved, courseId=" + COURSE + ",";
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(failureLine));
        assertThat(mails.sentTo(mainEmail)).isEmpty();
        List<String> lines = output.getAll().lines().filter(line -> line.contains(failureLine)).toList();
        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(line).doesNotContain("@"));
    }

    @Test
    void courseGone_nothingSentFailureLoggedWithoutPersonalData_UCA10E3(Scenario scenario, CapturedOutput output) {
        given(courseQuery.findSummaries(anyCollection())).willReturn(Map.of()); // deleted since the event

        scenario.publish(new CourseRejected(COURSE, "lap-trinh-java", "Lý do"))
                .andWaitForStateChange(() -> output.getAll().contains("Mail not sent: event=CourseRejected"),
                        Boolean::booleanValue)
                .andVerify(logged -> { });

        assertThat(mails.sentTo(mainEmail)).isEmpty();
        assertThat(output.getAll()).doesNotContain(mainEmail);
    }
}
