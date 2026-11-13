package com.edumy.authoring.api;

import java.util.List;

/** A chapter of a course outline with its lessons in display order (no lesson content). */
public record ChapterOutline(Long id, String title, List<LessonOutline> lessons) {

    /** {@code contentType} is the lower-case code ({@code video} / {@code article}). */
    public record LessonOutline(Long id, String title, String contentType, Integer durationSeconds, boolean preview) {
    }
}
