package com.edumy.learning.dto.response;

import java.util.List;

/**
 * UC-S11 outline of a course: chapters with lessons. {@code progress} and each lesson's {@code completed} exist only
 * for an enrolled student (D-32: an instructor or admin reading the content has no progress; both are null).
 */
public record LearningOutlineResponse(
        Long courseId,
        String title,
        boolean enrolled,
        Progress progress,
        List<Chapter> chapters) {

    public record Progress(int completedLessons, int totalLessons, int percent) {
    }

    public record Chapter(Long id, String title, List<Lesson> lessons) {
    }

    public record Lesson(Long id, String title, String contentType, Integer durationSeconds, boolean preview,
                         Boolean completed) {
    }
}
