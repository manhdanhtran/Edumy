package com.edumy.authoring.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lessons seen from other modules (learning: UC-S08 / UC-S09 / UC-S11 / UC-I19; endpoints addressed by lesson id). */
public interface LessonQueryApi {

    /** Course of the lesson (through its chapter); empty when the lesson does not exist. */
    Optional<Long> findCourseId(Long lessonId);

    /**
     * Number of lessons of each course, in <b>one</b> query, whatever the course status; a course without lessons (or
     * unknown) is absent. An empty collection costs no query. The map is immutable.
     */
    Map<Long, Integer> countLessons(Collection<Long> courseIds);

    /** Chapters of the course in display order, each with its lessons in order (2 queries). Empty for an unknown course. */
    List<ChapterOutline> findOutline(Long courseId);

    /** One lesson with its content; empty when it does not exist. */
    Optional<LessonContent> findContent(Long lessonId);
}
