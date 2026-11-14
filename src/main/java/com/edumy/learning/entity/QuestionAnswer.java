package com.edumy.learning.entity;

import java.time.Instant;
import java.util.Objects;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.jpa.JpaEntities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code question_answers} (EDU-DDD-001 §5.19): a reply in a question thread. Content ≤ 5000 characters of plain text,
 * trimmed and non-blank (D-44, ck_question_answers_content). {@code author_type} is a snapshot of the author's role
 * when posting. Only a course instructor or the asker may answer (BR-38) — checked by the service / {@code @qaAccess}.
 * <p>State machine (docs/ai/02 §2.2): new → visible (only on a visible question) · visible → deleted (author, final) ·
 * visible → hidden (admin) · hidden → visible (admin, BR-35). The author edits only a visible answer (BR-41: hidden →
 * {@code QA_CONTENT_HIDDEN_BY_ADMIN}). Any other transition → {@code DATA_CONFLICT}. {@code moderated_by} / {@code moderated_at} are set together by the admin
 * transitions (ck_question_answers_moderation_pair, ck_question_answers_hidden_moderated).
 */
@Entity
@Table(name = "question_answers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false, updatable = false)
    private CourseQuestion question;

    /** Author (identity): plain id. */
    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(name = "author_type", nullable = false, length = 20, updatable = false)
    private AuthorType authorType;

    @Column(name = "content", nullable = false, length = CourseQuestion.CONTENT_MAX_LENGTH)
    private String content;

    @Column(name = "visibility_status", nullable = false, length = 20)
    private ContentStatus visibilityStatus;

    /** Admin of the last hide / restore (BR-26 checked by the service). */
    @Column(name = "moderated_by")
    private Long moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by MySQL (ON UPDATE CURRENT_TIMESTAMP(6)); null on a freshly persisted instance until reloaded. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * UC-I21 / UC-S19: new visible answer of {@code authorId} in a visible question (a hidden question takes no answer
     * → {@code QA_CONTENT_HIDDEN_BY_ADMIN}, a deleted one → {@code DATA_CONFLICT}, UC-I21 E2).
     */
    public static QuestionAnswer post(CourseQuestion question, Long authorId, AuthorType authorType, String content,
                                      Instant now) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(authorId, "authorId");
        Objects.requireNonNull(authorType, "authorType");
        Objects.requireNonNull(now, "now");
        String validContent = requireContent(content);
        question.requireOpenForAuthor();
        QuestionAnswer answer = new QuestionAnswer();
        answer.question = question;
        answer.authorId = authorId;
        answer.authorType = authorType;
        answer.content = validContent;
        answer.visibilityStatus = ContentStatus.VISIBLE;
        answer.createdAt = now;
        return answer;
    }

    /** UC-I21 / UC-S19: the author edits a visible answer (hidden, BR-41 → {@code QA_CONTENT_HIDDEN_BY_ADMIN}). */
    public void edit(String newContent) {
        requireOpenForAuthor();
        content = requireContent(newContent);
    }

    /** visible → deleted (author, final). Hidden → BR-41. */
    public void delete() {
        requireOpenForAuthor();
        visibilityStatus = ContentStatus.DELETED;
    }

    /** UC-A19: visible → hidden (admin). */
    public void hide(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        requireVisible();
        visibilityStatus = ContentStatus.HIDDEN;
        moderatedBy = adminId;
        moderatedAt = now;
    }

    /** UC-A19: hidden → visible (admin only, BR-35). */
    public void restore(Long adminId, Instant now) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(now, "now");
        if (visibilityStatus != ContentStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
        visibilityStatus = ContentStatus.VISIBLE;
        moderatedBy = adminId;
        moderatedAt = now;
    }

    public boolean isVisible() {
        return visibilityStatus == ContentStatus.VISIBLE;
    }

    public boolean isAuthoredBy(Long userId) {
        return authorId.equals(userId);
    }

    private void requireVisible() {
        if (visibilityStatus != ContentStatus.VISIBLE) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT);
        }
    }

    private void requireOpenForAuthor() {
        if (visibilityStatus == ContentStatus.HIDDEN) {
            throw new BusinessException(ErrorCode.QA_CONTENT_HIDDEN_BY_ADMIN);
        }
        requireVisible();
    }

    private static String requireContent(String content) {
        return CourseQuestion.requireText(content, "content", CourseQuestion.CONTENT_MAX_LENGTH, "Nội dung phản hồi");
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || JpaEntities.effectiveClass(this) != JpaEntities.effectiveClass(o)) {
            return false;
        }
        Long id = getId();
        return id != null && id.equals(((QuestionAnswer) o).getId());
    }

    @Override
    public final int hashCode() {
        return JpaEntities.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "QuestionAnswer[id=" + getId() + ", authorType=" + authorType + ", status=" + visibilityStatus + "]";
    }
}
