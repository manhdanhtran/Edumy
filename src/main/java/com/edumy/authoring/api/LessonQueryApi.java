package com.edumy.authoring.api;

import java.util.Optional;

/** Lessons seen from other modules (learning: UC-S09/S10 endpoints are addressed by lesson id). */
public interface LessonQueryApi {

    /** Course of the lesson (through its chapter); empty when the lesson does not exist. */
    Optional<Long> findCourseId(Long lessonId);
}
