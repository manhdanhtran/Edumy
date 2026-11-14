package com.edumy.identity.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Admin writes on user accounts (UC-A02 lock, UC-A03 unlock, UC-A04 grant / UC-A05 revoke instructor) through the real
 * filter chain and MySQL: BR-36, D-05 (lock ends every session at once), D-04/D-07 (revoke effective at once), BR-26,
 * BR-28 (role rows reused, never deleted).
 */
@MySqlSpringBootTest
class AdminUserWriteIT {

    static final String USERS = "/api/v1/admin/users/";
    static final String ME = "/api/v1/me";
    static final String LOGIN = "/api/v1/auth/login";
    static final String REFRESH = "/api/v1/auth/refresh";
    static final String INSTRUCTOR_COURSES = "/api/v1/instructor/courses";
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
    SqlStatementCounter sql;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------ UC-A02 lock

    @Test
    void lock_endsEverySessionAtOnce_liveTokenRejected_refreshFails_loginLocked_UCA02_D05() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser target = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        LoggedIn first = login(target.email());
        LoggedIn second = login(target.email());
        expectMe(first.accessToken(), 200); // caches "session active" and "user active"
        expectMe(second.accessToken(), 200);

        sql.reset();
        mvc.perform(post(USERS + target.id() + "/lock").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.id()))
                .andExpect(jsonPath("$.email").value(target.email()))
                .andExpect(jsonPath("$.status").value("locked"))
                .andExpect(jsonPath("$.roles", contains("student", "instructor")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(sql.statements())
                .as("the target row is read with a row lock")
                .anyMatch(statement -> statement.toLowerCase().contains("for update"));

        // no waiting for the 30 s cache TTL nor the 15 min access-token TTL
        expectMe(first.accessToken(), 401);
        expectMe(second.accessToken(), 401);
        expectProblem(mvc.perform(post(REFRESH).cookie(new Cookie("edumy_rt", first.refreshToken()))),
                401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(target.email()))), 403, "AUTH_ACCOUNT_LOCKED");

        assertThat(userStatus(target.id())).isEqualTo("locked");
        assertThat(jdbc.sql("SELECT revoke_reason FROM refresh_tokens WHERE user_id = ?").param(target.id())
                .query(String.class).list())
                .hasSize(2)
                .containsOnly("account_locked");
        // the admin's own session is untouched
        expectMe(admin.accessToken(), 200);
    }

    @Test
    void lock_self_403AdminLockForbidden_nothingChanges_BR36() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);

        expectProblem(mvc.perform(post(USERS + admin.userId() + "/lock")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 403, "ADMIN_LOCK_FORBIDDEN");

        assertThat(userStatus(admin.userId())).isEqualTo("active");
        expectMe(admin.accessToken(), 200);
    }

    @Test
    void lock_anotherAdmin_403AdminLockForbidden_sessionsKept_BR36_AC37() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        Session otherAdmin = tokens.newUserSession(RoleCode.STUDENT, RoleCode.ADMIN);

        expectProblem(mvc.perform(post(USERS + otherAdmin.userId() + "/lock")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 403, "ADMIN_LOCK_FORBIDDEN");

        assertThat(userStatus(otherAdmin.userId())).isEqualTo("active");
        assertThat(activeRefreshTokens(otherAdmin.userId())).isEqualTo(1);
        expectMe(otherAdmin.accessToken(), 200);
    }

    @Test
    void lock_formerAdminWithRevokedAdminRole_isAllowed() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser former = users.create(RoleCode.STUDENT, RoleCode.ADMIN);
        users.revokeRole(former.id(), RoleCode.ADMIN);

        mvc.perform(post(USERS + former.id() + "/lock").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("locked"))
                .andExpect(jsonPath("$.roles", contains("student")));
    }

    @Test
    void lock_alreadyLocked_409_UCA02() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser target = users.create(RoleCode.STUDENT);
        users.lock(target.id());

        expectProblem(mvc.perform(post(USERS + target.id() + "/lock")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 409, "ACCOUNT_ALREADY_LOCKED");
        assertThat(userStatus(target.id())).isEqualTo("locked");
    }

    @Test
    void lock_twoConcurrentRequests_rowLockLetsExactlyOneWin() throws Exception {
        Session admin1 = tokens.newUserSession(RoleCode.ADMIN);
        Session admin2 = tokens.newUserSession(RoleCode.ADMIN);
        TestUser target = users.create(RoleCode.STUDENT);
        tokens.session(target);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Session admin : List.of(admin1, admin2)) {
                Callable<Integer> call = () -> {
                    start.await();
                    return mvc.perform(post(USERS + target.id() + "/lock")
                                    .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(userStatus(target.id())).isEqualTo("locked");
        assertThat(activeRefreshTokens(target.id())).isZero();
    }

    // ---------------------------------------------------------------------------------------------- UC-A03 unlock

    @Test
    void unlock_afterLock_userLogsInAgain_newTokenWorksAtOnce_oldStaysDead_UCA03() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser target = users.create(RoleCode.STUDENT);
        LoggedIn before = login(target.email());
        mvc.perform(post(USERS + target.id() + "/lock").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk());
        expectMe(before.accessToken(), 401); // caches "user not active"

        mvc.perform(post(USERS + target.id() + "/unlock").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.id()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.roles", contains("student")));

        assertThat(userStatus(target.id())).isEqualTo("active");
        LoggedIn after = login(target.email());
        expectMe(after.accessToken(), 200); // user-status cache evicted after commit
        expectMe(before.accessToken(), 401); // the sessions ended by the lock stay ended
    }

    @Test
    void unlock_activeUser_409_UCA03() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser target = users.create(RoleCode.STUDENT);

        expectProblem(mvc.perform(post(USERS + target.id() + "/unlock")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 409, "ACCOUNT_NOT_LOCKED");
        assertThat(userStatus(target.id())).isEqualTo("active");
    }

    // ----------------------------------------------------------------------------------------------- UC-A04 grant

    @Test
    void grant_newRow_assignedByAdmin_effectiveAtOnce_UCA04() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        Session target = tokens.newUserSession(RoleCode.STUDENT);
        expectProblem(mvc.perform(get(INSTRUCTOR_COURSES).header(HttpHeaders.AUTHORIZATION, target.bearer())),
                403, "ACCESS_DENIED"); // caches "no instructor role"

        mvc.perform(put(USERS + target.userId() + "/roles/instructor").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.userId()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("student", "instructor")));

        InstructorRow row = instructorRow(target.userId());
        assertThat(row.active()).isTrue();
        assertThat(row.assignedBy()).isEqualTo(admin.userId());
        // the role is re-checked from the database (BR-26), the access token is not refreshed
        mvc.perform(get(INSTRUCTOR_COURSES).header(HttpHeaders.AUTHORIZATION, target.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void grant_revokedRow_isReactivated_notDuplicated_BR28_D04() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser target = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        users.revokeRole(target.id(), RoleCode.INSTRUCTOR);
        InstructorRow revoked = instructorRow(target.id());

        mvc.perform(put(USERS + target.id() + "/roles/instructor").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("student", "instructor")));

        assertThat(instructorRowCount(target.id())).isEqualTo(1);
        InstructorRow reactivated = instructorRow(target.id());
        assertThat(reactivated.id()).isEqualTo(revoked.id());
        assertThat(reactivated.active()).isTrue();
        assertThat(reactivated.assignedBy()).isEqualTo(admin.userId());
    }

    @Test
    void grant_alreadyActive_409_nothingChanges_UCA04E1() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser target = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        expectProblem(mvc.perform(put(USERS + target.id() + "/roles/instructor")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 409, "INSTRUCTOR_ROLE_ALREADY_ACTIVE");

        InstructorRow row = instructorRow(target.id());
        assertThat(row.active()).isTrue();
        assertThat(row.assignedBy()).isNull(); // not rewritten
    }

    // ---------------------------------------------------------------------------------------------- UC-A05 revoke

    @Test
    void revoke_rowKeptInactive_instructorRequestRefusedAtOnce_sessionKept_UCA05_D07() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        Session target = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        mvc.perform(get(INSTRUCTOR_COURSES).header(HttpHeaders.AUTHORIZATION, target.bearer()))
                .andExpect(status().isOk()); // caches the active roles

        mvc.perform(delete(USERS + target.userId() + "/roles/instructor")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.userId()))
                .andExpect(jsonPath("$.roles", contains("student")));

        assertThat(instructorRowCount(target.userId())).isEqualTo(1);
        assertThat(instructorRow(target.userId()).active()).isFalse();
        // the JWT still claims instructor: the database decides, without waiting for the 60 s cache TTL
        expectProblem(mvc.perform(get(INSTRUCTOR_COURSES).header(HttpHeaders.AUTHORIZATION, target.bearer())),
                403, "ACCESS_DENIED");
        expectMe(target.accessToken(), 200);
    }

    @Test
    void revoke_noRoleOrAlreadyRevoked_409_UCA05E1() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        TestUser student = users.create(RoleCode.STUDENT);
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);

        expectProblem(mvc.perform(delete(USERS + student.id() + "/roles/instructor")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 409, "INSTRUCTOR_ROLE_NOT_ACTIVE");
        expectProblem(mvc.perform(delete(USERS + revoked.id() + "/roles/instructor")
                .header(HttpHeaders.AUTHORIZATION, admin.bearer())), 409, "INSTRUCTOR_ROLE_NOT_ACTIVE");
        assertThat(instructorRowCount(student.id())).isZero();
    }

    // ------------------------------------------------------------------------------------------ common: 404/403/401

    @Test
    void unknownUser_404_everyEndpoint() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        for (MockHttpServletRequestBuilder request : writes(Long.MAX_VALUE)) {
            expectProblem(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, admin.bearer())),
                    404, "RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void nonAdmin_403_everyEndpoint_nothingChanges() throws Exception {
        Session student = tokens.newUserSession(RoleCode.STUDENT);
        Session instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        TestUser target = users.create(RoleCode.STUDENT);
        for (Session caller : List.of(student, instructor)) {
            for (MockHttpServletRequestBuilder request : writes(target.id())) {
                expectProblem(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, caller.bearer())),
                        403, "ACCESS_DENIED");
            }
        }
        assertThat(userStatus(target.id())).isEqualTo("active");
        assertThat(instructorRowCount(target.id())).isZero();
    }

    @Test
    void adminRoleRevokedInDatabase_staleJwt_403_BR26() throws Exception {
        Session staleAdmin = tokens.newUserSession(RoleCode.ADMIN);
        users.revokeRole(staleAdmin.userId(), RoleCode.ADMIN); // the JWT still claims admin
        TestUser target = users.create(RoleCode.STUDENT);

        for (MockHttpServletRequestBuilder request : writes(target.id())) {
            expectProblem(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, staleAdmin.bearer())),
                    403, "ACCESS_DENIED");
        }
        assertThat(userStatus(target.id())).isEqualTo("active");
        assertThat(instructorRowCount(target.id())).isZero();
    }

    @Test
    void withoutToken_401_everyEndpoint() throws Exception {
        TestUser target = users.create(RoleCode.STUDENT);
        for (MockHttpServletRequestBuilder request : writes(target.id())) {
            expectProblem(mvc.perform(request), 401, "UNAUTHENTICATED");
        }
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private static List<MockHttpServletRequestBuilder> writes(Long userId) {
        return List.of(
                post(USERS + userId + "/lock"),
                post(USERS + userId + "/unlock"),
                put(USERS + userId + "/roles/instructor"),
                delete(USERS + userId + "/roles/instructor"));
    }

    private LoggedIn login(String email) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(loginJson(email)))
                .andExpect(status().isOk())
                .andReturn();
        Matcher cookie = REFRESH_COOKIE.matcher(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(cookie.matches()).isTrue();
        return new LoggedIn(JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"),
                cookie.group(1));
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

    private String userStatus(Long userId) {
        return jdbc.sql("SELECT status FROM users WHERE id = ?").param(userId).query(String.class).single();
    }

    private int activeRefreshTokens(Long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL")
                .param(userId).query(Integer.class).single();
    }

    private int instructorRowCount(Long userId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'instructor'
                        """)
                .param(userId).query(Integer.class).single();
    }

    private InstructorRow instructorRow(Long userId) {
        return jdbc.sql("""
                        SELECT ur.id, ur.is_active, ur.assigned_by FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = ? AND r.code = 'instructor'
                        """)
                .param(userId)
                .query((rs, n) -> new InstructorRow(rs.getLong(1), rs.getBoolean(2), rs.getObject(3, Long.class)))
                .single();
    }

    private static String loginJson(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + TestUsers.PASSWORD + "\"}";
    }

    record LoggedIn(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "LoggedIn[tokens=****]";
        }
    }

    record InstructorRow(long id, boolean active, Long assignedBy) {
    }
}
