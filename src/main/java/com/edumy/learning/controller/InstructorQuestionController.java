package com.edumy.learning.controller;

import com.edumy.learning.dto.request.InstructorQuestionSearchRequest;
import com.edumy.learning.dto.response.QuestionSummaryResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Questions of the courses the caller instructs (UC-I22); active instructor role re-read from the database (BR-26). */
@RestController
@RequestMapping("/api/v1/instructor/questions")
@Tag(name = "learning")
@PreAuthorize("@enrollmentAccess.isActiveInstructor()")
public class InstructorQuestionController {

    private final QaQueryService queries;

    public InstructorQuestionController(QaQueryService queries) {
        this.queries = queries;
    }

    /**
     * {@code page}, {@code size} (default 20, max 100); <b>oldest first</b> ({@code sort} is ignored); {@code status}
     * defaults to {@code unanswered}, {@code all} shows every visible question (UC-I22, P10 Q-b).
     */
    @GetMapping
    @Operation(summary = "UC-I22 Xem câu hỏi của các khóa phụ trách (lọc theo khóa, chưa có phản hồi GV)")
    public PagedModel<QuestionSummaryResponse> list(
            @Valid @ParameterObject @ModelAttribute InstructorQuestionSearchRequest filter,
            @ParameterObject Pageable pageable, @CurrentUserId Long userId) {
        return new PagedModel<>(queries.listForInstructor(userId, filter.courseId(), filter.unansweredOnly(), pageable));
    }
}
