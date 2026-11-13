package com.edumy.learning.controller;

import com.edumy.learning.dto.request.CourseQuestionSearchRequest;
import com.edumy.learning.dto.response.QuestionSummaryResponse;
import com.edumy.learning.dto.response.QuestionThreadResponse;
import com.edumy.learning.service.QaQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Course Q&amp;A reads (UC-S18). Visibility rules: docs/ai/04 §4.3 ({@code @qaAccess}, {@code @enrollmentAccess}). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "learning")
public class QuestionController {

    private final QaQueryService queries;

    public QuestionController(QaQueryService queries) {
        this.queries = queries;
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
}
