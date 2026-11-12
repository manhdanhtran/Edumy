package com.edumy.authoring.entity;

import com.edumy.shared.jpa.CodeEnum;

/**
 * {@code lessons.content_type} (ck_lessons_type). video: {@code video_url}; article: {@code article_content}
 * (ck_lessons_content_match, D-22).
 */
public enum LessonType implements CodeEnum {

    VIDEO("video"),
    ARTICLE("article");

    private final String code;

    LessonType(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
