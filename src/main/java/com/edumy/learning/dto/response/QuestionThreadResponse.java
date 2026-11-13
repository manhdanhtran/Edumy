package com.edumy.learning.dto.response;

import java.time.Instant;
import java.util.List;

/** UC-S18 thread: the question and its answers, oldest first (hidden ones only for their author and an admin). */
public record QuestionThreadResponse(
        Long id,
        Long courseId,
        Long lessonId,
        String title,
        String content,
        boolean resolved,
        String status,
        QuestionSummaryResponse.Asker asker,
        boolean instructorReplied,
        Instant createdAt,
        Instant updatedAt,
        List<AnswerResponse> answers) {
}
