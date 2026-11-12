package com.edumy.authoring.entity;

import static com.edumy.authoring.entity.CourseTest.NOW;
import static com.edumy.authoring.entity.CourseTest.assertValidationFailed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LessonTest {

    static final String VIDEO = "https://cdn.edumy.vn/v.mp4";

    static Lesson videoLesson() {
        return Lesson.create(ChapterTest.newChapter(), "Bài 1", LessonType.VIDEO, VIDEO, null, 300, true, 1, NOW);
    }

    @Test
    void create_video_videoUrlOnly_UCI09() {
        Chapter chapter = ChapterTest.newChapter();

        Lesson lesson = Lesson.create(chapter, " Bài 1 ", LessonType.VIDEO, " " + VIDEO + " ", "  ", 300, true, 2,
                NOW);

        assertThat(lesson.getChapter()).isSameAs(chapter);
        assertThat(lesson.getTitle()).isEqualTo("Bài 1");
        assertThat(lesson.getContentType()).isEqualTo(LessonType.VIDEO);
        assertThat(lesson.getVideoUrl()).isEqualTo(VIDEO);
        assertThat(lesson.getArticleContent()).isNull();
        assertThat(lesson.getDurationSeconds()).isEqualTo(300);
        assertThat(lesson.isPreview()).isTrue();
        assertThat(lesson.getSortOrder()).isEqualTo(2);
        assertThat(lesson.getCreatedAt()).isEqualTo(NOW);
        assertThat(lesson.hasContent()).isTrue();
    }

    @Test
    void create_article_articleContentOnly_D22() {
        Lesson lesson = Lesson.create(ChapterTest.newChapter(), "Bài đọc", LessonType.ARTICLE, null, "Nội dung", null,
                false, 1, NOW);

        assertThat(lesson.getArticleContent()).isEqualTo("Nội dung");
        assertThat(lesson.getVideoUrl()).isNull();
        assertThat(lesson.getDurationSeconds()).isNull();
        assertThat(lesson.isPreview()).isFalse();
        assertThat(lesson.hasContent()).isTrue();
    }

    @Test
    void create_withoutContent_allowedButHasNoContent_BR07() {
        Lesson video = Lesson.create(ChapterTest.newChapter(), "A", LessonType.VIDEO, " ", null, null, false, 1, NOW);
        Lesson article = Lesson.create(ChapterTest.newChapter(), "B", LessonType.ARTICLE, null, null, 0, false, 1,
                NOW);

        assertThat(video.getVideoUrl()).isNull();
        assertThat(video.hasContent()).isFalse();
        assertThat(article.hasContent()).isFalse();
        assertThat(article.getDurationSeconds()).isZero();
    }

    @Test
    void create_videoWithArticleContent_rejected_ckLessonsContentMatch() {
        assertValidationFailed(() -> Lesson.create(ChapterTest.newChapter(), "A", LessonType.VIDEO, VIDEO, "Bài viết",
                null, false, 1, NOW), "articleContent");
    }

    @Test
    void create_articleWithVideoUrl_rejected_ckLessonsContentMatch() {
        assertValidationFailed(() -> Lesson.create(ChapterTest.newChapter(), "A", LessonType.ARTICLE, VIDEO, "Bài",
                null, false, 1, NOW), "videoUrl");
    }

    @Test
    void create_negativeDuration_rejected_ckLessonsDuration() {
        assertValidationFailed(() -> Lesson.create(ChapterTest.newChapter(), "A", LessonType.VIDEO, VIDEO, null, -1,
                false, 1, NOW), "durationSeconds");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void create_blankTitle_rejected(String title) {
        assertValidationFailed(() -> Lesson.create(ChapterTest.newChapter(), title, LessonType.VIDEO, VIDEO, null,
                null, false, 1, NOW), "title");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void create_nonPositiveSortOrder_rejected_ckLessonsOrder(int sortOrder) {
        assertThatIllegalArgumentException().isThrownBy(() -> Lesson.create(ChapterTest.newChapter(), "A",
                LessonType.VIDEO, VIDEO, null, null, false, sortOrder, NOW));
    }

    @Test
    void create_nullType_rejected() {
        assertThatThrownBy(() -> Lesson.create(ChapterTest.newChapter(), "A", null, VIDEO, null, null, false, 1, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void update_videoToArticle_contentSwitched_UCI10() {
        Lesson lesson = videoLesson();

        lesson.update(" Bài đọc ", LessonType.ARTICLE, null, " Nội dung ", 120, false);

        assertThat(lesson.getTitle()).isEqualTo("Bài đọc");
        assertThat(lesson.getContentType()).isEqualTo(LessonType.ARTICLE);
        assertThat(lesson.getVideoUrl()).isNull();
        assertThat(lesson.getArticleContent()).isEqualTo("Nội dung");
        assertThat(lesson.getDurationSeconds()).isEqualTo(120);
        assertThat(lesson.isPreview()).isFalse();
        assertThat(lesson.getSortOrder()).isEqualTo(1);
    }

    @Test
    void update_mismatchedContent_rejectedAndUnchanged() {
        Lesson lesson = videoLesson();

        assertValidationFailed(() -> lesson.update("Mới", LessonType.ARTICLE, VIDEO, "Nội dung", null, false),
                "videoUrl");
        assertThat(lesson.getTitle()).isEqualTo("Bài 1");
        assertThat(lesson.getContentType()).isEqualTo(LessonType.VIDEO);
        assertThat(lesson.getVideoUrl()).isEqualTo(VIDEO);
        assertThat(lesson.isPreview()).isTrue();
    }

    @Test
    void update_negativeDuration_rejectedAndUnchanged() {
        Lesson lesson = videoLesson();

        assertValidationFailed(() -> lesson.update("Mới", LessonType.VIDEO, VIDEO, null, -10, true),
                "durationSeconds");
        assertThat(lesson.getDurationSeconds()).isEqualTo(300);
        assertThat(lesson.getTitle()).isEqualTo("Bài 1");
    }
}
