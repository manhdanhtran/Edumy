package com.edumy.support;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.edumy.identity.security.JwtIssuer;
import com.edumy.shared.config.CacheConfig;
import com.edumy.shared.security.RoleCode;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Access tokens for the dev-seed users in {@link MySqlSeedSpringBootTest} tests, <b>without writing anything</b>.
 * <p>A real session needs a non-revoked {@code refresh_tokens} row for the JWT {@code sid} ({@code JwtSessionValidator},
 * ADR-002), and the seed has none. Inserting one (as {@link JwtTestTokens} does) or logging in through
 * {@code /auth/login} would write into the shared read-only database {@code edumy_seed}. Instead this helper uses one
 * fixed {@code sid} per user and marks it active in the {@value CacheConfig#SESSION_STATUS} cache (the answer the
 * validator would cache after reading such a row), refreshed on every call so the 30 s TTL never runs out during a
 * request. Everything else stays real: signature, expiry (application {@code Clock}), issuer, and the user-status check
 * ({@code users.status} read from the seed: user {@value #LOCKED} is {@code locked} → 401).
 * <p>Call {@link #bearer} per request (it is cheap: no SQL, one HMAC):
 * <pre>{@code
 * mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, seedTokens.bearer(SeedUserTokens.ADMIN)))
 * }</pre>
 * The JWT {@code roles} claim is the user's active seed roles ({@link #seedRoles}); use
 * {@link #bearer(long, RoleCode...)} for a stale/forged claim (e.g. the revoked instructor 5 still claiming it).
 */
public class SeedUserTokens {

    /** admin@edumy.vn — the only admin (D-01); holds no other role. */
    public static final long ADMIN = 1;
    /** Instructors (and students) 2, 3, 4. */
    public static final long INSTRUCTOR = 2;
    public static final long INSTRUCTOR_2 = 3;
    public static final long INSTRUCTOR_3 = 4;
    /** Instructor role revoked by the admin (user_roles 9 inactive, D-04): student only. */
    public static final long REVOKED_INSTRUCTOR = 5;
    /** Students 6–17 (verified, active). */
    public static final long STUDENT = 6;
    /** Email not verified: 18, 19. */
    public static final long UNVERIFIED = 18;
    /** {@code users.status = locked}: every token is rejected (401). */
    public static final long LOCKED = 20;
    /** Seed user ids are 1…{@value #LAST_USER_ID}; all passwords {@code Edumy@123}. */
    public static final long LAST_USER_ID = 20;

    private final JwtIssuer issuer;
    private final Cache sessions;

    public SeedUserTokens(JwtIssuer issuer, CacheManager cacheManager) {
        this.issuer = issuer;
        this.sessions = cacheManager.getCache(CacheConfig.SESSION_STATUS);
    }

    /** {@code Authorization} header value for seed user {@code userId}, roles claim = its active seed roles. */
    public String bearer(long userId) {
        return bearer(userId, seedRoles(userId).toArray(RoleCode[]::new));
    }

    /** {@code Authorization} header value for seed user {@code userId} with exactly {@code roles} in the claim. */
    public String bearer(long userId, RoleCode... roles) {
        String sid = sid(userId);
        sessions.put(sid, Boolean.TRUE);
        return "Bearer " + issuer.issue(userId, List.of(roles), sid).value();
    }

    /** Session id used for {@code userId} (stable, so the cache does not grow). */
    public static String sid(long userId) {
        return UUID.nameUUIDFromBytes(("edumy-seed-session-" + userId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Active roles of the seed users (db/seed/V1000__dev_seed.sql, user_roles): 1 admin; 2–4 student + instructor;
     * 5–20 student (5: instructor revoked). Checked against the database by {@code MySqlSeedSpringBootTestIT}.
     */
    public static Set<RoleCode> seedRoles(long userId) {
        if (userId < 1 || userId > LAST_USER_ID) {
            throw new IllegalArgumentException("Not a seed user id: " + userId);
        }
        if (userId == ADMIN) {
            return EnumSet.of(RoleCode.ADMIN);
        }
        return userId <= INSTRUCTOR_3 ? EnumSet.of(RoleCode.STUDENT, RoleCode.INSTRUCTOR) : EnumSet.of(RoleCode.STUDENT);
    }
}
