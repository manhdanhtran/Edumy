package com.edumy.authoring.dto.response;

import java.util.List;

/** A chapter of a course being edited (UC-I05…I08): position in the course (1..n) and its lessons in order. */
public record ChapterResponse(Long id, String title, int sortOrder, List<LessonResponse> lessons) {

    public ChapterResponse {
        lessons = lessons == null ? List.of() : List.copyOf(lessons);
    }
}
