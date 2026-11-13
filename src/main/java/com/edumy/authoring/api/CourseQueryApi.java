package com.edumy.authoring.api;

/** Read-only facts about courses for other modules. Extended by later units (docs/ai/01 §1.9). */
public interface CourseQueryApi {

    /** A course with this id exists, whatever its status (learning ignores the course status, BR-31). */
    boolean exists(Long courseId);
}
