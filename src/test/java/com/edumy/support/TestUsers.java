package com.edumy.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.edumy.shared.security.RoleCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Committed test users for {@link MySqlSpringBootTest} tests that go through HTTP (security, login): each user gets a
 * UUID-unique email, is {@code active} and holds the given roles. Call {@link #deleteAll()} in {@code @AfterEach}: it
 * deletes the users created since the last call together with their roles, refresh tokens and account tokens, so no
 * rows are left in the shared database (docs/ai/07). Rows of other tables referencing these users must be deleted
 * first by the test.
 */
public class TestUsers {

    /** Plain password of every test user (letters + digits, docs/ai/04 §4.2). */
    public static final String PASSWORD = "Passw0rd123";
    // computed once at a low cost: tests only need a hash that matches PASSWORD, not production strength
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    private final JdbcClient jdbc;
    private final List<Long> created = new ArrayList<>();
    private final List<String> trackedEmails = new ArrayList<>();

    public TestUsers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new active user holding {@code roles} (none = no role row). */
    public synchronized TestUser create(RoleCode... roles) {
        String email = "test-" + UUID.randomUUID() + "@edumy.test";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO users (email, password_hash, full_name, is_email_verified, status)
                        VALUES (?, ?, 'Người dùng kiểm thử', TRUE, 'active')
                        """)
                .params(email, PASSWORD_HASH)
                .update(keys);
        Long id = keys.getKey().longValue();
        created.add(id);
        Set<RoleCode> roleSet = EnumSet.noneOf(RoleCode.class);
        roleSet.addAll(Arrays.asList(roles));
        for (RoleCode role : roleSet) {
            jdbc.sql("INSERT INTO user_roles (user_id, role_id, is_active) SELECT ?, id, TRUE FROM roles WHERE code = ?")
                    .params(id, role.code())
                    .update();
        }
        return new TestUser(id, email, roleSet);
    }

    /** Sets {@code users.status = locked} directly (no session revoke, no cache eviction). */
    public void lock(Long userId) {
        jdbc.sql("UPDATE users SET status = 'locked' WHERE id = ?").param(userId).update();
    }

    /** Sets {@code users.is_email_verified = false} (test users are created verified). */
    public void markEmailUnverified(Long userId) {
        jdbc.sql("UPDATE users SET is_email_verified = FALSE WHERE id = ?").param(userId).update();
    }

    /** Sets the user's {@code role} row inactive, as an admin revoke would (UC-A05, D-04); no cache eviction. */
    public void revokeRole(Long userId, RoleCode role) {
        jdbc.sql("""
                        UPDATE user_roles SET is_active = FALSE
                        WHERE user_id = ? AND role_id = (SELECT id FROM roles WHERE code = ?)
                        """)
                .params(userId, role.code())
                .update();
    }

    /**
     * A user created through the API (e.g. {@code POST /auth/register}) with this email (any case / outer spaces) is
     * deleted by the next {@link #deleteAll()} too. Call it before the request, so a failing assertion still cleans up.
     */
    public synchronized void deleteLaterByEmail(String email) {
        trackedEmails.add(email.strip().toLowerCase(Locale.ROOT));
    }

    /** Id of the user with this normalized email. */
    public Long idByEmail(String normalizedEmail) {
        return jdbc.sql("SELECT id FROM users WHERE email = ?").param(normalizedEmail).query(Long.class).single();
    }

    /**
     * Deletes every user created or tracked since the last call (refresh_tokens and user_tokens go by ON DELETE
     * CASCADE).
     */
    public synchronized void deleteAll() {
        for (String email : trackedEmails) {
            jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(Long.class).optional()
                    .ifPresent(created::add);
        }
        trackedEmails.clear();
        // every role row first: a row of one test user may name another one as assigned_by (admin grant, UC-A04)
        for (Long id : created) {
            jdbc.sql("DELETE FROM user_roles WHERE user_id = ?").param(id).update();
        }
        for (Long id : created) {
            jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
        }
        created.clear();
    }

    public record TestUser(Long id, String email, Set<RoleCode> roles) {

        public TestUser {
            roles = Set.copyOf(roles);
        }
    }
}
