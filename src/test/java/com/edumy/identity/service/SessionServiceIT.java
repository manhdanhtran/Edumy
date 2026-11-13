package com.edumy.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.edumy.identity.dto.response.AuthTokenResponse;
import com.edumy.identity.entity.RevokeReason;
import com.edumy.identity.security.SessionStatusCache;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Bulk session revokes and their after-commit cache eviction (ADR-002, docs/ai/04 §4.1). */
@MySqlSpringBootTest
class SessionServiceIT {

    @Autowired
    SessionService sessionService;
    @Autowired
    SessionStatusCache sessionStatus;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    TestUsers users;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    @Test
    void dummyHash_sameEncoderCost12_NFR02() {
        assertThat(sessionService.dummyHash()).startsWith("$2a$12$");
        assertThat(passwordEncoder.encode("x")).startsWith("$2a$12$");
    }

    @Test
    void revokeAllExcept_keepsGivenSid_endsOthers_UCG12() {
        TestUser user = users.create(RoleCode.STUDENT);
        Session first = tokens.session(user);
        Session kept = tokens.session(user);
        Session third = tokens.session(user);
        List.of(first, kept, third).forEach(s -> assertThat(sessionStatus.isSessionActive(s.sid())).isTrue());

        int revoked = sessionService.revokeAllExcept(user.id(), kept.sid(), RevokeReason.PASSWORD_CHANGED);

        assertThat(revoked).isEqualTo(2);
        assertThat(sessionStatus.isSessionActive(first.sid())).isFalse();
        assertThat(sessionStatus.isSessionActive(third.sid())).isFalse();
        assertThat(sessionStatus.isSessionActive(kept.sid())).isTrue();
        assertThat(reasons(first.sid())).containsExactly("password_changed");
        assertThat(reasons(third.sid())).containsExactly("password_changed");
        assertThat(reasons(kept.sid())).containsExactly((String) null);
    }

    @Test
    void revokeAll_endsEverySessionOfUserOnly_D05() {
        TestUser user = users.create(RoleCode.STUDENT);
        Session a = tokens.session(user);
        Session b = tokens.session(user);
        Session other = tokens.newUserSession(RoleCode.STUDENT);
        List.of(a, b, other).forEach(s -> assertThat(sessionStatus.isSessionActive(s.sid())).isTrue());

        int revoked = sessionService.revokeAll(user.id(), RevokeReason.ACCOUNT_LOCKED);

        assertThat(revoked).isEqualTo(2);
        assertThat(sessionStatus.isSessionActive(a.sid())).isFalse();
        assertThat(sessionStatus.isSessionActive(b.sid())).isFalse();
        assertThat(sessionStatus.isSessionActive(other.sid())).isTrue();
        assertThat(reasons(a.sid())).containsExactly("account_locked");
        assertThat(sessionService.revokeAll(user.id(), RevokeReason.ACCOUNT_LOCKED)).isZero();
    }

    @Test
    void logout_endsOnlyThatSession_UCG10() {
        TestUser user = users.create(RoleCode.STUDENT);
        Session ended = tokens.session(user);
        Session other = tokens.session(user);
        assertThat(sessionStatus.isSessionActive(ended.sid())).isTrue();

        sessionService.logout(ended.sid());

        assertThat(sessionStatus.isSessionActive(ended.sid())).isFalse();
        assertThat(sessionStatus.isSessionActive(other.sid())).isTrue();
        assertThat(reasons(ended.sid())).containsExactly("logout");
    }

    @Test
    void toString_neverShowsTokens_NFR08() {
        SessionTokens result = new SessionTokens(
                new AuthTokenResponse("ACCESS-SECRET", null), "REFRESH-SECRET",
                Duration.ofDays(14));

        assertThat(result.toString()).doesNotContain("ACCESS-SECRET").doesNotContain("REFRESH-SECRET");
    }

    private List<String> reasons(String sid) {
        return jdbc.sql("SELECT revoke_reason FROM refresh_tokens WHERE family_id = ? ORDER BY id")
                .param(sid)
                .query((rs, n) -> rs.getString(1))
                .list();
    }
}
