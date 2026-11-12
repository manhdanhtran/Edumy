package com.edumy.authoring.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.edumy.authoring.entity.Category;
import com.edumy.authoring.entity.Chapter;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.entity.CourseCoInstructor;
import com.edumy.authoring.entity.CourseStatus;
import com.edumy.authoring.entity.InvitationStatus;
import com.edumy.authoring.entity.Lesson;
import com.edumy.authoring.entity.LessonType;
import com.edumy.authoring.repository.CourseCoInstructorRepository.InvitationView;
import com.edumy.authoring.repository.CourseRepository.InstructorCourseView;
import com.edumy.authoring.repository.CourseRepository.PendingCourseView;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SchemaFixtures;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring repositories on real MySQL 8.4 (database {@code edumy}, no seed). Every test runs in a rolled-back
 * transaction; users (identity's table, FK parents only) are inserted with plain SQL in the same transaction and every
 * slug/name/email is unique, so nothing is left in the shared database (docs/ai/07).
 */
@MySqlSpringBootTest
@Transactional
class AuthoringRepositoryIT {

    static final Instant NOW = Instant.parse("2026-02-10T09:15:30.654321Z");

    @Autowired
    EntityManager em;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CategoryRepository categories;
    @Autowired
    CourseRepository courses;
    @Autowired
    CourseCoInstructorRepository coInstructors;
    @Autowired
    ChapterRepository chapters;
    @Autowired
    LessonRepository lessons;

    SchemaFixtures fx;
    Category category;

    @BeforeEach
    void setUp() {
        fx = new SchemaFixtures(jdbc);
        category = newCategory();
    }

    // -------------------------------------------------------------------------------------------- mapping round trip

    @Test
    void save_courseTree_allColumnsStoredAndReadBack() {
        long instructor = fx.user();
        Course course = courses.save(Course.create(instructor, category, "Lập trình Đồ họa", slug(),
                "Mô tả dài", "https://cdn.edumy.vn/t.png", 499_000, NOW));
        Chapter chapter = chapters.save(Chapter.create(course, "Chương 1", 1, NOW));
        Lesson article = lessons.save(Lesson.create(chapter, "Bài đọc", LessonType.ARTICLE, null, "Nội dung",
                null, true, 1, NOW));
        em.flush();

        var row = jdbc.sql("""
                SELECT status, title_normalized, price, description,
                       DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created
                FROM courses WHERE id = ?""").param(course.getId()).query().singleRow();
        assertThat(row.get("status")).isEqualTo("draft");
        assertThat(row.get("title_normalized")).isEqualTo("lap trinh do hoa");
        assertThat(row.get("price")).isEqualTo(499_000L);
        assertThat(row.get("description")).isEqualTo("Mô tả dài");
        assertThat(row.get("created")).isEqualTo(NOW.toString());
        assertThat(jdbc.sql("SELECT content_type FROM lessons WHERE id = ?").param(article.getId())
                .query(String.class).single()).isEqualTo("article");

        em.clear();
        Course reloaded = courses.findWithCategoryById(course.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(reloaded.getCategory())).isTrue();
        assertThat(reloaded.getCategory().getId()).isEqualTo(category.getId());
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(CourseStatus.DRAFT);
        Lesson lesson = lessons.findById(article.getId()).orElseThrow();
        assertThat(lesson.getContentType()).isEqualTo(LessonType.ARTICLE);
        assertThat(lesson.getArticleContent()).isEqualTo("Nội dung");
        assertThat(lesson.isPreview()).isTrue();
        assertThat(courses.findWithCategoryById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void dirtyMainInstructorId_notWritten_updatableFalse_ADR001() {
        long main = fx.user();
        long other = fx.user();
        Course course = courses.saveAndFlush(newCourse(main, NOW));

        ReflectionTestUtils.setField(course, "mainInstructorId", other);
        course.changePrice(0);
        courses.flush();

        var row = jdbc.sql("SELECT main_instructor_id, price FROM courses WHERE id = ?").param(course.getId())
                .query().singleRow();
        assertThat(row.get("main_instructor_id")).isEqualTo(main);
        assertThat(row.get("price")).isEqualTo(0L);
    }

    @Test
    void statusTransitions_persisted_checksSatisfied() {
        long main = fx.user();
        long admin = fx.user();
        Course course = courses.saveAndFlush(newCourse(main, NOW));

        course.submitForReview(NOW.plusSeconds(1));
        courses.flush();
        course.reject(admin, "Thiếu mô tả", NOW.plusSeconds(2));
        courses.flush();
        course.submitForReview(NOW.plusSeconds(3));
        course.approve(admin, NOW.plusSeconds(4));
        course.hide(admin, "Vi phạm", NOW.plusSeconds(5));
        courses.flush();
        em.clear();

        Course hidden = courses.findByIdForUpdate(course.getId()).orElseThrow();
        assertThat(hidden.getStatus()).isEqualTo(CourseStatus.HIDDEN);
        assertThat(hidden.getReviewNote()).isEqualTo("Vi phạm");
        assertThat(hidden.getReviewedBy()).isEqualTo(admin);
        assertThat(hidden.getSubmittedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(hidden.getPublishedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(hidden.isDeletable()).isFalse();
    }

    // ------------------------------------------------------------------------------------------- instructor queries

    @Test
    void findInstructorCourses_mainAndAcceptedCoOnly_newestFirst_statusFilter_UCI02() {
        long main = fx.user();
        long co = fx.user();
        long otherMain = fx.user();
        Course own1 = courses.save(newCourse(co, NOW));
        Course own2 = courses.save(newCourse(co, NOW.plusSeconds(10)));
        own2.submitForReview(NOW.plusSeconds(11));
        Course accepted = courses.save(newCourse(main, NOW.plusSeconds(20)));
        Course pending = courses.save(newCourse(main, NOW.plusSeconds(30)));
        Course declined = courses.save(newCourse(otherMain, NOW.plusSeconds(40)));
        Course removed = courses.save(newCourse(otherMain, NOW.plusSeconds(50)));
        Course stranger = courses.save(newCourse(otherMain, NOW.plusSeconds(60)));
        invite(accepted, co).accept(NOW);
        invite(pending, co);
        invite(declined, co).decline(NOW);
        CourseCoInstructor gone = invite(removed, co);
        gone.accept(NOW);
        gone.remove();
        em.flush();
        em.clear();

        Page<InstructorCourseView> page = courses.findInstructorCourses(co, null, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(InstructorCourseView::getId)
                .containsExactly(accepted.getId(), own2.getId(), own1.getId())
                .doesNotContain(pending.getId(), declined.getId(), removed.getId(), stranger.getId());
        InstructorCourseView first = page.getContent().getFirst();
        assertThat(first.getMainInstructorId()).isEqualTo(main);
        assertThat(first.getCategoryId()).isEqualTo(category.getId());
        assertThat(first.getCategoryName()).isEqualTo(category.getName());
        assertThat(first.getStatus()).isEqualTo(CourseStatus.DRAFT);
        assertThat(first.getPrice()).isEqualTo(499_000);
        assertThat(first.getCreatedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(first.getUpdatedAt()).isNotNull();

        assertThat(courses.findInstructorCourses(co, CourseStatus.PENDING_REVIEW, PageRequest.of(0, 10)).getContent())
                .extracting(InstructorCourseView::getId).containsExactly(own2.getId());
        Page<InstructorCourseView> second = courses.findInstructorCourses(co, null, PageRequest.of(1, 2));
        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.getContent()).extracting(InstructorCourseView::getId).containsExactly(own1.getId());
        assertThat(courses.findInstructorCourses(main, null, PageRequest.of(0, 10)).getContent())
                .extracting(InstructorCourseView::getId).containsExactly(pending.getId(), accepted.getId());
    }

    @Test
    void findPendingQueue_oldestSubmissionFirst_onlyPending_UCA09() {
        long main = fx.user();
        Course third = courses.save(newCourse(main, NOW));
        Course first = courses.save(newCourse(main, NOW));
        Course second = courses.save(newCourse(main, NOW));
        Course draft = courses.save(newCourse(main, NOW));
        third.submitForReview(NOW.plusSeconds(300));
        first.submitForReview(NOW.plusSeconds(100));
        second.submitForReview(NOW.plusSeconds(200));
        em.flush();
        em.clear();
        Set<Long> mine = Set.of(first.getId(), second.getId(), third.getId(), draft.getId());

        Page<PendingCourseView> queue = courses.findPendingQueue(PageRequest.of(0, 1000));

        assertThat(queue.getContent()).extracting(PendingCourseView::getId).filteredOn(mine::contains)
                .containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(queue.getContent()).allSatisfy(v -> assertThat(v.getSubmittedAt()).isNotNull());
        PendingCourseView view = queue.getContent().stream().filter(v -> v.getId().equals(first.getId()))
                .findFirst().orElseThrow();
        assertThat(view.getSubmittedAt()).isEqualTo(NOW.plusSeconds(100));
        assertThat(view.getMainInstructorId()).isEqualTo(main);
        assertThat(view.getCategoryName()).isEqualTo(category.getName());
        assertThat(view.getSlug()).isEqualTo(first.getSlug());
    }

    @Test
    void findInvitationsOfInstructor_byStatus_newestFirst_UCI14() {
        long main = fx.user();
        long co = fx.user();
        Course a = courses.save(newCourse(main, NOW));
        Course b = courses.save(newCourse(main, NOW));
        Course c = courses.save(newCourse(main, NOW));
        CourseCoInstructor older = coInstructors.save(CourseCoInstructor.invite(a, co, main, NOW));
        CourseCoInstructor newer = coInstructors.save(CourseCoInstructor.invite(b, co, main, NOW.plusSeconds(60)));
        CourseCoInstructor answered = coInstructors.save(CourseCoInstructor.invite(c, co, main, NOW.plusSeconds(90)));
        answered.decline(NOW.plusSeconds(120));
        em.flush();
        em.clear();

        List<InvitationView> pendingOnes = coInstructors.findInvitationsOfInstructor(co, InvitationStatus.PENDING);
        assertThat(pendingOnes).extracting(InvitationView::getId).containsExactly(newer.getId(), older.getId());
        InvitationView view = pendingOnes.getFirst();
        assertThat(view.getCourseId()).isEqualTo(b.getId());
        assertThat(view.getCourseTitle()).isEqualTo(b.getTitle());
        assertThat(view.getCourseSlug()).isEqualTo(b.getSlug());
        assertThat(view.getCourseStatus()).isEqualTo(CourseStatus.DRAFT);
        assertThat(view.getMainInstructorId()).isEqualTo(main);
        assertThat(view.getInvitedBy()).isEqualTo(main);
        assertThat(view.getInvitedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(view.getRespondedAt()).isNull();
        assertThat(coInstructors.findInvitationsOfInstructor(co, null)).hasSize(3);
        assertThat(coInstructors.findInvitationsOfInstructor(co, InvitationStatus.DECLINED))
                .singleElement().satisfies(v -> assertThat(v.getRespondedAt()).isEqualTo(NOW.plusSeconds(120)));
        assertThat(coInstructors.findByCourseIdOrderByInvitedAtAscIdAsc(a.getId()))
                .extracting(CourseCoInstructor::getId).containsExactly(older.getId());
    }

    @Test
    void reinvite_persisted_sameRowPendingAgain_D18() {
        long main = fx.user();
        long co = fx.user();
        Course course = courses.save(newCourse(main, NOW));
        CourseCoInstructor row = coInstructors.save(CourseCoInstructor.invite(course, co, main, NOW));
        row.decline(NOW.plusSeconds(10));
        em.flush();

        CourseCoInstructor found = coInstructors.findByCourseIdAndInstructorId(course.getId(), co).orElseThrow();
        found.reinvite(main, NOW.plusSeconds(20));
        em.flush();
        em.clear();

        CourseCoInstructor reloaded = coInstructors.findByCourseIdAndInstructorId(course.getId(), co).orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(row.getId());
        assertThat(reloaded.getInvitationStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(reloaded.getInvitedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(reloaded.getRespondedAt()).isNull();
        assertThat(coInstructors.findByCourseIdAndInstructorId(course.getId(), main)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ access queries

    @Test
    void accessQueries_mainAcceptedCoPendingCoStranger_BR04() {
        long main = fx.user();
        long co = fx.user();
        long invited = fx.user();
        long stranger = fx.user();
        Course course = courses.save(newCourse(main, NOW));
        course.submitForReview(NOW);
        invite(course, co).accept(NOW);
        invite(course, invited);
        Chapter chapter = chapters.save(Chapter.create(course, "Chương", 1, NOW));
        Lesson lesson = lessons.save(videoLesson(chapter, 1));
        em.flush();
        em.clear();
        Long id = course.getId();

        assertThat(courses.existsByIdAndMainInstructorId(id, main)).isTrue();
        assertThat(courses.existsByIdAndMainInstructorId(id, co)).isFalse();
        assertThat(courses.isCourseInstructor(id, main)).isTrue();
        assertThat(courses.isCourseInstructor(id, co)).isTrue();
        assertThat(courses.isCourseInstructor(id, invited)).isFalse();
        assertThat(courses.isCourseInstructor(id, stranger)).isFalse();
        assertThat(courses.isCourseInstructor(Long.MAX_VALUE, main)).isFalse();
        assertThat(coInstructors.existsByCourseIdAndInstructorIdAndInvitationStatus(id, co, InvitationStatus.ACCEPTED))
                .isTrue();
        assertThat(coInstructors.existsByCourseIdAndInstructorIdAndInvitationStatus(id, invited,
                InvitationStatus.ACCEPTED)).isFalse();
        assertThat(courses.findStatusById(id)).contains(CourseStatus.PENDING_REVIEW);
        assertThat(courses.findStatusById(Long.MAX_VALUE)).isEmpty();
        assertThat(chapters.findCourseIdById(chapter.getId())).contains(id);
        assertThat(chapters.findCourseIdById(Long.MAX_VALUE)).isEmpty();
        assertThat(lessons.findCourseIdById(lesson.getId())).contains(id);
        assertThat(lessons.findCourseIdById(Long.MAX_VALUE)).isEmpty();
    }

    // ------------------------------------------------------------------------------------- outline and BR-07 counts

    @Test
    void outlineAndChecklistCounts_BR07() {
        long main = fx.user();
        Course course = courses.save(newCourse(main, NOW));
        Course empty = courses.save(newCourse(main, NOW));
        Chapter second = chapters.save(Chapter.create(course, "Chương 2", 2, NOW));
        Chapter first = chapters.save(Chapter.create(course, "Chương 1", 1, NOW));
        Chapter noLessons = chapters.save(Chapter.create(course, "Chương 3", 3, NOW));
        Lesson s2 = lessons.save(videoLesson(second, 2));
        Lesson s1 = lessons.save(Lesson.create(second, "Trống", LessonType.VIDEO, null, null, null, false, 1, NOW));
        Lesson f1 = lessons.save(Lesson.create(first, "Bài đọc", LessonType.ARTICLE, null, null, null, false, 1, NOW));
        Lesson f2 = lessons.save(videoLesson(first, 2));
        em.flush();
        em.clear();
        Long id = course.getId();

        assertThat(chapters.findByCourseIdOrderBySortOrderAsc(id)).extracting(Chapter::getId)
                .containsExactly(first.getId(), second.getId(), noLessons.getId());
        assertThat(lessons.findByChapterIdOrderBySortOrderAsc(second.getId())).extracting(Lesson::getId)
                .containsExactly(s1.getId(), s2.getId());
        List<Lesson> outline = lessons.findByCourseIdOrdered(id);
        assertThat(outline).extracting(Lesson::getId)
                .containsExactly(f1.getId(), f2.getId(), s1.getId(), s2.getId());
        assertThat(outline).allSatisfy(l -> assertThat(Hibernate.isInitialized(l.getChapter())).isTrue());

        assertThat(chapters.countByCourseId(id)).isEqualTo(3);
        assertThat(chapters.countWithoutLessonsByCourseId(id)).isEqualTo(1);
        assertThat(lessons.countWithoutContentByCourseId(id)).isEqualTo(2);
        assertThat(lessons.countByCourseId(id)).isEqualTo(4);
        assertThat(chapters.findMaxSortOrderByCourseId(id)).isEqualTo(3);
        assertThat(lessons.findMaxSortOrderByChapterId(second.getId())).isEqualTo(2);

        assertThat(chapters.countByCourseId(empty.getId())).isZero();
        assertThat(chapters.findMaxSortOrderByCourseId(empty.getId())).isZero();
        assertThat(lessons.findMaxSortOrderByChapterId(noLessons.getId())).isZero();
        assertThat(lessons.countByCourseId(empty.getId())).isZero();
    }

    // ------------------------------------------------------------------------------------------------------ reorder

    @Test
    void reorderChapters_twoPhase_swapWithoutUniqueViolation_UCI08() {
        Course course = courses.save(newCourse(fx.user(), NOW));
        Chapter a = chapters.save(Chapter.create(course, "A", 1, NOW));
        Chapter b = chapters.save(Chapter.create(course, "B", 2, NOW));
        Chapter c = chapters.save(Chapter.create(course, "C", 3, NOW));
        em.flush();

        // a naive single-row move collides with uq_chapters_course_order: why the two phases exist
        assertThatThrownBy(() -> jdbc.sql("UPDATE chapters SET sort_order = 2 WHERE id = ?").param(a.getId()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_chapters_course_order");

        List<Long> locked = chapters.lockIdsByCourseId(course.getId());
        assertThat(locked).containsExactly(a.getId(), b.getId(), c.getId());
        assertThat(chapters.shiftSortOrders(course.getId())).isEqualTo(3);
        assertThat(jdbc.sql("SELECT MIN(sort_order) FROM chapters WHERE course_id = ?").param(course.getId())
                .query(Integer.class).single()).isEqualTo(1 + ChapterRepository.REORDER_OFFSET);
        List<Long> wanted = List.of(c.getId(), a.getId(), b.getId());
        for (int i = 0; i < wanted.size(); i++) {
            assertThat(chapters.updateSortOrder(wanted.get(i), i + 1)).isEqualTo(1);
        }

        assertThat(chapters.findByCourseIdOrderBySortOrderAsc(course.getId()))
                .extracting(Chapter::getId, Chapter::getSortOrder)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(c.getId(), 1),
                        org.assertj.core.groups.Tuple.tuple(a.getId(), 2),
                        org.assertj.core.groups.Tuple.tuple(b.getId(), 3));
    }

    @Test
    void reorderLessons_twoPhase_swapWithoutUniqueViolation_UCI12() {
        Course course = courses.save(newCourse(fx.user(), NOW));
        Chapter chapter = chapters.save(Chapter.create(course, "A", 1, NOW));
        Chapter otherChapter = chapters.save(Chapter.create(course, "B", 2, NOW));
        Lesson x = lessons.save(videoLesson(chapter, 1));
        Lesson y = lessons.save(videoLesson(chapter, 2));
        Lesson untouched = lessons.save(videoLesson(otherChapter, 1));
        em.flush();

        assertThat(lessons.lockIdsByChapterId(chapter.getId())).containsExactly(x.getId(), y.getId());
        assertThat(lessons.shiftSortOrders(chapter.getId())).isEqualTo(2);
        lessons.updateSortOrder(y.getId(), 1);
        lessons.updateSortOrder(x.getId(), 2);

        assertThat(lessons.findByChapterIdOrderBySortOrderAsc(chapter.getId())).extracting(Lesson::getId)
                .containsExactly(y.getId(), x.getId());
        assertThat(lessons.findById(untouched.getId()).orElseThrow().getSortOrder()).isEqualTo(1);
    }

    // --------------------------------------------------------------------------------------------------- categories

    @Test
    void categoryQueries_activeOrderedByName_slugAndUsage_D21() {
        long n = SchemaFixtures.next();
        Category inactive = categories.save(Category.create("Zz danh mục " + n, "zz-" + n, NOW));
        inactive.deactivate();
        Category aa = categories.save(Category.create("Aa danh mục " + n, "aa-" + n, NOW));
        em.flush();
        em.clear();

        List<Category> active = categories.findByActiveTrueOrderByNameAsc();
        assertThat(active).extracting(Category::getId).contains(aa.getId(), category.getId())
                .doesNotContain(inactive.getId());
        assertThat(active).extracting(Category::getId).filteredOn(Set.of(aa.getId(), category.getId())::contains)
                .containsExactly(aa.getId(), category.getId());
        assertThat(categories.findAllByOrderByNameAsc()).extracting(Category::getId).contains(inactive.getId());
        assertThat(categories.existsBySlug("aa-" + n)).isTrue();
        assertThat(categories.existsBySlug("aa-" + n + "-x")).isFalse();
        assertThat(categories.existsByName("AA DANH MỤC " + n)).isTrue();

        assertThat(courses.existsByCategoryId(aa.getId())).isFalse();
        Course course = courses.save(Course.create(fx.user(), aa, "Khóa", slug(), null, null, 0, NOW));
        em.flush();
        assertThat(courses.existsByCategoryId(aa.getId())).isTrue();
        assertThat(courses.existsBySlug(course.getSlug())).isTrue();
        assertThat(courses.existsBySlug(course.getSlug() + "-x")).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    Category newCategory() {
        long n = SchemaFixtures.next();
        return categories.save(Category.create("Danh mục IT " + n, "danh-muc-it-" + n, NOW));
    }

    Course newCourse(long mainInstructorId, Instant createdAt) {
        return Course.create(mainInstructorId, category, "Khóa học " + SchemaFixtures.next(), slug(), "Mô tả",
                null, 499_000, createdAt);
    }

    CourseCoInstructor invite(Course course, long instructorId) {
        return coInstructors.save(CourseCoInstructor.invite(course, instructorId, course.getMainInstructorId(), NOW));
    }

    static Lesson videoLesson(Chapter chapter, int sortOrder) {
        return Lesson.create(chapter, "Bài " + sortOrder, LessonType.VIDEO, "https://cdn.edumy.vn/v.mp4", null, 60,
                false, sortOrder, NOW);
    }

    static String slug() {
        return "khoa-hoc-it-" + SchemaFixtures.next();
    }
}
