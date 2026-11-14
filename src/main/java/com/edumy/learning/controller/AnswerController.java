package com.edumy.learning.controller;

import java.net.URI;

import com.edumy.learning.dto.request.CreateAnswerRequest;
import com.edumy.learning.dto.request.UpdateAnswerRequest;
import com.edumy.learning.dto.response.AnswerResponse;
import com.edumy.learning.service.AnswerService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replies in a question thread (UC-I21 instructor answer, UC-S19 the asker's follow-up and the author's own edit /
 * delete). BR-38: only the asker and the course instructors reply ({@code @qaAccess.canReply}); edit and delete only
 * by the answer's author ({@code @qaAccess.isAnswerAuthor}).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "learning")
public class AnswerController {

    private final AnswerService answers;

    public AnswerController(AnswerService answers) {
        this.answers = answers;
    }

    /**
     * 201 + Location (the thread) + the answer; {@code authorType} instructor or student is decided by the server. A
     * question the caller cannot see → 404 identical; seen but neither asker nor instructor → 403
     * {@code QA_REPLY_NOT_ALLOWED}; hidden question → 409 {@code QA_CONTENT_HIDDEN_BY_ADMIN}.
     */
    @PostMapping("/questions/{id}/answers")
    @PreAuthorize("@qaAccess.canReply(#id)")
    @Operation(summary = "UC-I21 / UC-S19 Trả lời câu hỏi (giảng viên của khóa học hoặc người hỏi)")
    public ResponseEntity<AnswerResponse> post(@PathVariable Long id, @CurrentUserId Long userId,
                                               @Valid @RequestBody CreateAnswerRequest request) {
        AnswerResponse created = answers.post(userId, id, request.content());
        return ResponseEntity.created(URI.create("/api/v1/questions/" + id)).body(created);
    }

    /** 200 + the answer; only the author's own (404 identical otherwise); hidden → 409 (BR-41). */
    @PatchMapping("/answers/{id}")
    @PreAuthorize("@qaAccess.isAnswerAuthor(#id)")
    @Operation(summary = "UC-I21 / UC-S19 Sửa phản hồi của mình")
    public AnswerResponse edit(@PathVariable Long id, @CurrentUserId Long userId,
                               @Valid @RequestBody UpdateAnswerRequest request) {
        return answers.edit(userId, id, request.content());
    }

    /** 204; the answer becomes {@code deleted} (final). */
    @DeleteMapping("/answers/{id}")
    @PreAuthorize("@qaAccess.isAnswerAuthor(#id)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "UC-I21 / UC-S19 Xóa phản hồi của mình")
    public void delete(@PathVariable Long id, @CurrentUserId Long userId) {
        answers.delete(userId, id);
    }
}
