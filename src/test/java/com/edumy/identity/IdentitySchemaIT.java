package com.edumy.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.edumy.support.MySqlJdbcTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * V1__identity.sql on real MySQL 8.4: every CHECK / UNIQUE / FK of users, roles, user_roles, user_tokens
 * (EDU-DDD-001 §5.1–5.4, §6 #1–4, §8 rows 1–2) and refresh_tokens (ADR-002). Each violation asserts the
 * constraint name, because the error translator maps names to API error codes (docs/ai/03).
 * Each test runs in a rolled-back transaction (@JdbcTest).
 */
@MySqlJdbcTest
class IdentitySchemaIT {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final LocalDateTime FAR_FUTURE = LocalDateTime.of(2999, 1, 1, 0, 0);

    @Autowired
    JdbcClient jdbc;

    // ---------------------------------------------------------------------------------------------------------- users

    @Test
    void insertUser_valid_appliesDefaults() {
        long id = insertUser("valid@example.com");

        var row = jdbc.sql("SELECT status, is_email_verified, created_at, updated_at FROM users WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(row.get("is_email_verified")).isEqualTo(false);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertUser_duplicateEmail_rejected_BR01() {
        insertUser("dup@example.com");
        assertViolates("uq_users_email", () -> insertUser("dup@example.com"));
    }

    /** users.email is utf8mb4_bin: accents are significant (PostgreSQL behaviour), unlike the ai_ci default. */
    @Test
    void insertUser_emailsDifferingOnlyByAccent_areDistinct_BR01() {
        long accented = insertUser("é@example.com");
        long plain = insertUser("e@example.com");

        assertThat(accented).isNotEqualTo(plain);
        assertThat(jdbc.sql("SELECT id FROM users WHERE email = ?").param("é@example.com").query(Long.class).list())
                .containsExactly(accented);
        assertThat(jdbc.sql("SELECT id FROM users WHERE email = ?").param("e@example.com").query(Long.class).list())
                .containsExactly(plain);
    }

    @Test
    void emailColumn_usesBinaryCollation() {
        assertThat(jdbc.sql("""
                SELECT COLLATION_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'email'""")
                .query(String.class).single()).isEqualTo("utf8mb4_bin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Upper@example.com", " lead@example.com", "trail@example.com "})
    void insertUser_notNormalizedEmail_rejected_BR01(String email) {
        assertCheckViolated("ck_users_email_normalized", () -> insertUser(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "Active", "LOCKED", "actívé"})
    void insertUser_invalidStatus_rejected(String status) {
        assertCheckViolated("ck_users_status", () -> jdbc.sql("""
                INSERT INTO users (email, password_hash, full_name, status) VALUES (?, 'x', 'Nguyễn Văn A', ?)""")
                .params(uniqueEmail(), status).update());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void insertUser_blankFullName_rejected(String fullName) {
        assertCheckViolated("ck_users_full_name", () -> jdbc.sql("""
                INSERT INTO users (email, password_hash, full_name) VALUES (?, 'x', ?)""")
                .params(uniqueEmail(), fullName).update());
    }

    @Test
    void updateUser_anyColumn_refreshesUpdatedAt() {
        long id = insertUser(uniqueEmail());
        jdbc.sql("UPDATE users SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();

        jdbc.sql("UPDATE users SET status = 'locked' WHERE id = ?").param(id).update();

        LocalDateTime updatedAt = jdbc.sql("SELECT updated_at FROM users WHERE id = ?")
                .param(id).query(LocalDateTime.class).single();
        assertThat(updatedAt).isAfter(LocalDateTime.of(2020, 1, 2, 0, 0));
    }

    // ---------------------------------------------------------------------------------------------------------- roles

    @Test
    void insertRole_valid_storesRow() {
        long id = roleId("instructor");

        var row = jdbc.sql("SELECT code, created_at FROM roles WHERE id = ?").param(id).query().singleRow();
        assertThat(row.get("code")).isEqualTo("instructor");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void insertRole_duplicateCode_rejected() {
        roleId("student");
        assertViolates("uq_roles_code", () -> jdbc.sql("INSERT INTO roles (code, name) VALUES ('student', 'Học viên 2')")
                .update());
    }

    @ParameterizedTest
    @ValueSource(strings = {"teacher", "Admin", "STUDENT"})
    void insertRole_unknownCode_rejected(String code) {
        assertCheckViolated("ck_roles_code", () -> jdbc.sql("INSERT INTO roles (code, name) VALUES (?, 'X')")
                .param(code).update());
    }

    // ----------------------------------------------------------------------------------------------------- user_roles

    @Test
    void insertUserRole_valid_appliesDefaults() {
        long userId = insertUser(uniqueEmail());
        long id = insertUserRole(userId, roleId("student"), null);

        var row = jdbc.sql("SELECT is_active, assigned_by, assigned_at, updated_at FROM user_roles WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("assigned_by")).isNull();
        assertThat(row.get("assigned_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertUserRole_sameRoleTwice_rejected_AC16() {
        long userId = insertUser(uniqueEmail());
        long roleId = roleId("student");
        insertUserRole(userId, roleId, null);
        assertViolates("uq_user_roles_user_role", () -> insertUserRole(userId, roleId, null));
    }

    @Test
    void insertUserRole_unknownUser_rejected() {
        assertViolates("fk_user_roles_user_id", () -> insertUserRole(-1L, roleId("student"), null));
    }

    @Test
    void insertUserRole_unknownRole_rejected() {
        long userId = insertUser(uniqueEmail());
        assertViolates("fk_user_roles_role_id", () -> insertUserRole(userId, -1L, null));
    }

    @Test
    void insertUserRole_unknownAssigner_rejected() {
        long userId = insertUser(uniqueEmail());
        assertViolates("fk_user_roles_assigned_by", () -> insertUserRole(userId, roleId("student"), -1L));
    }

    @Test
    void updateUserRole_revoke_refreshesUpdatedAt() {
        long id = insertUserRole(insertUser(uniqueEmail()), roleId("instructor"), null);
        jdbc.sql("UPDATE user_roles SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();

        jdbc.sql("UPDATE user_roles SET is_active = FALSE WHERE id = ?").param(id).update();

        LocalDateTime updatedAt = jdbc.sql("SELECT updated_at FROM user_roles WHERE id = ?")
                .param(id).query(LocalDateTime.class).single();
        assertThat(updatedAt).isAfter(LocalDateTime.of(2020, 1, 2, 0, 0));
    }

    // ---------------------------------------------------------------------------------------------------- user_tokens

    @Test
    void insertUserToken_valid_unusedByDefault() {
        long id = insertUserToken(insertUser(uniqueEmail()), hash(), "email_verification", FAR_FUTURE);

        var row = jdbc.sql("SELECT used_at, created_at FROM user_tokens WHERE id = ?").param(id).query().singleRow();
        assertThat(row.get("used_at")).isNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void insertUserToken_duplicateHash_rejected_BR34() {
        long userId = insertUser(uniqueEmail());
        String hash = hash();
        insertUserToken(userId, hash, "password_reset", FAR_FUTURE);
        assertViolates("uq_user_tokens_token_hash", () -> insertUserToken(userId, hash, "password_reset", FAR_FUTURE));
    }

    @Test
    void insertUserToken_unknownUser_rejected() {
        assertViolates("fk_user_tokens_user_id", () -> insertUserToken(-1L, hash(), "password_reset", FAR_FUTURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"magic_link", "Password_Reset", "EMAIL_VERIFICATION"})
    void insertUserToken_unknownType_rejected(String type) {
        long userId = insertUser(uniqueEmail());
        assertCheckViolated("ck_user_tokens_type", () -> insertUserToken(userId, hash(), type, FAR_FUTURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01 00:00:00", "2026-01-02 00:00:00"})
    void insertUserToken_expiryNotAfterCreation_rejected(String expiresAt) {
        long userId = insertUser(uniqueEmail());
        assertCheckViolated("ck_user_tokens_expiry", () -> jdbc.sql("""
                INSERT INTO user_tokens (user_id, token_hash, token_type, expires_at, created_at)
                VALUES (?, ?, 'password_reset', ?, '2026-01-02 00:00:00')""")
                .params(userId, hash(), expiresAt).update());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789",  // uppercase hex
            "g000000000000000000000000000000000000000000000000000000000000000",  // not hex
            "abc"})                                                              // too short
    void insertUserToken_hashNotLowerHex_rejected(String hash) {
        long userId = insertUser(uniqueEmail());
        assertCheckViolated("ck_user_tokens_hash_hex", () -> insertUserToken(userId, hash, "password_reset", FAR_FUTURE));
    }

    // ------------------------------------------------------------------------------------------------- refresh_tokens

    @Test
    void insertRefreshToken_activeAndRevoked_valid() {
        long userId = insertUser(uniqueEmail());
        insertRefreshToken(userId, hash(), null, null);
        insertRefreshToken(userId, hash(), "2026-01-01 00:00:00", "rotated");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?").param(userId)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void insertRefreshToken_duplicateHash_rejected() {
        long userId = insertUser(uniqueEmail());
        String hash = hash();
        insertRefreshToken(userId, hash, null, null);
        assertViolates("uq_refresh_tokens_token_hash", () -> insertRefreshToken(userId, hash, null, null));
    }

    @Test
    void insertRefreshToken_unknownUser_rejected() {
        assertViolates("fk_refresh_tokens_user_id", () -> insertRefreshToken(-1L, hash(), null, null));
    }

    @Test
    void insertRefreshToken_hashNotLowerHex_rejected() {
        long userId = insertUser(uniqueEmail());
        assertCheckViolated("ck_refresh_tokens_hash_hex", () -> insertRefreshToken(userId, hash().toUpperCase(), null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"banned", "Logout", "ROTATED"})
    void insertRefreshToken_unknownReason_rejected(String reason) {
        long userId = insertUser(uniqueEmail());
        assertCheckViolated("ck_refresh_tokens_reason",
                () -> insertRefreshToken(userId, hash(), "2026-01-01 00:00:00", reason));
    }

    @Test
    void insertRefreshToken_revokedWithoutReason_rejected() {
        long userId = insertUser(uniqueEmail());
        assertCheckViolated("ck_refresh_tokens_revoked_pair",
                () -> insertRefreshToken(userId, hash(), "2026-01-01 00:00:00", null));
    }

    @Test
    void insertRefreshToken_reasonWithoutRevokedAt_rejected() {
        long userId = insertUser(uniqueEmail());
        assertCheckViolated("ck_refresh_tokens_revoked_pair", () -> insertRefreshToken(userId, hash(), null, "logout"));
    }

    // ------------------------------------------------------------------------------------ ON DELETE (DDD §6 #1–4)

    @Test
    void deleteUser_withTokensOnly_cascadesTokens() {
        long userId = insertUser(uniqueEmail());
        insertUserToken(userId, hash(), "email_verification", FAR_FUTURE);
        insertRefreshToken(userId, hash(), null, null);

        jdbc.sql("DELETE FROM users WHERE id = ?").param(userId).update();

        assertThat(count("user_tokens", userId)).isZero();
        assertThat(count("refresh_tokens", userId)).isZero();
    }

    @Test
    void deleteUser_holdingRole_restricted() {
        long userId = insertUser(uniqueEmail());
        insertUserRole(userId, roleId("student"), null);
        assertViolates("fk_user_roles_user_id", () -> jdbc.sql("DELETE FROM users WHERE id = ?").param(userId).update());
    }

    @Test
    void deleteUser_whoAssignedARole_restricted() {
        long adminId = insertUser(uniqueEmail());
        insertUserRole(insertUser(uniqueEmail()), roleId("instructor"), adminId);
        assertViolates("fk_user_roles_assigned_by",
                () -> jdbc.sql("DELETE FROM users WHERE id = ?").param(adminId).update());
    }

    @Test
    void deleteRole_inUse_restricted() {
        long roleId = roleId("admin");
        insertUserRole(insertUser(uniqueEmail()), roleId, null);
        assertViolates("fk_user_roles_role_id", () -> jdbc.sql("DELETE FROM roles WHERE id = ?").param(roleId).update());
    }

    // -------------------------------------------------------------------------------------------- schema metadata

    @Test
    void foreignKeys_haveDddNamesAndDeleteRules() {
        var rules = jdbc.sql("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND TABLE_NAME IN ('users', 'roles', 'user_roles', 'user_tokens', 'refresh_tokens')
                ORDER BY CONSTRAINT_NAME""")
                .query((rs, n) -> rs.getString(1) + "=" + rs.getString(2)).list();
        assertThat(rules).containsExactly(
                "fk_refresh_tokens_user_id=CASCADE",
                "fk_user_roles_assigned_by=RESTRICT",
                "fk_user_roles_role_id=RESTRICT",
                "fk_user_roles_user_id=RESTRICT",
                "fk_user_tokens_user_id=CASCADE");
    }

    @Test
    void indexes_haveExpectedColumns() {
        assertThat(indexColumns("user_roles", "uq_user_roles_user_role")).containsExactly("user_id", "role_id");
        assertThat(indexColumns("user_roles", "ix_user_roles_role")).containsExactly("role_id");
        assertThat(indexColumns("user_roles", "ix_user_roles_assigned_by")).containsExactly("assigned_by");
        assertThat(indexColumns("user_tokens", "ix_user_tokens_active"))
                .containsExactly("user_id", "token_type", "used_at");
        assertThat(indexColumns("refresh_tokens", "ix_refresh_tokens_user")).containsExactly("user_id", "revoked_at");
        assertThat(indexColumns("refresh_tokens", "ix_refresh_tokens_family"))
                .containsExactly("family_id", "revoked_at");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /**
     * CHECK violations: MySQL error 3819 (SQLSTATE HY000). Spring JDBC 7.0 does not list 3819 in its MySQL
     * dataIntegrityViolationCodes, so it surfaces as UncategorizedSQLException, not DataIntegrityViolationException.
     * The API error translator must handle this (open question raised in the V1 session).
     */
    private void assertCheckViolated(String constraint, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).isEqualTo("Check constraint '" + constraint + "' is violated.");
                });
    }

    /** UNIQUE (1062) and FK (1451/1452) violations, translated by Spring to DataIntegrityViolationException. */
    private void assertViolates(String constraint, ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(NestedExceptionUtils.getMostSpecificCause(e).getMessage())
                        .contains(constraint));
    }

    private static String uniqueEmail() {
        return "u" + SEQ.incrementAndGet() + "@example.com";
    }

    private static String hash() {
        return String.format("%064x", SEQ.incrementAndGet());
    }

    private long insertUser(String email) {
        return insertReturningId(jdbc.sql("""
                INSERT INTO users (email, password_hash, full_name) VALUES (?, '$2a$12$notARealHash', 'Nguyễn Văn A')""")
                .param(email));
    }

    /** Returns the role id, inserting the row if absent (V6__reference_data.sql will seed these codes later). */
    private long roleId(String code) {
        return jdbc.sql("SELECT id FROM roles WHERE code = ?").param(code).query(Long.class).optional()
                .orElseGet(() -> insertReturningId(jdbc.sql("INSERT INTO roles (code, name) VALUES (?, ?)")
                        .params(code, "Vai trò " + code)));
    }

    private long insertUserRole(long userId, long roleId, Long assignedBy) {
        return insertReturningId(jdbc.sql("INSERT INTO user_roles (user_id, role_id, assigned_by) VALUES (?, ?, ?)")
                .params(userId, roleId, assignedBy));
    }

    private long insertUserToken(long userId, String hash, String type, LocalDateTime expiresAt) {
        return insertReturningId(jdbc.sql("""
                INSERT INTO user_tokens (user_id, token_hash, token_type, expires_at) VALUES (?, ?, ?, ?)""")
                .params(userId, hash, type, expiresAt));
    }

    private long insertRefreshToken(long userId, String hash, String revokedAt, String reason) {
        return insertReturningId(jdbc.sql("""
                INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at, revoked_at, revoke_reason)
                VALUES (?, '6f1c2a3e-0000-4000-8000-000000000001', ?, ?, ?, ?)""")
                .params(userId, hash, FAR_FUTURE, revokedAt, reason));
    }

    private long insertReturningId(JdbcClient.StatementSpec spec) {
        KeyHolder keys = new GeneratedKeyHolder();
        spec.update(keys);
        return keys.getKey().longValue();
    }

    private long count(String table, long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?").param(userId)
                .query(Long.class).single();
    }

    private List<String> indexColumns(String table, String index) {
        return jdbc.sql("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX""")
                .params(table, index).query(String.class).list();
    }
}
