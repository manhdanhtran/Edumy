package com.edumy.learning.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import com.edumy.learning.entity.ContentStatus;
import com.edumy.learning.entity.CourseQuestion;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code course_questions}. Deleted questions are never listed (the asker deleted the whole thread); hidden ones only
 * for an admin ({@code includeHidden}) or for their own asker (UC-S18, so the student sees why it disappeared).
 * {@code answerCount} counts visible answers only. List queries fix their order: pass an unsorted {@link Pageable}.
 */
public interface CourseQuestionRepository extends JpaRepository<CourseQuestion, Long> {

    /**
     * UC-S17 E3 (P13): the enrollment's non-deleted questions asked since {@code since} (a handful at most), to refuse a
     * repeated submission; compared exactly in Java (the ai_ci collation would also match other accents / cases).
     */
    @Query("""
            select q from CourseQuestion q
            where q.enrollment.id = :enrollmentId and q.createdAt >= :since
              and q.visibilityStatus <> com.edumy.learning.entity.ContentStatus.DELETED
            """)
    java.util.List<CourseQuestion> findRecentByEnrollment(@Param("enrollmentId") Long enrollmentId,
                                                         @Param("since") java.time.Instant since);

    /**
     * UC-S17 {@code GET /courses/{courseId}/questions}: questions of a course, newest first; {@code lessonId} null =
     * every lesson and course-level questions. Visible only, plus hidden ones when {@code includeHidden} (admin).
     */
    @Query(value = """
            select q.id as id, e.courseId as courseId, e.studentId as askerId, q.lessonId as lessonId,
                   q.title as title, q.resolved as resolved, q.visibilityStatus as visibilityStatus,
                   q.createdAt as createdAt,
                   (select count(a) from QuestionAnswer a
                    where a.question = q and a.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       as answerCount,
                   case when exists (select 1 from QuestionAnswer ra where ra.question = q
                       and ra.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                       and ra.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       then true else false end as instructorReplied
            from CourseQuestion q join q.enrollment e
            where e.courseId = :courseId
              and (:lessonId is null or q.lessonId = :lessonId)
              and (q.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE
                   or (:includeHidden = true and q.visibilityStatus = com.edumy.learning.entity.ContentStatus.HIDDEN))
            order by q.createdAt desc, q.id desc
            """,
            countQuery = """
            select count(q) from CourseQuestion q join q.enrollment e
            where e.courseId = :courseId
              and (:lessonId is null or q.lessonId = :lessonId)
              and (q.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE
                   or (:includeHidden = true and q.visibilityStatus = com.edumy.learning.entity.ContentStatus.HIDDEN))
            """)
    Page<QuestionSummaryView> findByCourse(@Param("courseId") Long courseId, @Param("lessonId") Long lessonId,
                                           @Param("includeHidden") boolean includeHidden, Pageable pageable);

    /**
     * UC-S18: questions asked by a student in every course, newest first (ix_course_questions_enrollment); visible and
     * hidden (shown as hidden to the asker), never deleted.
     */
    @Query(value = """
            select q.id as id, e.courseId as courseId, e.studentId as askerId, q.lessonId as lessonId,
                   q.title as title, q.resolved as resolved, q.visibilityStatus as visibilityStatus,
                   q.createdAt as createdAt,
                   (select count(a) from QuestionAnswer a
                    where a.question = q and a.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       as answerCount,
                   case when exists (select 1 from QuestionAnswer ra where ra.question = q
                       and ra.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                       and ra.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       then true else false end as instructorReplied
            from CourseQuestion q join q.enrollment e
            where e.studentId = :studentId
              and q.visibilityStatus <> com.edumy.learning.entity.ContentStatus.DELETED
            order by q.createdAt desc, q.id desc
            """,
            countQuery = """
            select count(q) from CourseQuestion q join q.enrollment e
            where e.studentId = :studentId
              and q.visibilityStatus <> com.edumy.learning.entity.ContentStatus.DELETED
            """)
    Page<QuestionSummaryView> findByStudent(@Param("studentId") Long studentId, Pageable pageable);

    /**
     * UC-I22 {@code GET /instructor/questions?courseId=&status=}: visible questions of the given courses (the
     * instructor's courses, from {@code authoring::api}), newest first. {@code unansweredOnly}: only questions without a
     * visible instructor answer (ix_question_answers_instructor_visible) — a student follow-up or a hidden / deleted
     * instructor answer does not count as answered. {@code courseIds} must not be empty.
     */
    @Query(value = """
            select q.id as id, e.courseId as courseId, e.studentId as askerId, q.lessonId as lessonId,
                   q.title as title, q.resolved as resolved, q.visibilityStatus as visibilityStatus,
                   q.createdAt as createdAt,
                   (select count(a) from QuestionAnswer a
                    where a.question = q and a.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       as answerCount,
                   case when exists (select 1 from QuestionAnswer ra where ra.question = q
                       and ra.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                       and ra.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       then true else false end as instructorReplied
            from CourseQuestion q join q.enrollment e
            where e.courseId in :courseIds
              and q.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE
              and (:unansweredOnly = false
                   or not exists (select 1 from QuestionAnswer ia
                                  where ia.question = q
                                    and ia.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                                    and ia.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE))
            order by q.createdAt asc, q.id asc
            """,
            countQuery = """
            select count(q) from CourseQuestion q join q.enrollment e
            where e.courseId in :courseIds
              and q.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE
              and (:unansweredOnly = false
                   or not exists (select 1 from QuestionAnswer ia
                                  where ia.question = q
                                    and ia.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                                    and ia.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE))
            """)
    Page<QuestionSummaryView> findForInstructor(@Param("courseIds") Collection<Long> courseIds,
                                                @Param("unansweredOnly") boolean unansweredOnly, Pageable pageable);

