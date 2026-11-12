package com.edumy.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.edumy.shared.error.ConstraintViolationTranslator.Kind;
import com.edumy.shared.error.ConstraintViolationTranslator.TranslatedViolation;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SchemaFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every constraint family on real MySQL 8.4, provoked through BOTH persistence paths: {@code JdbcClient} and Hibernate
 * ({@code EntityManager.createNativeQuery(...).executeUpdate()}, entities do not exist yet so native SQL only).
 *
 * <p>Evidence that deciding by vendor code is necessary: the same violation surfaces as a different exception family
 * per path (observed on Spring 7 / Hibernate 7.4). {@code JdbcClient} throws Spring {@link DataAccessException}s:
 * DuplicateKeyException for 1062, DataIntegrityViolationException for 1451/1452, and a plain UncategorizedSQLException
 * (not a DataIntegrityViolationException) for 3819 and 1644. A raw {@code EntityManager} call throws Hibernate
 * exceptions, a {@link PersistenceException} subtree: ConstraintViolationException for 1062/1451/1452/3819 and
 * GenericJDBCException for 1644; none of them is a Spring exception. Both paths end in the same MySQL SQLException.
 *
 * <p>A failed Hibernate statement marks the surrounding transaction rollback-only, so each test method provokes one
 * violation. Tests are {@code @Transactional}: parent rows roll back.
 */
@MySqlSpringBootTest
@Transactional
class ConstraintViolationTranslatorIT {

    enum Path { JDBC_CLIENT, HIBERNATE_NATIVE }

    /** Test-only mappings to prove "known key -> its ErrorCode" against real MySQL messages. */
    private static final Map<String, ErrorCode> TEST_MAP = Map.of(
            "uq_roles_code", ErrorCode.CART_COURSE_ALREADY_OWNED,
            "fk_user_roles_user_id", ErrorCode.RESOURCE_NOT_FOUND,
            "ck_users_status", ErrorCode.VALIDATION_FAILED,
            "BR-02", ErrorCode.CART_OWN_COURSE);

    private final ConstraintViolationTranslator withMap = new ConstraintViolationTranslator(TEST_MAP);

    /** The production bean: its map is still empty, so every key falls back to DATA_CONFLICT. */
    @Autowired
    ConstraintViolationTranslator production;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager em;

    SchemaFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void duplicateRoleCode_1062_uniqueConstraintName(Path path) {
        // V6 seeded roles.code 'student'
        Throwable ex = provoke(path, "INSERT INTO roles (code, name) VALUES ('student', 'Học viên')");
        assertTranslated(ex, Kind.UNIQUE, 1062, "uq_roles_code", ErrorCode.CART_COURSE_ALREADY_OWNED);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void insertUserRoleOfUnknownUser_1452_fkName(Path path) {
        Throwable ex = provoke(path, "INSERT INTO user_roles (user_id, role_id) VALUES (-1, " + studentRoleId() + ")");
        assertTranslated(ex, Kind.FOREIGN_KEY, 1452, "fk_user_roles_user_id", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void deleteUserWithRole_1451_fkName(Path path) {
        long user = fx.user();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)").params(user, studentRoleId()).update();
        Throwable ex = provoke(path, "DELETE FROM users WHERE id = " + user);
        assertTranslated(ex, Kind.FOREIGN_KEY, 1451, "fk_user_roles_user_id", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void insertUserWithBadStatus_3819_checkName(Path path) {
        // CHECKs compare CAST(status AS BINARY): 'Active' is not 'active'
        Throwable ex = provoke(path, "INSERT INTO users (email, password_hash, full_name, status) VALUES "
                + "('translator" + SchemaFixtures.next() + "@edumy.vn', 'x', 'Nguyễn Văn A', 'Active')");
        assertTranslated(ex, Kind.CHECK, 3819, "ck_users_status", ErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void insertMainInstructorAsCoInstructor_1644_BR02(Path path) {
        long owner = fx.user();
        long course = fx.course(owner);
        long inviter = fx.user();
        Throwable ex = provoke(path, "INSERT INTO course_co_instructors (course_id, instructor_id, invited_by) "
                + "VALUES (" + course + ", " + owner + ", " + inviter + ")");
        assertTranslated(ex, Kind.TRIGGER, 1644, "BR-02", ErrorCode.CART_OWN_COURSE);
    }

    private long studentRoleId() {
        return jdbc.sql("SELECT id FROM roles WHERE code = 'student'").query(Long.class).single();
    }

    /** Runs the statement through the chosen path, asserts the family of the thrown exception and returns it. */
    private Throwable provoke(Path path, String sql) {
        try {
            switch (path) {
                case JDBC_CLIENT -> jdbc.sql(sql).update();
                case HIBERNATE_NATIVE -> em.createNativeQuery(sql).executeUpdate();
            }
        } catch (RuntimeException ex) {
            assertThat(ex).isInstanceOf(path == Path.JDBC_CLIENT ? DataAccessException.class : PersistenceException.class);
            return ex;
        }
        throw new AssertionError("statement did not fail: " + sql);
    }

    private void assertTranslated(Throwable ex, Kind kind, int vendor, String key, ErrorCode mapped) {
        TranslatedViolation known = withMap.translate(ex).orElseThrow();
        assertThat(known).isEqualTo(new TranslatedViolation(kind, vendor, key, mapped));

        TranslatedViolation unknown = production.translate(ex).orElseThrow();
        assertThat(unknown).isEqualTo(new TranslatedViolation(kind, vendor, key, ErrorCode.DATA_CONFLICT));
    }
}
