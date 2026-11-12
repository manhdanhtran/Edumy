package com.edumy.authoring;

import static com.edumy.support.SchemaAssertions.assertCheckViolated;
import static com.edumy.support.SchemaAssertions.assertCheckViolatedAnyOf;
import static com.edumy.support.SchemaAssertions.checkConstraint;
import static com.edumy.support.SchemaAssertions.assertViolates;
import static com.edumy.support.SchemaAssertions.foreignKeyDeleteRules;
import static com.edumy.support.SchemaAssertions.indexColumns;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import com.edumy.support.MySqlJdbcTest;
import com.edumy.support.SchemaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V2__authoring.sql on real MySQL 8.4: every CHECK / UNIQUE / FK of categories, courses, course_co_instructors,
 * chapters, lessons (EDU-DDD-001 §5.5–5.9, §6 #5–12, §8 rows 5–13, §9). Each violation asserts the constraint name.
 * Triggers of these tables (BR-02, BR-19) are created and tested in V5 (TriggerSchemaIT).
 * Each test runs in a rolled-back transaction (@JdbcTest).
 */
@MySqlJdbcTest
class AuthoringSchemaIT {

    private static final String PAST = "2026-01-01 00:00:00";

    @Autowired
    JdbcClient jdbc;

    SchemaFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
    }

    // ----------------------------------------------------------------------------------------------------- categories

    @Test
    void insertCategory_valid_appliesDefaults() {
        long id = fx.category();

        var row = jdbc.sql("SELECT is_active, created_at, updated_at FROM categories WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertCategory_duplicateName_rejected() {
        insertCategory("Lập trình web", "lap-trinh-web-1");
        assertViolates("uq_categories_name", () -> insertCategory("Lập trình web", "lap-trinh-web-2"));
    }

    @Test
    void insertCategory_nameDifferingOnlyInCase_rejected() {
        // utf8mb4_0900_ai_ci: names are unique case-insensitively (the intended behaviour for display names)
        insertCategory("Thiết kế", "thiet-ke-1");
        assertViolates("uq_categories_name", () -> insertCategory("THIẾT KẾ", "thiet-ke-2"));
    }

    @Test
    void insertCategory_duplicateSlug_rejected() {
        insertCategory("Kinh doanh 1", "kinh-doanh");
        assertViolates("uq_categories_slug", () -> insertCategory("Kinh doanh 2", "kinh-doanh"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Kinh-doanh", "kinh--doanh", "-kinh", "kinh-", "kinh doanh", "kinh_doanh", "kính-doanh", "",
            "kinh-doanh\n"})
    void insertCategory_invalidSlug_rejected(String slug) {
        assertCheckViolated("ck_categories_slug", () -> insertCategory("Danh mục " + SchemaFixtures.next(), slug));
    }

    @Test
    void updateCategory_deactivate_refreshesUpdatedAt() {
        long id = fx.category();
        assertUpdatedAtRefreshed("categories", id, "is_active = FALSE");
    }

    // -------------------------------------------------------------------------------------------------------- courses

    @Test
    void insertCourse_valid_appliesDefaults() {
        long id = fx.course();

        var row = jdbc.sql("""
                SELECT price, status, reviewed_by, description, thumbnail_url, review_note,
                       submitted_at, reviewed_at, published_at, created_at, updated_at
                FROM courses WHERE id = ?""").param(id).query().singleRow();
        assertThat(row.get("price")).isEqualTo(0L);
        assertThat(row.get("status")).isEqualTo("draft");
        assertThat(row).containsEntry("reviewed_by", null).containsEntry("description", null)
                .containsEntry("thumbnail_url", null).containsEntry("review_note", null)
                .containsEntry("submitted_at", null).containsEntry("reviewed_at", null)
                .containsEntry("published_at", null);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertCourse_everyStatusWithItsRequiredColumns_accepted() {
        long instructor = fx.user();
        long admin = fx.user();
        insertCourse(instructor, "draft", null, null, null, null);
        insertCourse(instructor, "pending_review", null, PAST, null, null);
        insertCourse(instructor, "rejected", "Thiếu mô tả", PAST, null, admin);
        insertCourse(instructor, "published", null, PAST, PAST, admin);
        insertCourse(instructor, "hidden", "Vi phạm bản quyền", PAST, PAST, admin);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM courses WHERE main_instructor_id = ?").param(instructor)
                .query(Long.class).single()).isEqualTo(5L);
    }

    @Test
    void insertCourse_duplicateSlug_rejected() {
        long instructor = fx.user();
        insertCourse(instructor, "khoa-hoc-trung", 0L);
        assertViolates("uq_courses_slug", () -> insertCourse(instructor, "khoa-hoc-trung", 0L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Java-co-ban", "java--co-ban", "java co ban", "java-cơ-bản", "-java", "java-co-ban\n"})
    void insertCourse_invalidSlug_rejected(String slug) {
        long instructor = fx.user();
        assertCheckViolated("ck_courses_slug", () -> insertCourse(instructor, slug, 0L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"archived", "Draft", "PUBLISHED", "pending_revíew"})
    void insertCourse_invalidStatus_rejected(String status) {
        long instructor = fx.user();
        // every companion column filled, so only the status list can fail
        assertCheckViolated("ck_courses_status",
                () -> insertCourse(instructor, status, "Ghi chú", PAST, PAST, fx.user()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 10_000, 499_000, 1_000_000})
    void insertCourse_validPrice_accepted_BR07(long price) {
        long id = insertCourse(fx.user(), "gia-" + SchemaFixtures.next(), price);
        assertThat(jdbc.sql("SELECT price FROM courses WHERE id = ?").param(id).query(Long.class).single())
                .isEqualTo(price);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1_000, 1, 1_000, 9_000, 10_500, 499_999})
    void insertCourse_invalidPrice_rejected_BR07(long price) {
        long instructor = fx.user();
        assertCheckViolated("ck_courses_price",
                () -> insertCourse(instructor, "gia-" + SchemaFixtures.next(), price));
    }

    @Test
    void insertCourse_rejectedWithoutNote_rejected_BR08() {
        long instructor = fx.user();
        assertCheckViolated("ck_courses_note_required",
                () -> insertCourse(instructor, "rejected", null, PAST, null, fx.user()));
    }

    @Test
    void insertCourse_hiddenWithoutNote_rejected_BR08() {
        long instructor = fx.user();
        assertCheckViolated("ck_courses_note_required",
                () -> insertCourse(instructor, "hidden", null, PAST, PAST, fx.user()));
    }

    @Test
    void insertCourse_publishedWithoutPublishedAt_rejected() {
        long instructor = fx.user();
        assertCheckViolated("ck_courses_published_at",
                () -> insertCourse(instructor, "published", null, PAST, null, fx.user()));
    }

    @Test
    void insertCourse_hiddenWithoutPublishedAt_rejected() {
        long instructor = fx.user();
        assertCheckViolated("ck_courses_published_at",
                () -> insertCourse(instructor, "hidden", "Vi phạm", PAST, null, fx.user()));
    }

    @Test
    void insertCourse_pendingReviewWithoutSubmittedAt_rejected() {
        long instructor = fx.user();
        assertCheckViolated("ck_courses_submitted_at",
                () -> insertCourse(instructor, "pending_review", null, null, null, null));
    }

    @Test
    void insertCourse_unknownMainInstructor_rejected() {
        assertViolates("fk_courses_main_instructor_id", () -> insertCourse(-1L, "khong-co-gv", 0L));
    }

    @Test
    void insertCourse_unknownCategory_rejected() {
        long instructor = fx.user();
        assertViolates("fk_courses_category_id", () -> jdbc.sql("""
                INSERT INTO courses (main_instructor_id, category_id, title, title_normalized, slug)
                VALUES (?, -1, 'Khóa', 'khoa', ?)""").params(instructor, "khoa-" + SchemaFixtures.next()).update());
    }

    @Test
    void insertCourse_unknownReviewer_rejected() {
        long instructor = fx.user();
        assertViolates("fk_courses_reviewed_by",
                () -> insertCourse(instructor, "published", null, PAST, PAST, -1L));
    }

    @Test
    void updateCourse_anyColumn_refreshesUpdatedAt() {
        long id = fx.course();
        assertUpdatedAtRefreshed("courses", id, "title = 'Tên mới'");
    }

    // ---------------------------------------------------------------------------------------- course_co_instructors

    @Test
    void insertInvitation_valid_appliesDefaults() {
        long owner = fx.user();
        long id = insertInvitation(fx.course(owner), fx.user(), owner, "pending", null);

        var row = jdbc.sql("""
                SELECT invitation_status, invited_at, responded_at, updated_at FROM course_co_instructors WHERE id = ?""")
                .param(id).query().singleRow();
        assertThat(row.get("invitation_status")).isEqualTo("pending");
        assertThat(row.get("invited_at")).isNotNull();
        assertThat(row.get("responded_at")).isNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertInvitation_everyStatus_accepted() {
        long owner = fx.user();
        long course = fx.course(owner);
        insertInvitation(course, fx.user(), owner, "pending", null);
        insertInvitation(course, fx.user(), owner, "accepted", PAST);
        insertInvitation(course, fx.user(), owner, "declined", PAST);
        insertInvitation(course, fx.user(), owner, "removed", null);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM course_co_instructors WHERE course_id = ?").param(course)
                .query(Long.class).single()).isEqualTo(4L);
    }

    @Test
    void insertInvitation_samePairTwice_rejected_D18() {
        long owner = fx.user();
        long course = fx.course(owner);
        long invitee = fx.user();
        insertInvitation(course, invitee, owner, "pending", null);
        assertViolates("uq_cci_course_instructor", () -> insertInvitation(course, invitee, owner, "pending", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invited", "Accepted", "PENDING", "removéd"})
    void insertInvitation_invalidStatus_rejected(String status) {
        long owner = fx.user();
        long course = fx.course(owner);
        long invitee = fx.user();
        assertCheckViolated("ck_cci_status", () -> insertInvitation(course, invitee, owner, status, PAST));
    }

    @Test
    void insertInvitation_inviteeIsInviter_rejected() {
        long owner = fx.user();
        long course = fx.course(owner);
        long other = fx.user();
        assertCheckViolated("ck_cci_not_self", () -> insertInvitation(course, other, other, "pending", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "declined"})
    void insertInvitation_answeredWithoutRespondedAt_rejected(String status) {
        long owner = fx.user();
        long course = fx.course(owner);
        long invitee = fx.user();
        assertCheckViolated("ck_cci_responded", () -> insertInvitation(course, invitee, owner, status, null));
    }

    @Test
    void insertInvitation_unknownCourse_rejected() {
        long owner = fx.user();
        long invitee = fx.user();
        assertViolates("fk_course_co_instructors_course_id",
                () -> insertInvitation(-1L, invitee, owner, "pending", null));
    }

    @Test
    void insertInvitation_unknownInvitee_rejected() {
        long owner = fx.user();
        long course = fx.course(owner);
        assertViolates("fk_course_co_instructors_instructor_id",
                () -> insertInvitation(course, -1L, owner, "pending", null));
    }

    @Test
    void insertInvitation_unknownInviter_rejected() {
        long course = fx.course();
        long invitee = fx.user();
        assertViolates("fk_course_co_instructors_invited_by",
                () -> insertInvitation(course, invitee, -1L, "pending", null));
    }

    @Test
    void updateInvitation_accept_refreshesUpdatedAt() {
        long owner = fx.user();
        long id = insertInvitation(fx.course(owner), fx.user(), owner, "pending", null);
        assertUpdatedAtRefreshed("course_co_instructors", id,
                "invitation_status = 'accepted', responded_at = CURRENT_TIMESTAMP(6)");
    }

    // ------------------------------------------------------------------------------------------------------- chapters

    @Test
    void insertChapter_valid_storesTimestamps() {
        long id = fx.chapter(fx.course(), 1);

        var row = jdbc.sql("SELECT sort_order, created_at, updated_at FROM chapters WHERE id = ?")
                .param(id).query().singleRow();
        assertThat(row.get("sort_order")).isEqualTo(1);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertChapter_duplicateOrderInCourse_rejected() {
        long course = fx.course();
        fx.chapter(course, 1);
        assertViolates("uq_chapters_course_order", () -> fx.chapter(course, 1));
    }

    @Test
    void insertChapter_sameOrderInAnotherCourse_accepted() {
        fx.chapter(fx.course(), 1);
        long id = fx.chapter(fx.course(), 1);
        assertThat(id).isPositive();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void insertChapter_nonPositiveOrder_rejected(int sortOrder) {
        long course = fx.course();
        assertCheckViolated("ck_chapters_order", () -> fx.chapter(course, sortOrder));
    }

    @Test
    void insertChapter_unknownCourse_rejected() {
        assertViolates("fk_chapters_course_id", () -> fx.chapter(-1L, 1));
    }

    @Test
    void reorderChapters_twoPhaseOffset_keepsUniqueAndCheck() {
        // docs/ai/03 §3.4: phase 1 shifts every sibling by +1 000 000, phase 2 assigns 1..n
        long course = fx.course();
        long a = fx.chapter(course, 1);
        long b = fx.chapter(course, 2);
        long c = fx.chapter(course, 3);

        jdbc.sql("UPDATE chapters SET sort_order = sort_order + 1000000 WHERE course_id = ?").param(course).update();
        jdbc.sql("UPDATE chapters SET sort_order = 1 WHERE id = ?").param(c).update();
        jdbc.sql("UPDATE chapters SET sort_order = 2 WHERE id = ?").param(a).update();
        jdbc.sql("UPDATE chapters SET sort_order = 3 WHERE id = ?").param(b).update();

        assertThat(jdbc.sql("SELECT id FROM chapters WHERE course_id = ? ORDER BY sort_order").param(course)
                .query(Long.class).list()).containsExactly(c, a, b);
    }

    @Test
    void updateChapter_rename_refreshesUpdatedAt() {
        long id = fx.chapter(fx.course(), 1);
        assertUpdatedAtRefreshed("chapters", id, "title = 'Chương mới'");
    }

    // -------------------------------------------------------------------------------------------------------- lessons

    @Test
    void insertLesson_video_appliesDefaults() {
        long id = fx.videoLesson(fx.chapter(fx.course(), 1), 1);

        var row = jdbc.sql("""
                SELECT is_preview, duration_seconds, article_content, created_at, updated_at FROM lessons WHERE id = ?""")
                .param(id).query().singleRow();
        assertThat(row.get("is_preview")).isEqualTo(false);
        assertThat(row.get("duration_seconds")).isNull();
        assertThat(row.get("article_content")).isNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void insertLesson_article_accepted() {
        long chapter = fx.chapter(fx.course(), 1);
        long id = insertLesson(chapter, "article", null, "<p>Nội dung</p>", 0, 1);
        assertThat(jdbc.sql("SELECT content_type FROM lessons WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("article");
    }

    /**
     * DDD ck_lessons_content_match only holds for 'video' or 'article', so an unknown type breaks it as well as
     * ck_lessons_type; MySQL reports the first CHECK it evaluates. Either name means "invalid content type".
     */
    @ParameterizedTest
    @ValueSource(strings = {"quiz", "Video", "ARTICLE", "vidéo"})
    void insertLesson_invalidType_rejected(String type) {
        long chapter = fx.chapter(fx.course(), 1);
        assertCheckViolatedAnyOf(() -> insertLesson(chapter, type, null, null, null, 1),
                "ck_lessons_type", "ck_lessons_content_match");
    }

    @Test
    void lessonTypeCheck_isEnforcedAndCaseSensitive() {
        assertThat(checkConstraint(jdbc, "lessons", "ck_lessons_type")).singleElement().asString()
                .startsWith("YES ")
                .contains("cast(`content_type` as char charset binary)")
                .contains("video").contains("article");
    }

    @Test
    void insertLesson_videoWithArticleContent_rejected_D22() {
        long chapter = fx.chapter(fx.course(), 1);
        assertCheckViolated("ck_lessons_content_match",
                () -> insertLesson(chapter, "video", "https://cdn.edumy.vn/v.mp4", "<p>x</p>", null, 1));
    }

    @Test
    void insertLesson_articleWithVideoUrl_rejected_D22() {
        long chapter = fx.chapter(fx.course(), 1);
        assertCheckViolated("ck_lessons_content_match",
                () -> insertLesson(chapter, "article", "https://cdn.edumy.vn/v.mp4", "<p>x</p>", null, 1));
    }

    @Test
    void insertLesson_negativeDuration_rejected() {
        long chapter = fx.chapter(fx.course(), 1);
        assertCheckViolated("ck_lessons_duration",
                () -> insertLesson(chapter, "video", "https://cdn.edumy.vn/v.mp4", null, -1, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void insertLesson_nonPositiveOrder_rejected(int sortOrder) {
        long chapter = fx.chapter(fx.course(), 1);
        assertCheckViolated("ck_lessons_order", () -> fx.videoLesson(chapter, sortOrder));
    }

    @Test
    void insertLesson_duplicateOrderInChapter_rejected() {
        long chapter = fx.chapter(fx.course(), 1);
        fx.videoLesson(chapter, 1);
        assertViolates("uq_lessons_chapter_order", () -> fx.videoLesson(chapter, 1));
    }

    @Test
    void insertLesson_unknownChapter_rejected() {
        assertViolates("fk_lessons_chapter_id", () -> fx.videoLesson(-1L, 1));
    }

    @Test
    void reorderLessons_twoPhaseOffset_keepsUniqueAndCheck() {
        long chapter = fx.chapter(fx.course(), 1);
        long a = fx.videoLesson(chapter, 1);
        long b = fx.videoLesson(chapter, 2);

        jdbc.sql("UPDATE lessons SET sort_order = sort_order + 1000000 WHERE chapter_id = ?").param(chapter).update();
        jdbc.sql("UPDATE lessons SET sort_order = 1 WHERE id = ?").param(b).update();
        jdbc.sql("UPDATE lessons SET sort_order = 2 WHERE id = ?").param(a).update();

        assertThat(jdbc.sql("SELECT id FROM lessons WHERE chapter_id = ? ORDER BY sort_order").param(chapter)
                .query(Long.class).list()).containsExactly(b, a);
    }

    @Test
    void updateLesson_rename_refreshesUpdatedAt() {
        long id = fx.videoLesson(fx.chapter(fx.course(), 1), 1);
        assertUpdatedAtRefreshed("lessons", id, "title = 'Bài mới'");
    }

    // ------------------------------------------------------------------------------------ ON DELETE (DDD §6 #5–12)

    @Test
    void deleteDraftCourse_cascadesInvitationsChaptersLessons() {
        long owner = fx.user();
        long course = fx.course(owner);
        insertInvitation(course, fx.user(), owner, "pending", null);
        long chapter = fx.chapter(course, 1);
        long lesson = fx.videoLesson(chapter, 1);

        jdbc.sql("DELETE FROM courses WHERE id = ?").param(course).update();

        assertThat(count("course_co_instructors", "course_id", course)).isZero();
        assertThat(count("chapters", "id", chapter)).isZero();
        assertThat(count("lessons", "id", lesson)).isZero();
    }

    @Test
    void deleteChapter_cascadesLessons() {
        long chapter = fx.chapter(fx.course(), 1);
        long lesson = fx.videoLesson(chapter, 1);

        jdbc.sql("DELETE FROM chapters WHERE id = ?").param(chapter).update();

        assertThat(count("lessons", "id", lesson)).isZero();
    }

    @Test
    void deleteUser_whoIsMainInstructor_restricted() {
        long owner = fx.user();
        fx.course(owner);
        assertViolates("fk_courses_main_instructor_id", () -> deleteUser(owner));
    }

    @Test
    void deleteUser_whoReviewedACourse_restricted() {
        long admin = fx.user();
        insertCourse(fx.user(), "published", null, PAST, PAST, admin);
        assertViolates("fk_courses_reviewed_by", () -> deleteUser(admin));
    }

    @Test
    void deleteCategory_inUse_restricted() {
        long course = fx.course();
        long category = jdbc.sql("SELECT category_id FROM courses WHERE id = ?").param(course)
                .query(Long.class).single();
        assertViolates("fk_courses_category_id",
                () -> jdbc.sql("DELETE FROM categories WHERE id = ?").param(category).update());
    }

    @Test
    void deleteUser_whoIsInvited_restricted() {
        long owner = fx.user();
        long invitee = fx.user();
        insertInvitation(fx.course(owner), invitee, owner, "pending", null);
        assertViolates("fk_course_co_instructors_instructor_id", () -> deleteUser(invitee));
    }

    @Test
    void deleteUser_whoInvited_restricted() {
        long owner = fx.user();
        long inviter = fx.user();
        insertInvitation(fx.course(owner), fx.user(), inviter, "pending", null);
        assertViolates("fk_course_co_instructors_invited_by", () -> deleteUser(inviter));
    }

    // -------------------------------------------------------------------------------------------- schema metadata

    @Test
    void foreignKeys_haveDddNamesAndDeleteRules() {
        assertThat(foreignKeyDeleteRules(jdbc, "categories", "courses", "course_co_instructors", "chapters", "lessons"))
                .containsExactly(
                        "fk_chapters_course_id=CASCADE",
                        "fk_course_co_instructors_course_id=CASCADE",
                        "fk_course_co_instructors_instructor_id=RESTRICT",
                        "fk_course_co_instructors_invited_by=RESTRICT",
                        "fk_courses_category_id=RESTRICT",
                        "fk_courses_main_instructor_id=RESTRICT",
                        "fk_courses_reviewed_by=RESTRICT",
                        "fk_lessons_chapter_id=CASCADE");
    }

    @Test
    void indexes_haveExpectedColumns() {
        assertThat(indexColumns(jdbc, "categories", "uq_categories_name")).containsExactly("name");
        assertThat(indexColumns(jdbc, "categories", "uq_categories_slug")).containsExactly("slug");
        assertThat(indexColumns(jdbc, "courses", "uq_courses_slug")).containsExactly("slug");
        assertThat(indexColumns(jdbc, "courses", "ix_courses_main_instructor")).containsExactly("main_instructor_id");
        assertThat(indexColumns(jdbc, "courses", "ix_courses_category_status")).containsExactly("category_id", "status");
        assertThat(indexColumns(jdbc, "courses", "ix_courses_reviewed_by")).containsExactly("reviewed_by");
        assertThat(indexColumns(jdbc, "courses", "ix_courses_published")).containsExactly("status", "published_at DESC");
        assertThat(indexColumns(jdbc, "courses", "ix_courses_pending")).containsExactly("status", "submitted_at");
        assertThat(indexColumns(jdbc, "courses", "ix_courses_status_title")).containsExactly("status", "title_normalized");
        assertThat(indexColumns(jdbc, "course_co_instructors", "uq_cci_course_instructor"))
                .containsExactly("course_id", "instructor_id");
        assertThat(indexColumns(jdbc, "course_co_instructors", "ix_cci_instructor_status"))
                .containsExactly("instructor_id", "invitation_status");
        assertThat(indexColumns(jdbc, "course_co_instructors", "ix_cci_invited_by")).containsExactly("invited_by");
        assertThat(indexColumns(jdbc, "chapters", "uq_chapters_course_order")).containsExactly("course_id", "sort_order");
        assertThat(indexColumns(jdbc, "lessons", "uq_lessons_chapter_order")).containsExactly("chapter_id", "sort_order");
    }

    @Test
    void titleNormalized_isAPlainColumnWrittenByTheApplication() {
        var column = jdbc.sql("""
                SELECT IS_NULLABLE, EXTRA, GENERATION_EXPRESSION FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'courses' AND COLUMN_NAME = 'title_normalized'""")
                .query().singleRow();
        assertThat(column.get("IS_NULLABLE")).isEqualTo("NO");
        assertThat((String) column.get("EXTRA")).doesNotContainIgnoringCase("GENERATED");
        assertThat((String) column.get("GENERATION_EXPRESSION")).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private long insertCategory(String name, String slug) {
        return fx.insert(jdbc.sql("INSERT INTO categories (name, slug) VALUES (?, ?)").params(name, slug));
    }

    private long insertCourse(long mainInstructorId, String slug, long price) {
        return fx.insert(jdbc.sql("""
                INSERT INTO courses (main_instructor_id, category_id, title, title_normalized, slug, price)
                VALUES (?, ?, 'Khóa học', 'khoa hoc', ?, ?)""")
                .params(mainInstructorId, fx.category(), slug, price));
    }

    private long insertCourse(long mainInstructorId, String status, String reviewNote, String submittedAt,
                              String publishedAt, Long reviewedBy) {
        return fx.insert(jdbc.sql("""
                INSERT INTO courses (main_instructor_id, category_id, title, title_normalized, slug, status,
                                     review_note, submitted_at, published_at, reviewed_by, reviewed_at)
                VALUES (?, ?, 'Khóa học', 'khoa hoc', ?, ?, ?, ?, ?, ?, ?)""")
                .params(mainInstructorId, fx.category(), "khoa-" + SchemaFixtures.next(), status, reviewNote,
                        submittedAt, publishedAt, reviewedBy, reviewedBy == null ? null : PAST));
    }

    private long insertInvitation(long courseId, long instructorId, long invitedBy, String status, String respondedAt) {
        return fx.insert(jdbc.sql("""
                INSERT INTO course_co_instructors (course_id, instructor_id, invited_by, invitation_status, responded_at)
                VALUES (?, ?, ?, ?, ?)""")
                .params(courseId, instructorId, invitedBy, status, respondedAt));
    }

    private long insertLesson(long chapterId, String type, String videoUrl, String article, Integer duration,
                              int sortOrder) {
        return fx.insert(jdbc.sql("""
                INSERT INTO lessons (chapter_id, title, content_type, video_url, article_content, duration_seconds,
                                     sort_order)
                VALUES (?, 'Bài', ?, ?, ?, ?, ?)""")
                .params(chapterId, type, videoUrl, article, duration, sortOrder));
    }

    private void deleteUser(long id) {
        jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }

    private long count(String table, String column, long value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?").param(value)
                .query(Long.class).single();
    }

    /** Backdates updated_at, runs the update and asserts ON UPDATE CURRENT_TIMESTAMP(6) moved it forward. */
    private void assertUpdatedAtRefreshed(String table, long id, String assignments) {
        jdbc.sql("UPDATE " + table + " SET updated_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();
        jdbc.sql("UPDATE " + table + " SET " + assignments + " WHERE id = ?").param(id).update();

        LocalDateTime updatedAt = jdbc.sql("SELECT updated_at FROM " + table + " WHERE id = ?")
                .param(id).query(LocalDateTime.class).single();
        assertThat(updatedAt).isAfter(LocalDateTime.of(2020, 1, 2, 0, 0));
    }
}
