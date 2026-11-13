package com.edumy.identity.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static com.edumy.support.ProblemAssertions.withoutTraceAndInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.edumy.identity.api.EmailVerificationRequested;
import com.edumy.identity.api.PasswordChanged;
import com.edumy.identity.api.PasswordResetRequested;
import com.edumy.identity.api.UserRegistered;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Register (UC-G05), email verification (UC-G06) and forgot / reset password (UC-G08) through the real filter chain
 * and MySQL. Codes (BR-34, D-03) are taken from the recorded {@code identity.api} events, as the mail would carry
 * them; time moves with {@link MutableClock}.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class AccountFlowsIT {

    static final String REGISTER = "/api/v1/auth/register";
    static final String CONFIRM = "/api/v1/auth/email-verification/confirm";
    static final String RESEND = "/api/v1/auth/email-verification/resend";
    static final String FORGOT = "/api/v1/auth/password/forgot";
    static final String RESET = "/api/v1/auth/password/reset";
    static final String LOGIN = "/api/v1/auth/login";
    static final String REFRESH = "/api/v1/auth/refresh";
    static final String ME = "/api/v1/me";
    static final String GOOD_PASSWORD = "MatKhau2026";
    static final String FORGOT_MESSAGE = "Nếu email tồn tại, hướng dẫn đã được gửi";
    static final Pattern REFRESH_COOKIE = Pattern.compile(
            "^edumy_rt=([A-Za-z0-9_-]{43}); Path=/api/v1/auth; Max-Age=1209600; Expires=[^;]+; Secure; HttpOnly; "
                    + "SameSite=Strict$");

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    MutableClock clock;
    @Autowired
    ApplicationEvents events;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    // ----------------------------------------------------------------------------------------------------- register

    @Test
    void register_success_201TokenCookieStudentRoleAndHashedCode_UCG05() throws Exception {
        String email = newEmail();
        users.deleteLaterByEmail(email);

        MvcResult result = mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("  " + email.toUpperCase() + " ", GOOD_PASSWORD, "  Nguyễn Văn A  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn();

        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).matches(REFRESH_COOKIE);
        Long userId = users.idByEmail(email);
        String access = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        assertThat(jwtDecoder.decode(access).getSubject()).isEqualTo(userId.toString());
        assertThat(jwtDecoder.decode(access).getClaimAsStringList("roles")).containsExactly("student");

        UserRow user = userRow(userId);
        assertThat(user.emailVerified()).isFalse();
        assertThat(user.status()).isEqualTo("active");
        assertThat(user.fullName()).isEqualTo("Nguyễn Văn A");
        assertThat(user.passwordHash()).startsWith("$2a$12$").doesNotContain(GOOD_PASSWORD);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'student' AND ur.is_active AND ur.assigned_by IS NULL
                        """).param(userId).query(Long.class).single()).isEqualTo(1L);

        UserRegistered event = single(events.stream(UserRegistered.class).toList());
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.email()).isEqualTo(email);
        assertThat(event.fullName()).isEqualTo("Nguyễn Văn A");
        String raw = event.rawVerificationCode();
        assertThat(event.toString()).doesNotContain(raw);

        List<TokenRow> codes = codesOf(userId);
        assertThat(codes).hasSize(1);
        TokenRow code = codes.getFirst();
        assertThat(code.type()).isEqualTo("email_verification");
        assertThat(code.usedAt()).isNull();
        assertThat(code.tokenHash()).isEqualTo(sha256Hex(raw)).isNotEqualTo(raw);
        assertThat(Duration.between(code.createdAt(), code.expiresAt())).isEqualTo(Duration.ofHours(24));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_tokens WHERE token_hash = ?").param(raw)
                .query(Long.class).single()).isZero(); // the raw code is never stored
        assertThat(jdbc.sql("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL")
                .param(userId).query(Long.class).single()).isEqualTo(1L);

        // logged in (step 7), email not verified yet
        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void register_duplicateEmailAnyCase_409EmailAlreadyRegistered_UCG05E1() throws Exception {
        TestUser existing = users.create(RoleCode.STUDENT);

        expectProblem(mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(existing.email().toUpperCase(), GOOD_PASSWORD, "B"))),
                409, "EMAIL_ALREADY_REGISTERED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE email = ?").param(existing.email())
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(events.stream(UserRegistered.class)).isEmpty();
    }

    @Test
    void register_weakPassword_400VietnameseMessage_NFR02() throws Exception {
        String email = newEmail();
        users.deleteLaterByEmail(email);

        MvcResult result = expectProblem(mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email, "abcdefgh", "A"))), 400, "VALIDATION_FAILED");

        jsonPath("$.errors[0].field").value("password").match(result);
        jsonPath("$.errors[0].message")
                .value("Mật khẩu phải dài 8–72 ký tự, có ít nhất một chữ cái và một chữ số").match(result);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE email = ?").param(email)
                .query(Long.class).single()).isZero();
    }

    @Test
    void register_passwordOver72Utf8Bytes_400_NFR02() throws Exception {
        String email = newEmail();
        users.deleteLaterByEmail(email);
        String vietnamese = "ậ".repeat(30) + "a1"; // 32 characters, 92 bytes: BCrypt would reject it

        MvcResult result = expectProblem(mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(email, vietnamese, "A"))), 400, "VALIDATION_FAILED");

        jsonPath("$.errors[0].field").value("password").match(result);
        jsonPath("$.errors[0].message").value(
                "Mật khẩu quá dài: tối đa 72 byte (chữ có dấu chiếm 2–3 byte), hãy dùng mật khẩu ngắn hơn")
                .match(result);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(vietnamese);
    }

    @Test
    void register_invalidEmailBlankAndLongFullName_400Vietnamese_UCG05E2() throws Exception {
        MvcResult blank = expectProblem(mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(registerJson("not-an-email", GOOD_PASSWORD, " "))), 400, "VALIDATION_FAILED");
        jsonPath("$.errors[?(@.field == 'email')].message").value("Email không đúng định dạng").match(blank);
        jsonPath("$.errors[?(@.field == 'fullName')].message").value("Không được để trống").match(blank);

        MvcResult tooLong = expectProblem(mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(newEmail(), GOOD_PASSWORD, "A".repeat(101)))), 400, "VALIDATION_FAILED");
        jsonPath("$.errors[0].field").value("fullName").match(tooLong);
        jsonPath("$.errors[0].message").value("Họ tên tối đa 100 ký tự").match(tooLong);
    }

    // ------------------------------------------------------------------------------------------ email verification

    @Test
    void confirm_withoutAuthorization_worksOnce_secondUse422_UCG06_BR34() throws Exception {
        Registered reg = register();

        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content(tokenJson(reg.code())))
                .andExpect(status().isNoContent());
        assertThat(userRow(reg.userId()).emailVerified()).isTrue();
        assertThat(codesOf(reg.userId()).getFirst().usedAt()).isNotNull();

        MvcResult again = expectProblem(mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                .content(tokenJson(reg.code()))), 422, "ACCOUNT_TOKEN_INVALID");
        jsonPath("$.title").value("Liên kết không còn hiệu lực").match(again);
    }

    @Test
    void confirm_expiredAfter24Hours_422_unknownAndWrongType_sameBody_BR34() throws Exception {
        Registered reg = register();
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        MvcResult expired = expectProblem(mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                .content(tokenJson(reg.code()))), 422, "ACCOUNT_TOKEN_INVALID");
        MvcResult unknown = expectProblem(mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                .content(tokenJson("x".repeat(43)))), 422, "ACCOUNT_TOKEN_INVALID");

        assertThat(withoutTraceAndInstance(expired)).isEqualTo(withoutTraceAndInstance(unknown));
        assertThat(userRow(reg.userId()).emailVerified()).isFalse();
    }

    @Test
    void confirm_resetCodeUsedAsVerificationCode_422_BR34() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        users.markEmailUnverified(user.id());
        String resetCode = forgotAndGetCode(user.email());

        expectProblem(mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                .content(tokenJson(resetCode))), 422, "ACCOUNT_TOKEN_INVALID");
        assertThat(userRow(user.id()).emailVerified()).isFalse();
    }

    @Test
    void resend_withoutLogin_401_Q2() throws Exception {
        expectProblem(mvc.perform(post(RESEND)), 401, "UNAUTHENTICATED");
    }

    @Test
    void resend_within60s_429_after60s_202AndOldCodeVoided_UCG06_D03() throws Exception {
        Registered reg = register();

        MvcResult tooSoon = expectProblem(mvc.perform(post(RESEND).header(HttpHeaders.AUTHORIZATION, reg.bearer())),
                429, "TOKEN_RESEND_TOO_SOON");
        jsonPath("$.title").value("Vui lòng chờ trước khi gửi lại").match(tooSoon);
        assertThat(codesOf(reg.userId())).hasSize(1);

        clock.advance(Duration.ofSeconds(60));
        mvc.perform(post(RESEND).header(HttpHeaders.AUTHORIZATION, reg.bearer()))
                .andExpect(status().isAccepted());

        EmailVerificationRequested event = single(events.stream(EmailVerificationRequested.class).toList());
        assertThat(event.userId()).isEqualTo(reg.userId());
        assertThat(event.rawCode()).isNotEqualTo(reg.code());
        assertThat(event.toString()).doesNotContain(event.rawCode());
        List<TokenRow> codes = codesOf(reg.userId());
        assertThat(codes).hasSize(2);
        assertThat(codes.getFirst().usedAt()).isNotNull(); // BR-34: the new code voided the old one
        assertThat(codes.get(1).usedAt()).isNull();

        expectProblem(mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                .content(tokenJson(reg.code()))), 422, "ACCOUNT_TOKEN_INVALID");
        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content(tokenJson(event.rawCode())))
                .andExpect(status().isNoContent());
        assertThat(userRow(reg.userId()).emailVerified()).isTrue();
    }

    @Test
    void resend_alreadyVerified_204NothingIssued() throws Exception {
        JwtTestTokens.Session session = tokens.newUserSession(RoleCode.STUDENT); // test users are verified

        mvc.perform(post(RESEND).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNoContent());

        assertThat(codesOf(session.userId())).isEmpty();
        assertThat(events.stream(EmailVerificationRequested.class)).isEmpty();
    }

    // ----------------------------------------------------------------------------------------------- forgot / reset

    @Test
    void forgot_knownUnknownLockedAndCooldown_identical202_onlyNormalCaseIssuesCode_D03_UCG08() throws Exception {
        TestUser known = users.create(RoleCode.STUDENT);
        TestUser locked = users.create(RoleCode.STUDENT);
        users.lock(locked.id());

        MvcResult first = forgot(known.email().toUpperCase());
        MvcResult cooldown = forgot(known.email());      // < 60 s later: no new code, no signal (Q3)
        MvcResult unknown = forgot(newEmail());
        MvcResult lockedAnswer = forgot(locked.email()); // E3: no code for a locked account

        String expected = withoutTraceAndInstance(first);
        assertThat(withoutTraceAndInstance(cooldown)).isEqualTo(expected);
        assertThat(withoutTraceAndInstance(unknown)).isEqualTo(expected);
        assertThat(withoutTraceAndInstance(lockedAnswer)).isEqualTo(expected);
        jsonPath("$.message").value(FORGOT_MESSAGE).match(first);

        List<TokenRow> codes = codesOf(known.id());
        assertThat(codes).hasSize(1);
        assertThat(codes.getFirst().type()).isEqualTo("password_reset");
        assertThat(Duration.between(codes.getFirst().createdAt(), codes.getFirst().expiresAt()))
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(codesOf(locked.id())).isEmpty();
        PasswordResetRequested event = single(events.stream(PasswordResetRequested.class).toList());
        assertThat(event.userId()).isEqualTo(known.id());
        assertThat(codes.getFirst().tokenHash()).isEqualTo(sha256Hex(event.rawCode()));
        assertThat(event.toString()).doesNotContain(event.rawCode());

        clock.advance(Duration.ofSeconds(60)); // after the cooldown a new code replaces the old one (BR-34)
        forgot(known.email());
        codes = codesOf(known.id());
        assertThat(codes).hasSize(2);
        assertThat(codes.getFirst().usedAt()).isNotNull();
        assertThat(codes.get(1).usedAt()).isNull();
    }

    @Test
    void reset_codeExpiresAfter30Minutes_422_BR34() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        String code = forgotAndGetCode(user.email());
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));

        expectProblem(mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(resetJson(code, GOOD_PASSWORD))), 422, "ACCOUNT_TOKEN_INVALID");
        assertThat(events.stream(PasswordChanged.class)).isEmpty();
    }

    @Test
    void reset_endsEverySession_noLogin_codeSingleUse_passwordChangedPublished_UCG08() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn first = login(user.email(), TestUsers.PASSWORD);
        LoggedIn second = login(user.email(), TestUsers.PASSWORD);
        expectMe(first.accessToken(), 200); // caches "session active"
        expectMe(second.accessToken(), 200);
        String code = forgotAndGetCode(user.email());

        MvcResult reset = mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                        .content(resetJson(code, GOOD_PASSWORD)))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();
        assertThat(reset.getResponse().getContentAsString()).isEmpty(); // no tokens: not logged in (step 7)

        expectMe(first.accessToken(), 401);
        expectMe(second.accessToken(), 401);
        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", first.refreshToken()))),
                401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", second.refreshToken()))),
                401, "UNAUTHENTICATED");
        assertThat(jdbc.sql("SELECT DISTINCT revoke_reason FROM refresh_tokens WHERE user_id = ?")
                .param(user.id()).query(String.class).list()).containsExactly("password_reset");

        PasswordChanged changed = single(events.stream(PasswordChanged.class).toList());
        assertThat(changed.userId()).isEqualTo(user.id());
        assertThat(changed.email()).isEqualTo(user.email());

        expectProblem(mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(resetJson(code, "Another2026"))), 422, "ACCOUNT_TOKEN_INVALID");
        expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(user.email(), TestUsers.PASSWORD))), 401, "AUTH_INVALID_CREDENTIALS");
        login(user.email(), GOOD_PASSWORD);
    }

    @Test
    void reset_lockedAccount_422_passwordUnchanged_UCG08E3() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        String code = forgotAndGetCode(user.email());
        String hashBefore = userRow(user.id()).passwordHash();
        users.lock(user.id());

        expectProblem(mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(resetJson(code, GOOD_PASSWORD))), 422, "ACCOUNT_TOKEN_INVALID");

        assertThat(userRow(user.id()).passwordHash()).isEqualTo(hashBefore);
        assertThat(events.stream(PasswordChanged.class)).isEmpty();
    }

    @Test
    void reset_weakNewPassword_400_codeNotConsumed() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        String code = forgotAndGetCode(user.email());

        expectProblem(mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(resetJson(code, "short1"))), 400, "VALIDATION_FAILED");

        assertThat(codesOf(user.id()).getFirst().usedAt()).isNull();
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private Registered register() throws Exception {
        String email = newEmail();
        users.deleteLaterByEmail(email);
        MvcResult result = mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, GOOD_PASSWORD, "Người mới")))
                .andExpect(status().isCreated())
                .andReturn();
        UserRegistered event = events.stream(UserRegistered.class)
                .filter(e -> e.email().equals(email)).findFirst().orElseThrow();
        return new Registered(event.userId(), event.rawVerificationCode(),
                JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"));
    }

    private MvcResult forgot(String email) throws Exception {
        return mvc.perform(post(FORGOT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    private String forgotAndGetCode(String email) throws Exception {
        forgot(email);
        return events.stream(PasswordResetRequested.class)
                .filter(e -> e.email().equals(email)).reduce((a, b) -> b).orElseThrow().rawCode();
    }

    private LoggedIn login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        var matcher = REFRESH_COOKIE.matcher(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(matcher.matches()).isTrue();
        return new LoggedIn(JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"),
                matcher.group(1));
    }

    private void expectMe(String accessToken, int expectedStatus) throws Exception {
        if (expectedStatus == 401) {
            expectProblem(mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)),
                    401, "UNAUTHENTICATED");
        } else {
            mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                    .andExpect(status().is(expectedStatus));
        }
    }

    private UserRow userRow(Long userId) {
        return jdbc.sql("SELECT is_email_verified, status, full_name, password_hash FROM users WHERE id = ?")
                .param(userId)
                .query((rs, n) -> new UserRow(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .single();
    }

    /** Codes of the user in insertion order; times read as formatted UTC strings (no JVM-zone shift, ADR-001). */
    private List<TokenRow> codesOf(Long userId) {
        return jdbc.sql("""
                        SELECT token_type, token_hash,
                               DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
                               DATE_FORMAT(expires_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
                               DATE_FORMAT(used_at, '%Y-%m-%dT%H:%i:%s.%fZ')
                        FROM user_tokens WHERE user_id = ? ORDER BY id
                        """)
                .param(userId)
                .query((rs, n) -> new TokenRow(rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3)),
                        Instant.parse(rs.getString(4)), rs.getString(5)))
                .list();
    }

    private static <T> T single(List<T> list) {
        assertThat(list).hasSize(1);
        return list.getFirst();
    }

    private static String newEmail() {
        return "reg-" + UUID.randomUUID() + "@edumy.test";
    }

    private static String registerJson(String email, String password, String fullName) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"" + fullName + "\"}";
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String tokenJson(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private static String resetJson(String token, String newPassword) {
        return "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private static String sha256Hex(String raw) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    record UserRow(boolean emailVerified, String status, String fullName, String passwordHash) {
    }

    record TokenRow(String type, String tokenHash, Instant createdAt, Instant expiresAt, String usedAt) {
    }

    record Registered(Long userId, String code, String accessToken) {

        String bearer() {
            return "Bearer " + accessToken;
        }

        @Override
        public String toString() {
            return "Registered[userId=" + userId + ", secrets=****]";
        }
    }

    record LoggedIn(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "LoggedIn[tokens=****]";
        }
    }
}
