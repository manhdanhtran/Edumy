package com.edumy.learning.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** DB codes must match the CHECK lists of V4__learning.sql exactly. */
class LearningConvertersTest {

    @Test
    void enrollmentSource_codes_matchCkEnrollmentsSource() {
        EnrollmentSourceConverter converter = new EnrollmentSourceConverter();
        assertThat(Arrays.stream(EnrollmentSource.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("free", "purchase");
        assertThat(converter.convertToEntityAttribute("purchase")).isEqualTo(EnrollmentSource.PURCHASE);
    }

    @Test
    void reviewStatus_codes_matchCkCourseReviewsVisibility() {
        ReviewStatusConverter converter = new ReviewStatusConverter();
        assertThat(Arrays.stream(ReviewStatus.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("visible", "withdrawn", "hidden");
        assertThat(converter.convertToEntityAttribute("withdrawn")).isEqualTo(ReviewStatus.WITHDRAWN);
    }

    @Test
    void contentStatus_codes_matchCkQuestionsAndAnswersVisibility() {
        ContentStatusConverter converter = new ContentStatusConverter();
        assertThat(Arrays.stream(ContentStatus.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("visible", "deleted", "hidden");
        assertThat(converter.convertToEntityAttribute("deleted")).isEqualTo(ContentStatus.DELETED);
    }

    @Test
    void authorType_codes_matchCkQuestionAnswersAuthorType() {
        AuthorTypeConverter converter = new AuthorTypeConverter();
        assertThat(Arrays.stream(AuthorType.values()).map(converter::convertToDatabaseColumn))
                .containsExactly("instructor", "student");
        assertThat(converter.convertToEntityAttribute("student")).isEqualTo(AuthorType.STUDENT);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void unknownCode_rejected() {
        assertThatThrownBy(() -> new ContentStatusConverter().convertToEntityAttribute("Visible"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
