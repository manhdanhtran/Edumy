package com.edumy.learning.controller;

import java.net.URI;

import com.edumy.learning.dto.request.CourseQuestionSearchRequest;
import com.edumy.learning.dto.request.CreateQuestionRequest;
import com.edumy.learning.dto.request.ResolveQuestionRequest;
import com.edumy.learning.dto.request.UpdateQuestionRequest;
import com.edumy.learning.dto.response.QuestionSummaryResponse;
import com.edumy.learning.dto.response.QuestionThreadResponse;
import com.edumy.learning.service.QaQueryService;
import com.edumy.learning.service.QuestionService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Course Q&amp;A: reads (UC-S18) and the asker's writes (UC-S17 ask, UC-S19 edit / resolve / delete). Visibility rules:
 * docs/ai/04 §4.3 ({@code @qaAccess}, {@code @enrollmentAccess}). Replies are on {@link AnswerController}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "learning")
public class QuestionController {

    private final QaQueryService queries;
    private final QuestionService questions;

    public QuestionController(QaQueryService queries, QuestionService questions) {
        this.queries = queries;
        this.questions = questions;
    }

    /** {@code page}, {@code size} (default 20, max 100); newest first ({@code sort} is ignored). */
    @GetMapping("/courses/{courseId}/questions")
    @PreAuthorize("@enrollmentAccess.canViewContent(#courseId)")
    @Operation(summary = "UC-S18 Xem hỏi đáp của khóa học (lọc theo bài, của tôi, chưa có phản hồi GV, từ khóa)")
    public PagedModel<QuestionSummaryResponse> list(@PathVariable Long courseId,
                                                    @Valid @ParameterObject @ModelAttribute
                                                    CourseQuestionSearchRequest filter,
                                                    @ParameterObject Pageable pageable,
                                                    @CurrentUserId Long userId) {
        return new PagedModel<>(queries.listForCourse(userId, courseId, filter, pageable));
    }

    @GetMapping("/questions/{id}")
    @PreAuthorize("@qaAccess.canView(#id)")
    @Operation(summary = "UC-S18 Xem một câu hỏi và các phản hồi")
    public QuestionThreadResponse thread(@PathVariable Long id, @CurrentUserId Long userId) {
        return queries.thread(userId, id);
    }

    /**
     * 201 + Location + the new thread. Enrolled students only (no enrollment / unknown course → 404 identical, E1);
     * a lesson of another course → 422 {@code LESSON_COURSE_MISMATCH} (BR-37); 400 {@code VALIDATION_FAILED} (E2).
     */
    @PostMapping("/courses/{courseId}/questions")
    @PreAuthorize("@enrollmentAccess.isEnrolled(#courseId)")
    @Operation(summary = "UC-S17 Đặt câu hỏi trong khóa học (tiêu đề ≤ 200, nội dung ≤ 5.000, bài học tùy chọn)")
    public ResponseEntity<QuestionThreadResponse> ask(@PathVariable Long courseId, @CurrentUserId Long userId,
                                                      @Valid @RequestBody CreateQuestionRequest request) {
        QuestionThreadResponse created = questions.ask(userId, courseId, request.lessonId(), request.title(),
                request.content());
        return ResponseEntity.created(URI.create("/api/v1/questions/" + created.id())).body(created);
    }

    /** 200 + the thread; only the asker's own question (404 identical otherwise); hidden → 409 (BR-41). */
    @PatchMapping("/questions/{id}")
    @PreAuthorize("@qaAccess.isQuestionAuthor(#id)")
    @Operation(summary = "UC-S19 Sửa câu hỏi của mình")
    public QuestionThreadResponse edit(@PathVariable Long id, @CurrentUserId Long userId,
                                       @Valid @RequestBody UpdateQuestionRequest request) {
        return questions.edit(userId, id, request.title(), request.content());
    }

    /** 204; the question becomes {@code deleted} (final) and its whole thread disappears. */
    @DeleteMapping("/questions/{id}")
    @PreAuthorize("@qaAccess.isQuestionAuthor(#id)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "UC-S19 Xóa câu hỏi của mình (cả chủ đề biến mất)")
    public void delete(@PathVariable Long id, @CurrentUserId Long userId) {
        questions.delete(userId, id);
    }

    /** 200 + the thread; body optional, {@code {"resolved": false}} takes the mark back (D-39). Idempotent. */
    @PostMapping("/questions/{id}/resolve")
    @PreAuthorize("@qaAccess.isQuestionAuthor(#id)")
    @Operation(summary = "UC-S19 Đánh dấu (hoặc bỏ đánh dấu) câu hỏi đã giải quyết")
    public QuestionThreadResponse resolve(@PathVariable Long id, @CurrentUserId Long userId,
                                          @RequestBody(required = false) ResolveQuestionRequest request) {
        return questions.resolve(userId, id, request == null || request.markResolved());
    }
}
