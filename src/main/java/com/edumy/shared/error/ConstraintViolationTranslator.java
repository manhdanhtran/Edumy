package com.edumy.shared.error;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a persistence failure is a database constraint violation and which {@link ErrorCode} it maps to
 * (docs/ai/05-api-contract.md §5.2). It walks the cause chain down to the root {@link SQLException} and decides by the
 * MySQL <b>vendor error code</b>, never by the Spring/Hibernate exception type: JPA wraps errors in
 * {@code PersistenceException} subclasses, {@code JdbcClient} in {@code DataAccessException}, and Spring JDBC does not
 * map CHECK error 3819 to {@code DataIntegrityViolationException}. It does not replace Spring's
 * {@code sql-error-codes.xml}.
 *
 * <ul>
 *   <li>1062 UNIQUE: key = constraint name in {@code for key 'table.constraint'}</li>
 *   <li>1451 / 1452 FK: key = name after {@code CONSTRAINT} in the message</li>
 *   <li>3819 CHECK: key = name in {@code Check constraint 'ck_…' is violated.}</li>
 *   <li>1644 or SQLState 45000 trigger SIGNAL: key = BR code at the start of the message ({@code BR-02: …})</li>
 * </ul>
 * A known key maps to its {@code ErrorCode}. Otherwise the default of the violation kind applies (docs/ai/05 §5.2):
 * UNIQUE and FK 1451 → {@code DATA_CONFLICT} (409, WARN); FK 1452 (missing parent) → {@code RESOURCE_NOT_FOUND} (404,
 * WARN); CHECK and trigger → {@code INTERNAL_ERROR} (500, ERROR: the application should have prevented it). The
 * constraint name is only logged, never put in the response. Anything that is not one of these violations yields
 * {@link Optional#empty()}.
 */
@Component
public class ConstraintViolationTranslator {

    /** Kind of violation, derived from the vendor code. */
    public enum Kind { UNIQUE, FOREIGN_KEY, CHECK, TRIGGER }

    /**
     * @param vendorCode MySQL error code of the root SQLException
     * @param key        constraint name or BR code; {@code null} when the message could not be parsed
     * @param code       the API error code ({@code DATA_CONFLICT} for an unknown key)
     */
    public record TranslatedViolation(Kind kind, int vendorCode, String key, ErrorCode code) {
    }

    /**
     * Constraint name / BR code → ErrorCode of the production bean (docs/ai/05 §5.2).
     *
     * <p>When one row breaks two overlapping CHECKs, MySQL reports only one of them (the DDD expression of the second
     * only holds for valid values: an unknown {@code lessons.content_type} breaks {@code ck_lessons_type} and
     * {@code ck_lessons_content_match}, etc.), so BOTH names of each pair are mapped, to the same code. They stay
     * {@code INTERNAL_ERROR}: such a value means the application skipped validation. Listing them makes a future change
     * of one name impossible without the other (see ConstraintViolationTranslatorTest).
     */
    private static final Map<String, ErrorCode> KNOWN_KEYS = Map.ofEntries(
            Map.entry("BR-02", ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN),
            Map.entry("BR-19", ErrorCode.COURSE_DELETE_FORBIDDEN),
            Map.entry("BR-24", ErrorCode.LESSON_COURSE_MISMATCH),
            Map.entry("BR-37", ErrorCode.LESSON_COURSE_MISMATCH),
            Map.entry("uq_cart_items_cart_course", ErrorCode.CART_ITEM_DUPLICATE),
            Map.entry("uq_users_email", ErrorCode.EMAIL_ALREADY_REGISTERED), // concurrent registrations (UC-G05 E1)
            Map.entry("uq_categories_name", ErrorCode.CATEGORY_ALREADY_EXISTS), // UC-A06/A07 E, concurrent writes
            Map.entry("uq_categories_slug", ErrorCode.CATEGORY_ALREADY_EXISTS),
            Map.entry("uq_cci_course_instructor", ErrorCode.COURSE_CO_INSTRUCTOR_ALREADY_INVITED), // UC-I13 E2
            // overlapping CHECK pairs (see above)
            Map.entry("ck_lessons_type", ErrorCode.INTERNAL_ERROR),
            Map.entry("ck_lessons_content_match", ErrorCode.INTERNAL_ERROR),
            Map.entry("ck_orders_status", ErrorCode.INTERNAL_ERROR),
            Map.entry("ck_orders_state", ErrorCode.INTERNAL_ERROR),
            Map.entry("ck_enrollments_source", ErrorCode.INTERNAL_ERROR),
            Map.entry("ck_enrollments_source_order", ErrorCode.INTERNAL_ERROR));

    private static final int UNIQUE = 1062;
    private static final int FK_PARENT_ROW_REFERENCED = 1451;
    private static final int FK_PARENT_ROW_MISSING = 1452;
    private static final int CHECK = 3819;
    private static final int SIGNAL = 1644;
    private static final String SQL_STATE_SIGNAL = "45000";

    private static final Pattern UNIQUE_KEY = Pattern.compile("for key '([^']+)'\\s*$", Pattern.DOTALL);
    private static final Pattern FK_NAME = Pattern.compile("CONSTRAINT `([^`]+)`");
    private static final Pattern CHECK_NAME = Pattern.compile("Check constraint '([^']+)' is violated");
    private static final Pattern BR_CODE = Pattern.compile("^(BR-\\d+):");

    private static final Logger log = LoggerFactory.getLogger(ConstraintViolationTranslator.class);

    private final Map<String, ErrorCode> knownKeys;

    public ConstraintViolationTranslator() {
        this(KNOWN_KEYS);
    }

    public ConstraintViolationTranslator(Map<String, ErrorCode> knownKeys) {
        this.knownKeys = Map.copyOf(knownKeys);
    }

    public Optional<TranslatedViolation> translate(Throwable ex) {
        SQLException root = rootSqlException(ex);
        if (root == null) {
            return Optional.empty();
        }
        int vendor = root.getErrorCode();
        Kind kind = kindOf(vendor, root.getSQLState());
        if (kind == null) {
            return Optional.empty();
        }
        String key = parseKey(kind, root.getMessage());
        ErrorCode known = key == null ? null : knownKeys.get(key);
        ErrorCode code = known != null ? known : defaultCode(vendor, kind);
        logOutcome(kind, vendor, key, known != null, code, root, ex);
        return Optional.of(new TranslatedViolation(kind, vendor, key, code));
    }

    /** Default per kind when the key is not mapped (docs/ai/05 §5.2). */
    private static ErrorCode defaultCode(int vendor, Kind kind) {
        return switch (kind) {
            case UNIQUE -> ErrorCode.DATA_CONFLICT;
            case FOREIGN_KEY -> vendor == FK_PARENT_ROW_MISSING ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.DATA_CONFLICT;
            case CHECK, TRIGGER -> ErrorCode.INTERNAL_ERROR;
        };
    }

    /**
     * Unmapped UNIQUE / FK → WARN; anything ending in INTERNAL_ERROR (unmapped CHECK or trigger, or a key mapped to it)
     * → ERROR, because the application should have prevented it. The full cause is the last argument.
     */
    private static void logOutcome(Kind kind, int vendor, String key, boolean mapped, ErrorCode code,
            SQLException root, Throwable ex) {
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Database {} violation the application should have prevented: vendorCode={}, key={}, message={}",
                    kind, vendor, key, root.getMessage(), ex);
        } else if (!mapped) {
            log.warn("Unmapped {} violation: vendorCode={}, key={}, message={}", kind, vendor, key, root.getMessage(), ex);
        }
    }

    private static Kind kindOf(int vendor, String sqlState) {
        return switch (vendor) {
            case UNIQUE -> Kind.UNIQUE;
            case FK_PARENT_ROW_REFERENCED, FK_PARENT_ROW_MISSING -> Kind.FOREIGN_KEY;
            case CHECK -> Kind.CHECK;
            case SIGNAL -> Kind.TRIGGER;
            default -> SQL_STATE_SIGNAL.equals(sqlState) ? Kind.TRIGGER : null;
        };
    }

    private static String parseKey(Kind kind, String message) {
        if (message == null) {
            return null;
        }
        return switch (kind) {
            case UNIQUE -> {
                Matcher m = UNIQUE_KEY.matcher(message);
                if (!m.find()) {
                    yield null;
                }
                String qualified = m.group(1); // 'carts.uq_carts_student' -> constraint name after the last '.'
                yield qualified.substring(qualified.lastIndexOf('.') + 1);
            }
            case FOREIGN_KEY -> firstGroup(FK_NAME, message);
            case CHECK -> firstGroup(CHECK_NAME, message);
            case TRIGGER -> firstGroup(BR_CODE, message);
        };
    }

    private static String firstGroup(Pattern pattern, String message) {
        Matcher m = pattern.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    /** The deepest SQLException of the cause chain; the visited set guards against cyclic chains. */
    private static SQLException rootSqlException(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        SQLException root = null;
        for (Throwable t = ex; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql) {
                root = sql;
            }
        }
        return root;
    }
}
