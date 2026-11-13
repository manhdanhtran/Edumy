package com.edumy.support;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import com.edumy.identity.security.JwtIssuer;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.TestUsers.TestUser;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Valid access tokens for a real session, for {@link MySqlSpringBootTest} tests: inserts a non-revoked
 * {@code refresh_tokens} row for a fresh {@code sid} (14 days, random hash) and signs a JWT with the application's
 * {@link JwtIssuer}. Rows disappear with the user ({@link TestUsers#deleteAll()}, ON DELETE CASCADE).
 */
public class JwtTestTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final JwtIssuer issuer;
    private final TestUsers users;

    public JwtTestTokens(JdbcClient jdbc, JwtIssuer issuer, TestUsers users) {
        this.jdbc = jdbc;
        this.issuer = issuer;
        this.users = users;
    }

    /** A new user holding {@code roles} with one active session. */
    public Session newUserSession(RoleCode... roles) {
        return session(users.create(roles));
    }

    /** A new active session (fresh sid) of an existing user, token carrying the user's roles. */
    public Session session(TestUser user) {
        String sid = UUID.randomUUID().toString();
        insertRefreshToken(user.id(), sid);
        return new Session(user, sid, issuer.issue(user.id(), user.roles(), sid).value());
    }

    /** Revokes every refresh token of {@code sid} in the database only (the caller evicts the cache if needed). */
    public void revokeInDatabase(String sid) {
        jdbc.sql("""
                        UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP(6), revoke_reason = 'logout'
                        WHERE family_id = ? AND revoked_at IS NULL
                        """)
                .param(sid)
                .update();
    }

    private void insertRefreshToken(Long userId, String sid) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        jdbc.sql("""
                        INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP(6) + INTERVAL 14 DAY)
                        """)
                .params(userId, sid, HexFormat.of().formatHex(random))
                .update();
    }

    /** A user, its session id and a signed access token of that session. */
    public record Session(TestUser user, String sid, String accessToken) {

        public Long userId() {
            return user.id();
        }

        /** Value of the {@code Authorization} header. */
        public String bearer() {
            return "Bearer " + accessToken;
        }

        @Override
        public String toString() {
            return "Session[userId=" + user.id() + ", sid=" + sid + ", accessToken=****]";
        }
    }
}
