package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code visibility_status} of {@code course_questions} and {@code question_answers}
 * (ck_course_questions_visibility, ck_question_answers_visibility). Transitions: docs/ai/02 §2.2, enforced by
 * {@link CourseQuestion} and {@link QuestionAnswer}.
 */
public enum ContentStatus implements CodeEnum {

    VISIBLE("visible"),
    DELETED("deleted"),
    HIDDEN("hidden");

    private final String code;

    ContentStatus(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