    /**
     * UC-S18 {@code GET /courses/{courseId}/questions}: visible questions of a course, newest first, plus hidden ones
     * for an admin ({@code allHidden}) and the caller's own hidden ones (BR-41, docs/ai/04 §4.3). Optional filters:
     * {@code lessonId}, {@code askerId} ("mine"), {@code unansweredOnly} (no visible instructor answer),
     * {@code pattern} (LIKE pattern with escape {@code !} on title or content; {@code %} = no keyword).
     */
    @Query(value = """
            select q.id as id, e.courseId as courseId, e.studentId as askerId, q.lessonId as lessonId,
                   q.title as title, q.resolved as resolved, q.visibilityStatus as visibilityStatus,
                   q.createdAt as createdAt,
                   (select count(a) from QuestionAnswer a
                    where a.question = q and a.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       as answerCount,
                   case when exists (select 1 from QuestionAnswer ra where ra.question = q
                       and ra.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                       and ra.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE)
                       then true else false end as instructorReplied
            from CourseQuestion q join q.enrollment e
            where e.courseId = :courseId
              and (:lessonId is null or q.lessonId = :lessonId)
              and (:askerId is null or e.studentId = :askerId)
              and (q.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE
                   or (q.visibilityStatus = com.edumy.learning.entity.ContentStatus.HIDDEN
                       and (:allHidden = true or e.studentId = :viewerId)))
              and (:unansweredOnly = false
                   or not exists (select 1 from QuestionAnswer ia
                                  where ia.question = q
                                    and ia.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                                    and ia.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE))
              and (q.title like :pattern escape '!' or q.content like :pattern escape '!')
            order by q.createdAt desc, q.id desc
            """,
            countQuery = """
            select count(q) from CourseQuestion q join q.enrollment e
            where e.courseId = :courseId
              and (:lessonId is null or q.lessonId = :lessonId)
              and (:askerId is null or e.studentId = :askerId)
              and (q.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE
                   or (q.visibilityStatus = com.edumy.learning.entity.ContentStatus.HIDDEN
                       and (:allHidden = true or e.studentId = :viewerId)))
              and (:unansweredOnly = false
                   or not exists (select 1 from QuestionAnswer ia
                                  where ia.question = q
                                    and ia.authorType = com.edumy.learning.entity.AuthorType.INSTRUCTOR
                                    and ia.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE))
              and (q.title like :pattern escape '!' or q.content like :pattern escape '!')
            """)
    Page<QuestionSummaryView> search(@Param("courseId") Long courseId, @Param("lessonId") Long lessonId,
                                     @Param("askerId") Long askerId, @Param("viewerId") Long viewerId,
                                     @Param("allHidden") boolean allHidden,
                                     @Param("unansweredOnly") boolean unansweredOnly,
                                     @Param("pattern") String pattern, Pageable pageable);

    /** UC-S18 thread: the question with its enrollment (asker, course). */
    @Query("select q from CourseQuestion q join fetch q.enrollment where q.id = :id")
    Optional<CourseQuestion> findWithEnrollmentById(@Param("id") Long id);

    /** UC-S19 {@code PATCH|DELETE /questions/{id}}, resolve: the question only if the student asked it (NFR-01). */
    Optional<CourseQuestion> findByIdAndEnrollmentStudentId(Long id, Long studentId);

    /**
     * UC-S19 / UC-I21 / UC-A19 writes: the question locked ({@code FOR UPDATE}), with its enrollment (asker, course), so
     * an asker edit, a reply and an admin hide of one question are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from CourseQuestion q join fetch q.enrollment where q.id = :id")
    Optional<CourseQuestion> findByIdForUpdate(@Param("id") Long id);

    /** {@code @qaAccess}: is {@code studentId} the asker of the question (BR-38)? */
    boolean existsByIdAndEnrollmentStudentId(Long id, Long studentId);

    /** {@code @qaAccess} / {@code @enrollmentAccess}: course of a question (through the asker's enrollment). */
    @Query("select q.enrollment.courseId from CourseQuestion q where q.id = :id")
    Optional<Long> findCourseIdById(@Param("id") Long id);

    /** {@code @qaAccess}: course, asker and visibility of a question in one query (BR-38, NFR-01). */
    @Query("""
            select e.courseId as courseId, e.studentId as askerId, q.visibilityStatus as visibilityStatus
            from CourseQuestion q join q.enrollment e
            where q.id = :id
            """)
    Optional<QuestionAccessView> findAccessViewById(@Param("id") Long id);

    /** What {@code @qaAccess} needs to know about a question. */
    interface QuestionAccessView {
        Long getCourseId();

        Long getAskerId();

        ContentStatus getVisibilityStatus();
    }

    /** Row of the question lists (UC-S17, UC-S18, UC-I22). Asker names come from {@code identity::api}. */
    interface QuestionSummaryView {
        Long getId();

        Long getCourseId();

        Long getAskerId();

        Long getLessonId();

        String getTitle();

        boolean isResolved();

        ContentStatus getVisibilityStatus();

        Instant getCreatedAt();

        long getAnswerCount();

        /** D-39: a visible answer by an instructor exists. */
        boolean isInstructorReplied();
    }
}
