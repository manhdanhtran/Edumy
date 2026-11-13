package com.edumy.authoring.api;

import java.util.Collection;
import java.util.Map;

/** Read-only facts about courses for other modules. Extended by later units (docs/ai/01 §1.9). */
public interface CourseQueryApi {

    /** A course with this id exists, whatever its status (learning ignores the course status, BR-31). */
    boolean exists(Long courseId);

    /**
     * Summaries of these courses, keyed by id, in <b>one</b> query, whatever their status; unknown ids (and
     * {@code null}s) are absent. An empty collection costs no query. The map is immutable.
     */
    Map<Long, CourseSummary> findSummaries(Collection<Long> courseIds);
}
