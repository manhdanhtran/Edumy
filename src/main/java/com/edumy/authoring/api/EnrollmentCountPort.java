package com.edumy.authoring.api;

import java.util.Collection;
import java.util.Map;

/**
 * Port declared by authoring and implemented by learning (P13, dependency inversion — authoring may not depend on
 * learning, docs/ai/01 §1.8): number of enrolled students per course, for the UC-I02 course list of an instructor.
 * Learning depends on {@code authoring :: api} already, so the module graph stays acyclic.
 */
public interface EnrollmentCountPort {

    /**
     * Enrollments (free and purchase, lifetime, BR-17) per course id, in one query; a course without enrollment is
     * absent from the map. An empty collection costs no query.
     */
    Map<Long, Long> countStudents(Collection<Long> courseIds);
}
