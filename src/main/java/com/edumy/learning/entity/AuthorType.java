package com.edumy.learning.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code question_answers.author_type} (ck_question_answers_author_type): snapshot of the author's role in the
 * course when the answer is posted (BR-38).
 */
public enum AuthorType implements CodeEnum {

    INSTRUCTOR("instructor"),
    STUDENT("student");

    private final String code;

    AuthorType(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
