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
 * A known key maps to its {@code ErrorCode}; an unknown or unparseable key maps to {@code DATA_CONFLICT} and the full
 * cause is logged at WARN. Anything that is not one of these violations yields {@link Optional#empty()}.
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
     * Constraint name / BR code → ErrorCode of the production bean. Empty for now: entries are added when the owning
     * module defines its ErrorCode (P03/P06), see docs/ai/05 §5.2 (e.g. "uq_carts_student" → CART_COURSE_ALREADY_OWNED).
     */
    private static final Map<String, ErrorCode> KNOWN_KEYS = Map.of();

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
        if (known == null) {
            log.warn("Unmapped {} violation: vendorCode={}, key={}, message={}",
                    kind, vendor, key, root.getMessage(), ex);
        }
        return Optional.of(new TranslatedViolation(kind, vendor, key, known != null ? known : ErrorCode.DATA_CONFLICT));
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
