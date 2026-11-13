package com.edumy.identity.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.edumy.identity.api.PasswordChanged;
import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** {@code /me} endpoints (UC-G09, UC-G12, UC-G13) through the real filter chain and MySQL. */
@MySqlSpringBootTest
@RecordApplicationEvents
class MeControllerIT {

    static final String ME = "/api/v1/me";
    static final String PASSWORD = "/api/v1/me/password";
    static final String INSTRUCTOR = "/api/v1/me/instructor-registration";
    static final String LOGIN = "/api/v1/auth/login";
    static final String REFRESH = "/api/v1/auth/refresh";
    static final String NEW_PASSWORD = "DoiMatKhau99";
    static final Pattern REFRESH_COOKIE = Pattern.compile("^edumy_rt=([A-Za-z0-9_-]{43});.*$");

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
    CacheManager cacheManager;
    @Autowired
    ApplicationEvents events;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------- GET / PATCH

    @Test
    void getMe_profileAndActiveRolesFromDatabase_UCG09() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        users.revokeRole(session.userId(), RoleCode.INSTRUCTOR); // the JWT still says instructor

        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(session.userId()))
                .andExpect(jsonPath("$.email").value(session.user().email()))
                .andExpect(jsonPath("$.fullName").value("Người dùng kiểm thử"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.roles", contains("student")))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void getMe_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get(ME)), 401, "UNAUTHENTICATED");
    }

    @Test
    void patchMe_updatesTrimmedFullName_UCG09() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);

        mvc.perform(patch(ME).header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"  Trần Thị B  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Trần Thị B"));

        assertThat(jdbc.sql("SELECT full_name FROM users WHERE id = ?").param(session.userId())
                .query(String.class).single()).isEqualTo("Trần Thị B");
    }

    @Test
    void patchMe_blankOrOver100Characters_400Vietnamese_UCG09() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);

        MvcResult blank = expectProblem(mvc.perform(patch(ME).header(HttpHeaders.AUTHORIZATION, session.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"   \"}")), 400, "VALIDATION_FAILED");
        jsonPath("$.errors[0].field").value("fullName").match(blank);
        jsonPath("$.errors[0].message").value("Không được để trống").match(blank);

        MvcResult tooLong = expectProblem(mvc.perform(patch(ME).header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"" + "ă".repeat(101) + "\"}")),
                400, "VALIDATION_FAILED");
        jsonPath("$.errors[0].message").value("Họ tên tối đa 100 ký tự").match(tooLong);
    }

    // ------------------------------------------------------------------------------------------- change password

    @Test
    void changePassword_endsOtherSessionsOnly_currentKeepsWorking_publishesPasswordChanged_UCG12() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn current = login(user.email(), TestUsers.PASSWORD);
        LoggedIn other = login(user.email(), TestUsers.PASSWORD);
        expectMe(current.accessToken(), 200); // caches "session active"
        expectMe(other.accessToken(), 200);

        mvc.perform(put(PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeJson(TestUsers.PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        expectMe(current.accessToken(), 200);
        expectMe(other.accessToken(), 401);
        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", other.refreshToken()))),
                401, "UNAUTHENTICATED");
        mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", current.refreshToken())))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("""
                        SELECT family_id FROM refresh_tokens
                        WHERE user_id = ? AND revoke_reason = 'password_changed'
                        """).param(user.id()).query(String.class).list())
                .containsExactly(other.sid());

        assertThat(events.stream(PasswordChanged.class).toList())
                .singleElement().satisfies(e -> assertThat(e.userId()).isEqualTo(user.id()));
        expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(user.email(), TestUsers.PASSWORD))), 401, "AUTH_INVALID_CREDENTIALS");
        login(user.email(), NEW_PASSWORD);
    }

    @Test
    void changePassword_wrongCurrent_422PasswordIncorrect_UCG12() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        String hashBefore = passwordHash(session.userId());

        MvcResult result = expectProblem(mvc.perform(put(PASSWORD).header(HttpHeaders.AUTHORIZATION, session.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(changeJson("Wrong12345", NEW_PASSWORD))), 422, "PASSWORD_INCORRECT");

        jsonPath("$.title").value("Mật khẩu hiện tại không đúng").match(result);
        assertThat(passwordHash(session.userId())).isEqualTo(hashBefore);
        assertThat(events.stream(PasswordChanged.class)).isEmpty();
    }

    @Test
    void changePassword_newSameAsCurrent_422PasswordSameAsOld_UCG12() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);

        expectProblem(mvc.perform(put(PASSWORD).header(HttpHeaders.AUTHORIZATION, session.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(changeJson(TestUsers.PASSWORD, TestUsers.PASSWORD))), 422, "PASSWORD_SAME_AS_OLD");
        assertThat(events.stream(PasswordChanged.class)).isEmpty();
    }

    @Test
    void changePassword_weakNewPassword_400_NFR02() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);

        MvcResult result = expectProblem(mvc.perform(put(PASSWORD).header(HttpHeaders.AUTHORIZATION, session.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(changeJson(TestUsers.PASSWORD, "onlyletters"))), 400, "VALIDATION_FAILED");
        jsonPath("$.errors[0].field").value("newPassword").match(result);
    }

    // --------------------------------------------------------------------------------- instructor registration

    @Test
    void instructorRegistration_emailNotVerified_403_UCG13E1() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT);
        users.markEmailUnverified(session.userId());

        expectProblem(mvc.perform(post(INSTRUCTOR).header(HttpHeaders.AUTHORIZATION, session.bearer())),
                403, "EMAIL_NOT_VERIFIED");
        assertThat(instructorRows(session.userId())).isZero();
    }

    @Test
    void instructorRegistration_roleRevokedBefore_403_noSelfReactivation_UCG13E2_D04() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        users.revokeRole(session.userId(), RoleCode.INSTRUCTOR);

        MvcResult result = expectProblem(mvc.perform(post(INSTRUCTOR)
                .header(HttpHeaders.AUTHORIZATION, session.bearer())), 403, "INSTRUCTOR_ROLE_REVOKED");

        jsonPath("$.title").value("Không thể đăng ký làm giảng viên").match(result);
        assertThat(jdbc.sql("""
                        SELECT ur.is_active FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'instructor'
                        """).param(session.userId()).query(Boolean.class).single()).isFalse();
    }

    @Test
    void instructorRegistration_alreadyActive_200Idempotent_UCG13E3() throws Exception {
        Session session = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        mvc.perform(post(INSTRUCTOR).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("student", "instructor")));
        assertThat(instructorRows(session.userId())).isEqualTo(1L);
    }

    @Test
    void instructorRegistration_granted_201_jwtStaleButDatabaseRoleActive_cacheEvicted_UCG13() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        LoggedIn session = login(user.email(), TestUsers.PASSWORD);
        Cache activeRoles = cacheManager.getCache(CacheConfig.ACTIVE_ROLES);
        activeRoles.put(user.id(), List.of("student")); // stale entry the grant must evict

        mvc.perform(post(INSTRUCTOR).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles", contains("student", "instructor")));

        assertThat(activeRoles.get(user.id())).isNull();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'instructor' AND ur.is_active AND ur.assigned_by IS NULL
                        """).param(user.id()).query(Long.class).single()).isEqualTo(1L);
        // the access token still carries the old roles (BR-26: rights are re-checked from the database) ...
        assertThat(jwtDecoder.decode(session.accessToken()).getClaimAsStringList("roles"))
                .containsExactly("student");
        mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(jsonPath("$.roles", contains("student", "instructor")));
        // ... until the next refresh
        MvcResult refreshed = mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", session.refreshToken())))
                .andExpect(status().isOk())
                .andReturn();
        String access = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
        assertThat(jwtDecoder.decode(access).getClaimAsStringList("roles")).containsExactly("student", "instructor");

        mvc.perform(post(INSTRUCTOR).header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk()); // E3 afterwards
    }

    @Test
    void instructorRegistration_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post(INSTRUCTOR)), 401, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private LoggedIn login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        Matcher cookie = REFRESH_COOKIE.matcher(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(cookie.matches()).isTrue();
        String access = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        return new LoggedIn(access, cookie.group(1), jwtDecoder.decode(access).getClaimAsString("sid"));
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

    private String passwordHash(Long userId) {
        return jdbc.sql("SELECT password_hash FROM users WHERE id = ?").param(userId).query(String.class).single();
    }

    private long instructorRows(Long userId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'instructor'
                        """).param(userId).query(Long.class).single();
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String changeJson(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    record LoggedIn(String accessToken, String refreshToken, String sid) {

        @Override
        public String toString() {
            return "LoggedIn[sid=" + sid + ", tokens=****]";
        }
    }
}
