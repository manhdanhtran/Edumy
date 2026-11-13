package com.edumy.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.edumy.identity.api.PasswordResetRequested;
import com.edumy.identity.api.UserRegistered;
import com.edumy.notification.integration.MailMessage;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CapturingMailGateway;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Identity mails end to end: HTTP flow → identity event → {@link IdentityMailListener} (async, after commit) → real
 * {@code MailComposer} → {@link CapturingMailGateway}. The code in the mailed link is the one identity accepts; a mail
 * failure never undoes the business change (BR-20, UC-G05 E3); nothing secret reaches the log (NFR-08).
 */
@MySqlSpringBootTest
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class IdentityMailListenerIT {

    static final String REGISTER = "/api/v1/auth/register";
    static final String CONFIRM = "/api/v1/auth/email-verification/confirm";
    static final String RESEND = "/api/v1/auth/email-verification/resend";
    static final String FORGOT = "/api/v1/auth/password/forgot";
    static final String RESET = "/api/v1/auth/password/reset";
    static final String CHANGE_PASSWORD = "/api/v1/me/password";
    static final String BASE_URL = "http://localhost:5173"; // edumy.frontend.base-url default
    static final String NEW_PASSWORD = "MatKhau2026";
    static final Pattern VERIFY_LINK = Pattern.compile("href=\"" + BASE_URL + "/verify-email\\?token=([^\"]+)\"");
    static final Pattern RESET_LINK = Pattern.compile("href=\"" + BASE_URL + "/reset-password\\?token=([^\"]+)\"");

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    CapturingMailGateway mails;
    @Autowired
    ApplicationEvents events;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    @Test
    void register_verificationMail_linkTokenVerifiesAccount_UCG05_UCG06() throws Exception {
        String email = newEmail();
        register(email, "Nguyễn Văn Mới");

        MailMessage mail = mails.awaitMail(email, "verify-email");

        assertThat(mail.subject()).isEqualTo("Xác thực địa chỉ email tài khoản Edumy");
        assertThat(mail.htmlBody()).contains("Xin chào Nguyễn Văn Mới,").contains("24 giờ");
        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(token(VERIFY_LINK, mail))))
                .andExpect(status().isNoContent());
        assertThat(emailVerified(users.idByEmail(email))).isTrue();
        assertThat(mails.sentTo(email)).hasSize(1); // one mail only: no separate welcome mail in the SRS list
    }

    @Test
    void forgot_resetMail_reset_passwordChangedMail_UCG08() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);

        forgot(user.email());
        MailMessage resetMail = mails.awaitMail(user.email(), "password-reset");
        assertThat(resetMail.subject()).isEqualTo("Đặt lại mật khẩu tài khoản Edumy");
        assertThat(resetMail.htmlBody()).contains("30 phút");

        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token(RESET_LINK, resetMail) + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        MailMessage changed = mails.awaitMail(user.email(), "password-changed");
        assertThat(changed.subject()).isEqualTo("Mật khẩu tài khoản Edumy đã được thay đổi");
        assertThat(changed.htmlBody()).doesNotContain("token=");
    }

    @Test
    void changePassword_passwordChangedMail_UCG12() throws Exception {
        JwtTestTokens.Session session = tokens.newUserSession(RoleCode.STUDENT);

        mvc.perform(put(CHANGE_PASSWORD).header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + TestUsers.PASSWORD + "\",\"newPassword\":\""
                                + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        MailMessage changed = mails.awaitMail(session.user().email(), "password-changed");
        assertThat(changed.subject()).isEqualTo("Mật khẩu tài khoản Edumy đã được thay đổi");
    }

    @Test
    void register_mailFails_accountStillCreated_resendAfterCooldownMails_UCG05E3_BR20() throws Exception {
        String email = newEmail();
        mails.failNextSendsTo(email, 1);

        String accessToken = register(email, "Người mới");

        mails.awaitFailuresConsumed(email);
        Long userId = users.idByEmail(email); // the registration committed
        assertThat(mails.failedTo(email)).hasSize(1);
        assertThat(mails.sentTo(email)).isEmpty();

        clock.advance(Duration.ofSeconds(61)); // past the 60 s resend cooldown (D-03)
        mvc.perform(post(RESEND).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isAccepted());

        MailMessage resent = mails.awaitMail(email, "verify-email");
        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                        .content(tokenJson(token(VERIFY_LINK, resent))))
                .andExpect(status().isNoContent());
        assertThat(emailVerified(userId)).isTrue();
    }

    @Test
    void mailFlows_logContainsNoCodePasswordOrAddress_eventsMaskCodes_NFR08(CapturedOutput output) throws Exception {
        String okEmail = newEmail();
        register(okEmail, "Người A");
        String verifyCode = token(VERIFY_LINK, mails.awaitMail(okEmail, "verify-email"));

        TestUser user = users.create(RoleCode.STUDENT);
        forgot(user.email());
        String resetCode = token(RESET_LINK, mails.awaitMail(user.email(), "password-reset"));

        String failingEmail = newEmail();
        mails.failNextSendsTo(failingEmail, 1);
        register(failingEmail, "Người B");
        mails.awaitFailuresConsumed(failingEmail);
        Long failingUserId = users.idByEmail(failingEmail);
        String failureLine = "Mail not sent: event=UserRegistered, userId=" + failingUserId + ",";
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(failureLine));
        String failedCode = token(VERIFY_LINK, mails.failedTo(failingEmail).getFirst());

        String log = output.getAll();
        for (String code : List.of(verifyCode, resetCode, failedCode)) {
            assertThat(log).doesNotContain(code)
                    .doesNotContain(URLDecoder.decode(code, StandardCharsets.UTF_8));
        }
        assertThat(log).doesNotContain(NEW_PASSWORD).doesNotContain(TestUsers.PASSWORD);
        List<String> failureLines = log.lines().filter(line -> line.contains("Mail not sent")).toList();
        assertThat(failureLines).anyMatch(line -> line.contains(failureLine));
        assertThat(failureLines).allSatisfy(line -> assertThat(line)
                .doesNotContain(failingEmail)
                .doesNotContain("@")
                .doesNotContain("Simulated SMTP failure"));

        UserRegistered registered = events.stream(UserRegistered.class)
                .filter(e -> e.email().equals(failingEmail)).findFirst().orElseThrow();
        assertThat(registered.toString()).doesNotContain(registered.rawVerificationCode());
        PasswordResetRequested reset = events.stream(PasswordResetRequested.class)
                .filter(e -> e.email().equals(user.email())).findFirst().orElseThrow();
        assertThat(reset.toString()).doesNotContain(reset.rawCode());
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** Registers through the API (user deleted after the test); returns the access token. */
    private String register(String email, String fullName) throws Exception {
        users.deleteLaterByEmail(email);
        MvcResult result = mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD
                                + "\",\"fullName\":\"" + fullName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void forgot(String email) throws Exception {
        mvc.perform(post(FORGOT).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
    }

    /** The code of the mailed link, as the SPA reads it from {@code ?token=}. */
    private static String token(Pattern link, MailMessage mail) {
        Matcher matcher = link.matcher(mail.htmlBody());
        assertThat(matcher.find()).as("link in " + mail.template()).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private boolean emailVerified(Long userId) {
        return jdbc.sql("SELECT is_email_verified FROM users WHERE id = ?").param(userId)
                .query(Boolean.class).single();
    }

    private static String newEmail() {
        return "mail-" + UUID.randomUUID() + "@edumy.test";
    }

    private static String tokenJson(String token) {
        return "{\"token\":\"" + token + "\"}";
    }
}
