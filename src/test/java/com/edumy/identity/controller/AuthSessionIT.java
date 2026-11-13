package com.edumy.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.edumy.identity.entity.RevokeReason;
import com.edumy.identity.security.JwtIssuer;
import com.edumy.identity.security.SessionStatusCache;
import com.edumy.identity.service.SessionService;
import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.security.RoleCode;
import com.edumy.shared.web.TraceIdFilter;
import com.edumy.support.JwtTestTokens;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Login / refresh / logout through the real filter chain and MySQL (UC-G07, UC-G10, ADR-002, docs/ai/04 §4.1/§4.5).
 * A protected path without a handler answers 404 when the access token is accepted and 401 when it is not.
 */
@MySqlSpringBootTest
class AuthSessionIT {

    static final String LOGIN = "/api/v1/auth/login";
    static final String REFRESH = "/api/v1/auth/refresh";
    static final String LOGOUT = "/api/v1/auth/logout";
    static final String PROTECTED = "/api/v1/me/nothing-here";
    static final Pattern REFRESH_COOKIE = Pattern.compile(
            "^edumy_rt=([A-Za-z0-9_-]{43}); Path=/api/v1/auth; Max-Age=1209600; Expires=[^;]+; Secure; HttpOnly; "
                    + "SameSite=Strict$");
    static final Pattern CLEARED_COOKIE = Pattern.compile(
            "^edumy_rt=; Path=/api/v1/auth; Max-Age=0; Expires=[^;]+; Secure; HttpOnly; SameSite=Strict$");

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    Clock clock;
    @Autowired
    EdumyProperties properties;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    SessionService sessionService;
    @Autowired
    SessionStatusCache sessionStatus;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    // -------------------------------------------------------------------------------------------------------- login

