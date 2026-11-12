package com.edumy.authoring.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** DB codes must match the CHECK lists of V2__authoring.sql exactly. */
class AuthoringConvertersTest {

    @Test
    void courseStatus_codes_matchCkCoursesStatus() {
        CourseStatusConverter converter = new CourseStatusConverter();
        assertThat(Arrays.stream(CourseStatus.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("draft", "pending_review", "rejected", "published", "hidden");
        assertThat(converter.convertToEntityAttribute("pending_review")).isEqualTo(CourseStatus.PENDING_REVIEW);
    }

    @Test
    void invitationStatus_codes_matchCkCciStatus() {
        InvitationStatusConverter converter = new InvitationStatusConverter();
        assertThat(Arrays.stream(InvitationStatus.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("pending", "accepted", "declined", "removed");
        assertThat(converter.convertToEntityAttribute("removed")).isEqualTo(InvitationStatus.REMOVED);
    }

    @Test
    void lessonType_codes_matchCkLessonsType() {
        LessonTypeConverter converter = new LessonTypeConverter();
        assertThat(Arrays.stream(LessonType.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("video", "article");
        assertThat(converter.convertToEntityAttribute("article")).isEqualTo(LessonType.ARTICLE);
    }
}
