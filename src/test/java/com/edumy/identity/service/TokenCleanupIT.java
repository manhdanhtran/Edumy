package com.edumy.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SRS §6: codes that expired more than 30 days ago are deleted by the timer; younger ones and valid ones stay. */
@MySqlSpringBootTest
class TokenCleanupIT {

    @Autowired
    TokenCleanupService cleanup;
    @Autowired
    TestUsers users;
    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        users.deleteAll();
    }

    String token(TestUser user, Instant expiresAt) {
        String hash = HexFormat.of().formatHex(UUID.randomUUID().toString().repeat(2).getBytes()).substring(0, 64);
        jdbc.sql("""
                        INSERT INTO user_tokens (user_id, token_type, token_hash, expires_at, created_at)
                        VALUES (?, 'email_verification', ?, ?, ?)""")
                .params(user.id(), hash, Timestamp.from(expiresAt), Timestamp.from(expiresAt.minus(1, ChronoUnit.DAYS)))
                .update();
        return hash;
    }

    boolean exists(String hash) {
        return jdbc.sql("SELECT COUNT(*) FROM user_tokens WHERE token_hash = ?").param(hash).query(Long.class)
                .single() == 1;
    }

    @Test
    void expiredMoreThan30DaysAgo_deleted_youngerAndValidKept() {
        TestUser user = users.create(RoleCode.STUDENT);
        Instant now = Instant.now();
        String old = token(user, now.minus(31, ChronoUnit.DAYS));
        String recent = token(user, now.minus(29, ChronoUnit.DAYS));
        String valid = token(user, now.plus(1, ChronoUnit.DAYS));

        int deleted = cleanup.deleteExpiredCodes();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(valid)).isTrue();
        assertThat(cleanup.deleteExpiredCodes()).isZero(); // idempotent
    }
}
