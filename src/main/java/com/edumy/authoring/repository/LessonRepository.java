package com.edumy.authoring.repository;

import java.util.List;
import java.util.Optional;

import com.edumy.authoring.entity.Lesson;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code lessons}. Reorder per chapter (UC-I12) uses the same two-phase algorithm as {@link ChapterRepository}
 * (docs/ai/03 §3.4): {@link #lockIdsByChapterId}, {@link #shiftSortOrders}, then {@link #updateSortOrder} for 1..n.
 * The bulk updates flush first and clear the persistence context afterwards.
 */
public interface LessonRepository extends JpaRepository<Lesson, Long> {

    /** UC-I12 / chapter detail: lessons of a chapter in display order. */
    List<Lesson> findByChapterIdOrderBySortOrderAsc(Long chapterId);

    /** UC-I02 outline: every lesson of a course with its chapter, in chapter order then lesson order. */
    @Query("""
            select l from Lesson l join fetch l.chapter ch
            where ch.course.id = :courseId
            order by ch.sortOrder, l.sortOrder
            """)
    List<Lesson> findByCourseIdOrdered(@Param("courseId") Long courseId);

    /** {@code @lessonAccess}: course of a lesson (through its chapter). */
    @Query("select l.chapter.course.id from Lesson l where l.id = :id")
    Optional<Long> findCourseIdById(@Param("id") Long id);

    /** Position for a new lesson: last position + 1 (0 when the chapter has none). */
    @Query("select coalesce(max(l.sortOrder), 0) from Lesson l where l.chapter.id = :chapterId")
    int findMaxSortOrderByChapterId(@Param("chapterId") Long chapterId);

    /** Learning (UC-S08 / UC-I19): number of lessons of each course in {@code courseIds}, one statement. */
    @Query("""
            select ch.course.id as courseId, count(l.id) as lessonCount
            from Lesson l join l.chapter ch
            where ch.course.id in :courseIds
            group by ch.course.id
            """)
    List<LessonCountView> countByCourseIdIn(@Param("courseIds") java.util.Collection<Long> courseIds);

    /** Learning (UC-S09): a lesson with its chapter. */
    @Query("select l from Lesson l join fetch l.chapter ch where l.id = :id")
    Optional<Lesson> findWithChapterById(@Param("id") Long id);

    /** Row of {@link #countByCourseIdIn}. */
    interface LessonCountView {
        Long getCourseId();

        long getLessonCount();
    }

    /** D-14: a published course keeps at least one lesson. */
    @Query("select count(l) from Lesson l where l.chapter.course.id = :courseId")
    long countByCourseId(@Param("courseId") Long courseId);

    /**
     * BR-07 checklist "every lesson has content". ck_lessons_content_match keeps the field of the other type null, so
     * "no content" = both fields null (the entity stores blank content as null).
     */
    @Query("""
            select count(l) from Lesson l
            where l.chapter.course.id = :courseId and l.videoUrl is null and l.articleContent is null
            """)
    long countWithoutContentByCourseId(@Param("courseId") Long courseId);

    /** Reorder step 1: {@code SELECT … FOR UPDATE} of the lessons of a chapter, current order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l.id from Lesson l where l.chapter.id = :chapterId order by l.sortOrder")
    List<Long> lockIdsByChapterId(@Param("chapterId") Long chapterId);

    /** Reorder phase 1: move every lesson of the chapter out of the 1..n range. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Lesson l set l.sortOrder = l.sortOrder + " + ChapterRepository.REORDER_OFFSET
            + " where l.chapter.id = :chapterId")
    int shiftSortOrders(@Param("chapterId") Long chapterId);

    /** Reorder phase 2: final position of one lesson. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Lesson l set l.sortOrder = :sortOrder where l.id = :id")
    int updateSortOrder(@Param("id") Long id, @Param("sortOrder") int sortOrder);
}