    @Test
    void login_success_tokenClaimsCookieAndStoredHash_UCG07() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        MvcResult result = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, "JUnit-Agent")
                        .content(loginJson("  " + user.email().toUpperCase() + " ", TestUsers.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn();

        String refresh = refreshCookie(result);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(refresh).doesNotContain("edumy_rt");
        Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("student", "instructor");
        assertThat(jwt.getExpiresAt().toString()).isEqualTo(JsonPath.read(body, "$.expiresAt"));

        List<TokenRow> rows = rowsOf(user.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().familyId()).isEqualTo(jwt.getClaimAsString("sid"));
        assertThat(rows.getFirst().tokenHash()).isEqualTo(sha256Hex(refresh));
        assertThat(rows.getFirst().revokeReason()).isNull();
        assertThat(rows.getFirst().userAgent()).isEqualTo("JUnit-Agent");
        expectAccepted(JsonPath.read(body, "$.accessToken"));
    }

    @Test
    void login_wrongPasswordAndUnknownEmail_sameBody_D03() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);

        MvcResult wrong = expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(user.email(), "Wrong123456"))), 401, "AUTH_INVALID_CREDENTIALS");
        MvcResult unknown = expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("nobody-" + user.email(), TestUsers.PASSWORD))), 401, "AUTH_INVALID_CREDENTIALS");

        assertThat(withoutTraceAndInstance(wrong)).isEqualTo(withoutTraceAndInstance(unknown));
        jsonPath("$.title").value("Sai thông tin đăng nhập").match(wrong);
        assertThat(rowsOf(user.id())).isEmpty();
    }

    @Test
    void login_lockedCorrectPassword_403AccountLocked_Q1() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        users.lock(user.id());

        MvcResult result = expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(user.email(), TestUsers.PASSWORD))), 403, "AUTH_ACCOUNT_LOCKED");

        jsonPath("$.title").value("Tài khoản đã bị khóa").match(result);
        assertThat(rowsOf(user.id())).isEmpty();
    }

    @Test
    void login_lockedWrongPassword_generic401_Q1() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        users.lock(user.id());

        expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(user.email(), "Wrong123456"))), 401, "AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void login_unverifiedEmail_allowed() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        jdbc.sql("UPDATE users SET is_email_verified = FALSE WHERE id = ?").param(user.id()).update();

        login(user);
    }

    @Test
    void login_blankEmail_400Validation() throws Exception {
        MvcResult result = expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(" ", TestUsers.PASSWORD))), 400, "VALIDATION_FAILED");

        jsonPath("$.errors[0].field").value("email").match(result);
    }

    @Test
    void login_expiredBearerSent_ignoredAndLogsIn() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        String expired = expiredAccessToken(tokens.session(user).sid(), user.id());

        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .content(loginJson(user.email(), TestUsers.PASSWORD)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------------ refresh

    @Test
    void refresh_rotates_oldRevokedRotated_successorSameFamily() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn first = login(user);

        // a stale/garbage bearer on refresh is ignored (public endpoint)
        MvcResult result = mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", first.refreshToken()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isOk())
                .andReturn();

        String second = refreshCookie(result);
        assertThat(second).isNotEqualTo(first.refreshToken());
        String access = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        assertThat(jwtDecoder.decode(access).getClaimAsString("sid")).isEqualTo(first.sid());

        List<TokenRow> rows = rowsOf(user.id());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).tokenHash()).isEqualTo(sha256Hex(first.refreshToken()));
        assertThat(rows.get(0).revokeReason()).isEqualTo("rotated");
        assertThat(rows.get(1).tokenHash()).isEqualTo(sha256Hex(second));
        assertThat(rows.get(1).familyId()).isEqualTo(first.sid());
        assertThat(rows.get(1).revokeReason()).isNull();
        expectAccepted(access);
        expectAccepted(first.accessToken()); // same session, still live
    }

    @Test
    void refresh_reuseOfRotatedToken_401_wholeFamilyRevokedCommitted_accessTokenDead() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn first = login(user);
        MvcResult rotated = mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", first.refreshToken())))
                .andExpect(status().isOk())
                .andReturn();
        String second = refreshCookie(rotated);
        String secondAccess = JsonPath.read(rotated.getResponse().getContentAsString(), "$.accessToken");
        expectAccepted(secondAccess); // caches "session active"

        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", first.refreshToken()))),
                401, "UNAUTHENTICATED");

        // read after the failed call: the revocation was committed, not rolled back with the 401
        List<TokenRow> rows = rowsOf(user.id());
        assertThat(rows).extracting(TokenRow::revokeReason).containsExactly("rotated", "reuse_detected");
        expectUnauthenticated(secondAccess);
        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", second))), 401, "UNAUTHENTICATED");
    }

    @Test
    void refresh_missingCookie_401() throws Exception {
        expectProblem(mvc.perform(post(REFRESH)), 401, "UNAUTHENTICATED");
    }

    @Test
    void refresh_unknownToken_401() throws Exception {
        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", randomToken()))),
                401, "UNAUTHENTICATED");
    }

    @Test
    void refresh_expiredToken_401_notRevoked() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        String raw = randomToken();
        jdbc.sql("""
                        INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at, created_at)
                        VALUES (?, UUID(), ?, CURRENT_TIMESTAMP(6) - INTERVAL 1 DAY,
                                CURRENT_TIMESTAMP(6) - INTERVAL 15 DAY)
                        """)
                .params(user.id(), sha256Hex(raw))
                .update();

        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", raw))), 401, "UNAUTHENTICATED");
        assertThat(rowsOf(user.id())).extracting(TokenRow::revokeReason).containsExactly((String) null);
    }

    @Test
    void refresh_userLocked_401() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user);
        users.lock(user.id());

        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", session.refreshToken()))),
                401, "UNAUTHENTICATED");
    }

    @Test
    void refresh_afterLogout_401_tokenRevokedForOtherReason() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user);
        mvc.perform(post(LOGOUT).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNoContent());

        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", session.refreshToken()))),
                401, "UNAUTHENTICATED");
        assertThat(rowsOf(user.id())).extracting(TokenRow::revokeReason).containsExactly("logout");
    }

    // ------------------------------------------------------------------------------------------------------- logout

    @Test
    void logout_204_clearsCookie_accessTokenDeadImmediately_UCG10() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user);
        expectAccepted(session.accessToken()); // caches "session active"

        MvcResult result = mvc.perform(post(LOGOUT).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).matches(CLEARED_COOKIE);
        expectUnauthenticated(session.accessToken());
    }

    @Test
    void logout_expiredToken_401() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user);

        expectProblem(mvc.perform(post(LOGOUT).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + expiredAccessToken(session.sid(), user.id()))), 401, "UNAUTHENTICATED");
        assertThat(rowsOf(user.id())).extracting(TokenRow::revokeReason).containsExactly((String) null);
    }

    // -------------------------------------------------------------------------------------------- lock (UC-A02, D-05)

    @Test
    void lockedUser_oldAccessToken_401Immediately_D05() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user);
        expectAccepted(session.accessToken()); // caches "session active" and "user active"

        users.lock(user.id());
        sessionService.revokeAll(user.id(), RevokeReason.ACCOUNT_LOCKED); // what the admin lock flow will do
        sessionStatus.evictUser(user.id());

        expectUnauthenticated(session.accessToken());
        assertThat(rowsOf(user.id())).extracting(TokenRow::revokeReason).containsExactly("account_locked");
    }

    // ------------------------------------------------------------------------------------------------- Origin (§4.5)

    @Test
    void refresh_foreignOrigin_403_tokenNotRotated() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user);

        expectProblem(mvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, "https://evil.example")
                .cookie(new Cookie("edumy_rt", session.refreshToken()))), 403, "ACCESS_DENIED");
        assertThat(rowsOf(user.id())).extracting(TokenRow::revokeReason).containsExactly((String) null);
    }

    @Test
    void logout_foreignOrigin_403_sessionStillActive() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user);

        expectProblem(mvc.perform(post(LOGOUT).header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.AUTHORIZATION, session.bearer())), 403, "ACCESS_DENIED");
        expectAccepted(session.accessToken());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private LoggedIn login(TestUser user) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(user.email(), TestUsers.PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String access = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        return new LoggedIn(access, refreshCookie(result), jwtDecoder.decode(access).getClaimAsString("sid"));
    }

    private static String refreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).matches(REFRESH_COOKIE);
        Matcher matcher = REFRESH_COOKIE.matcher(header);
        assertThat(matcher.matches()).isTrue();
        return matcher.group(1);
    }

    /** Asserts status, code, ProblemDetail content type and traceId = X-Trace-Id header. */
    private static MvcResult expectProblem(ResultActions actions, int status, String code) throws Exception {
        MvcResult result = actions
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        jsonPath("$.traceId").value(traceId).match(result);
        return result;
    }

    private static String withoutTraceAndInstance(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\"traceId\":\"[^\"]*\"", "")
                .replaceAll("\"instance\":\"[^\"]*\"", "");
    }

    private void expectAccepted(String accessToken) throws Exception {
        mvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private void expectUnauthenticated(String accessToken) throws Exception {
        expectProblem(mvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)),
                401, "UNAUTHENTICATED");
    }

    private String expiredAccessToken(String sid, Long userId) {
        Clock past = Clock.offset(clock, properties.security().jwt().accessTtl().plusMinutes(5).negated());
        return new JwtIssuer(jwtEncoder, past, properties).issue(userId, Set.of(RoleCode.STUDENT), sid).value();
    }

    private List<TokenRow> rowsOf(Long userId) {
        return jdbc.sql("""
                        SELECT family_id, token_hash, revoke_reason, user_agent FROM refresh_tokens
                        WHERE user_id = ? ORDER BY id
                        """)
                .param(userId)
                .query((rs, n) -> new TokenRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String raw) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    record TokenRow(String familyId, String tokenHash, String revokeReason, String userAgent) {
    }

    record LoggedIn(String accessToken, String refreshToken, String sid) {

        String bearer() {
            return "Bearer " + accessToken;
        }

        @Override
        public String toString() {
            return "LoggedIn[sid=" + sid + ", tokens=****]";
        }
    }
}
