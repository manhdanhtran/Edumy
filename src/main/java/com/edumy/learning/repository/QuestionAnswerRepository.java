package com.edumy.learning.repository;

import java.util.List;
import java.util.Optional;

import com.edumy.learning.entity.QuestionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code question_answers}: question threads (UC-S17…S19, UC-I21) and admin moderation (UC-A19). */
public interface QuestionAnswerRepository extends JpaRepository<QuestionAnswer, Long> {

    /**
     * {@code GET /questions/{id}}: the thread in chronological order (ix_question_answers_question). Deleted answers
     * are never returned; hidden ones only when {@code includeHidden} (admin).
     */
    @Query("""
            select a from QuestionAnswer a
            where a.question.id = :questionId
              and (a.visibilityStatus = com.edumy.learning.entity.ContentStatus.VISIBLE
                   or (:includeHidden = true and a.visibilityStatus = com.edumy.learning.entity.ContentStatus.HIDDEN))
            order by a.createdAt asc, a.id asc
            """)
    List<QuestionAnswer> findThread(@Param("questionId") Long questionId,
                                    @Param("includeHidden") boolean includeHidden);

    /** {@code PATCH|DELETE /answers/{id}}: the answer only if {@code authorId} wrote it (NFR-01). */
    Optional<QuestionAnswer> findByIdAndAuthorId(Long id, Long authorId);

    /** {@code @qaAccess.isAuthor}. */
    boolean existsByIdAndAuthorId(Long id, Long authorId);
}
