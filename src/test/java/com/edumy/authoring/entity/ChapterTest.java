package com.edumy.authoring.entity;

import static com.edumy.authoring.entity.CourseTest.NOW;
import static com.edumy.authoring.entity.CourseTest.assertValidationFailed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChapterTest {

    static Chapter newChapter() {
        return Chapter.create(CourseTest.newCourse(), " Chương 1: Giới thiệu ", 1, NOW);
    }

    @Test
    void create_validInput_trimmedTitle_UCI05() {
        Course course = CourseTest.newCourse();

        Chapter chapter = Chapter.create(course, " Chương 1 ", 3, NOW);

        assertThat(chapter.getCourse()).isSameAs(course);
        assertThat(chapter.getTitle()).isEqualTo("Chương 1");
        assertThat(chapter.getSortOrder()).isEqualTo(3);
        assertThat(chapter.getCreatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void create_blankTitle_rejected(String title) {
        assertValidationFailed(() -> Chapter.create(CourseTest.newCourse(), title, 1, NOW), "title");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void create_nonPositiveSortOrder_rejected_ckChaptersOrder(int sortOrder) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Chapter.create(CourseTest.newCourse(), "A", sortOrder, NOW));
    }

    @Test
    void rename_validTitle_trimmed_UCI06() {
        Chapter chapter = newChapter();

        chapter.rename("  Chương mới ");

        assertThat(chapter.getTitle()).isEqualTo("Chương mới");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rename_blankTitle_rejectedAndUnchanged(String title) {
        Chapter chapter = newChapter();

        assertValidationFailed(() -> chapter.rename(title), "title");
        assertThat(chapter.getTitle()).isEqualTo("Chương 1: Giới thiệu");
    }
}
