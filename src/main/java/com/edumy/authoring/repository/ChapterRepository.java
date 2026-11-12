package com.edumy.authoring.repository;

import java.util.List;
import java.util.Optional;

import com.edumy.authoring.entity.Chapter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code chapters}. Reorder (UC-I08, docs/ai/03 §3.4), inside one service transaction:
 * <ol>
 *   <li>{@link #lockIdsByCourseId} — lock the siblings and compare with the submitted id list;</li>
 *   <li>{@link #shiftSortOrders} — phase 1, every position + {@value #REORDER_OFFSET};</li>
 *   <li>{@link #updateSortOrder} for positions 1..n — phase 2.</li>
 * </ol>
 * The offset keeps {@code ck_chapters_order (> 0)} and never collides with uq_chapters_course_order. The bulk updates
 * flush pending changes first and clear the persistence context afterwards: re-load entities needed after them.
 */
public interface ChapterRepository extends JpaRepository<Chapter, Long> {

    /** Temporary offset of phase 1 (docs/ai/03 §3.4). */
    int REORDER_OFFSET = 1_000_000;

    /** UC-I02 outline / UC-I08: chapters of a course in display order. */
    List<Chapter> findByCourseIdOrderBySortOrderAsc(Long courseId);

    /** {@code @chapterAccess}: parent course of a chapter. */
    @Query("select ch.course.id from Chapter ch where ch.id = :id")
    Optional<Long> findCourseIdById(@Param("id") Long id);

    /** Position for a new chapter: last position + 1 (0 when the course has none). */
    @Query("select coalesce(max(ch.sortOrder), 0) from Chapter ch where ch.course.id = :courseId")
    int findMaxSortOrderByCourseId(@Param("courseId") Long courseId);

    /** BR-07 checklist: at least one chapter. */
    long countByCourseId(Long courseId);

    /** BR-07 checklist: no empty chapter. */
    @Query("""
            select count(ch) from Chapter ch
            where ch.course.id = :courseId and not exists (select 1 from Lesson l where l.chapter = ch)
            """)
    long countWithoutLessonsByCourseId(@Param("courseId") Long courseId);

    /** Reorder step 1: {@code SELECT … FOR UPDATE} of the siblings, current order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ch.id from Chapter ch where ch.course.id = :courseId order by ch.sortOrder")
    List<Long> lockIdsByCourseId(@Param("courseId") Long courseId);

    /** Reorder phase 1: move every chapter of the course out of the 1..n range. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Chapter ch set ch.sortOrder = ch.sortOrder + " + REORDER_OFFSET + " where ch.course.id = :courseId")
    int shiftSortOrders(@Param("courseId") Long courseId);

    /** Reorder phase 2: final position of one chapter. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Chapter ch set ch.sortOrder = :sortOrder where ch.id = :id")
    int updateSortOrder(@Param("id") Long id, @Param("sortOrder") int sortOrder);
}
