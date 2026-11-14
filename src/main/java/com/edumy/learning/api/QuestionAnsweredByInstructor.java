package com.edumy.learning.api;

import java.util.Objects;

/**
 * An instructor of the course answered a question (UC-I21 step 5, D-42): published inside the transaction only for an
 * {@code instructor}-type answer, never for the asker's follow-up. Notification mails the asker after commit. Carries
 * the ids and the question title (learning data the notification module may not read); names, email and course title
 * are read through {@code identity::api} / {@code authoring::api}.
 */
public record QuestionAnsweredByInstructor(Long questionId, Long answerId, Long courseId, Long askerId,
                                           Long instructorId, String questionTitle) {

    public QuestionAnsweredByInstructor {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(answerId, "answerId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(askerId, "askerId");
        Objects.requireNonNull(instructorId, "instructorId");
        Objects.requireNonNull(questionTitle, "questionTitle");
    }
}
