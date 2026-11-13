package com.edumy.identity.security;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * NFR-03 abuse protection through the real filter chain (docs/ai/04 §4.4). The limiters are beans of the shared test
 * context, so every test uses its own UUID email / random remote address.
 */
@MySqlSpringBootTest
class AbuseLimitersIT {

    static final String LOGIN = "/api/v1/auth/login";
    static final String WRONG = "Wrong123456";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    // ---------------------------------------------------------------------------------------- LoginAttemptLimiter

    @Test
    void login_fiveFailuresBlock15MinEvenWithCorrectPassword_thenLoginWorks_NFR03() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);

        for (int i = 0; i < 5; i++) {
            expectProblem(login(user.email(), WRONG), 401, "AUTH_INVALID_CREDENTIALS");
        }
        // failure #5 started the block: the correct password is rejected too, before any password check
        MvcResult blocked = expectProblem(login(user.email(), TestUsers.PASSWORD), 429,
                "AUTH_LOGIN_TEMPORARILY_BLOCKED");
        jsonPath("$.title").value("Tạm khóa đăng nhập").match(blocked);
        jsonPath("$.detail").value("Bạn đã đăng nhập sai quá nhiều lần, vui lòng thử lại sau.").match(blocked);
        jsonPath("$.status").value(429).match(blocked);
        assertThat(blocked.getResponse().getContentAsString()).doesNotContain(user.email());
        assertThat(retryAfter(blocked)).isBetween(890L, 900L);
        // other case / outer spaces = same normalized email
        expectProblem(login("  " + user.email().toUpperCase() + " ", TestUsers.PASSWORD), 429,
                "AUTH_LOGIN_TEMPORARILY_BLOCKED");

        clock.advance(Duration.ofMinutes(10));
        MvcResult atMinute10 = expectProblem(login(user.email(), TestUsers.PASSWORD), 429,
                "AUTH_LOGIN_TEMPORARILY_BLOCKED");
        // the attempts made while blocked did not extend the block
        assertThat(retryAfter(atMinute10)).isBetween(290L, 300L);
        assertThat(sessionCount(user.id())).isZero();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        login(user.email(), TestUsers.PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
        assertThat(sessionCount(user.id())).isEqualTo(1);
    }

    @Test
    void login_unknownEmail_blockedAfterFiveFailures_NFR03() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID() + "@edumy.test";

        for (int i = 0; i < 5; i++) {
            expectProblem(login(unknown, WRONG), 401, "AUTH_INVALID_CREDENTIALS");
        }
        MvcResult blocked = expectProblem(login(unknown, WRONG), 429, "AUTH_LOGIN_TEMPORARILY_BLOCKED");
        assertThat(retryAfter(blocked)).isBetween(890L, 900L);
    }

    @Test
    void login_successResetsCounter_lockedAccountWithCorrectPasswordCountsAsSuccess_NFR03_Q1() throws Exception {
        TestUser user = users.create(RoleCode.STUDENT);
        TestUser locked = users.create(RoleCode.STUDENT);
        users.lock(locked.id());

        for (int i = 0; i < 4; i++) {
            expectProblem(login(user.email(), WRONG), 401, "AUTH_INVALID_CREDENTIALS");
            expectProblem(login(locked.email(), WRONG), 401, "AUTH_INVALID_CREDENTIALS");
        }
        login(user.email(), TestUsers.PASSWORD).andExpect(status().isOk());
        expectProblem(login(locked.email(), TestUsers.PASSWORD), 403, "AUTH_ACCOUNT_LOCKED");
        for (int i = 0; i < 4; i++) {
            expectProblem(login(user.email(), WRONG), 401, "AUTH_INVALID_CREDENTIALS");
            expectProblem(login(locked.email(), WRONG), 401, "AUTH_INVALID_CREDENTIALS");
        }

        login(user.email(), TestUsers.PASSWORD).andExpect(status().isOk());
        expectProblem(login(locked.email(), TestUsers.PASSWORD), 403, "AUTH_ACCOUNT_LOCKED");
    }

    // ------------------------------------------------------------------------------- CertificateVerifyRateLimiter

    @Test
    void certificateVerify_31stRequestFromOneIp_429RateLimitedWithRetryAfter_otherIpUnaffected_NFR03()
            throws Exception {
        String ip = randomIp();
        String otherIp = randomIp();
        while (otherIp.equals(ip)) {
            otherIp = randomIp();
        }
        // pin the application time for every request, so real time spent by the 30 requests refills nothing
        Instant start = clock.instant();

        for (int i = 0; i < 30; i++) {
            clock.setInstant(start);
            int status = certificate(ip).andReturn().getResponse().getStatus();
            assertThat(status).as("request %d", i + 1).isNotEqualTo(429);
        }
        clock.setInstant(start);
        MvcResult limited = expectProblem(certificate(ip), 429, "RATE_LIMITED");
        jsonPath("$.title").value("Quá nhiều yêu cầu").match(limited);
        jsonPath("$.instance").value("/api/v1/catalog/certificates/ABC").match(limited);
        assertThat(retryAfter(limited)).isEqualTo(2); // greedy refill: one token every 2 s

        clock.setInstant(start);
        assertThat(certificate(otherIp).andReturn().getResponse().getStatus()).isNotEqualTo(429);
        // X-Forwarded-For is never trusted: another value in it does not escape the limit
        clock.setInstant(start);
        expectProblem(mvc.perform(get("/api/v1/catalog/certificates/ABC")
                .header("X-Forwarded-For", otherIp)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })), 429, "RATE_LIMITED");

        // one token every 2 s, counted from the bucket's first request (a few ms after start): +3 s gives exactly one
        clock.setInstant(start.plusSeconds(3));
        assertThat(certificate(ip).andReturn().getResponse().getStatus()).isNotEqualTo(429);
        expectProblem(certificate(ip), 429, "RATE_LIMITED");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions certificate(String remoteAddr) throws Exception {
        return mvc.perform(get("/api/v1/catalog/certificates/ABC").with(request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        }));
    }

    private int sessionCount(Long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?").param(userId)
                .query(Integer.class).single();
    }

    private static long retryAfter(MvcResult result) {
        String value = result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(value).as("Retry-After").matches("[1-9][0-9]*");
        return Long.parseLong(value);
    }

    private static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }
}
