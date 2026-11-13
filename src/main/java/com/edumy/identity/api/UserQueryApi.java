package com.edumy.identity.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Read access to user accounts for other modules (docs/ai/01 §1.9). Not cached: names may change (UC-G09) and the
 * callers read them together with their own rows. Use {@link #findSummaries} for lists (one statement for any number
 * of users, no N+1).
 */
public interface UserQueryApi {

    /** The user with this id; empty for an unknown id or {@code null}. */
    Optional<UserSummary> findSummary(Long id);

    /**
     * Users with these ids, keyed by id, in <b>one</b> query; unknown ids (and {@code null}s) are simply absent.
     * An empty collection costs no query. The map is immutable.
     */
    Map<Long, UserSummary> findSummaries(Collection<Long> ids);
}
