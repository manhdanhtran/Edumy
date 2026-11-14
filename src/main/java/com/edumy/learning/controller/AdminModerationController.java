package com.edumy.learning.controller;

import com.edumy.learning.dto.request.ModerationRequest;
import com.edumy.learning.dto.response.AnswerResponse;
import com.edumy.learning.dto.response.QuestionThreadResponse;
import com.edumy.learning.dto.response.ReviewResponse;
import com.edumy.learning.service.ModerationService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin moderation of learning content (UC-A15, A17 reviews; UC-A19 questions and answers). Admin only: route rule
 * {@code /api/v1/admin/**} plus the method-level check (docs/ai/04 §4.3); the service re-checks the admin role in the
 * database (BR-26). The body is optional ({@link ModerationRequest}); unknown id → 404; wrong state → 409.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "learning")
@PreAuthorize("hasRole('ADMIN')")
public class AdminModerationController {

    private final ModerationService moderation;

    public AdminModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    /** 200 + the review as {@code hidden}; moderator and time recorded (BR-35); leaves the average. */
    @PostMapping("/reviews/{id}/hide")
    @Operation(summary = "UC-A15 Ẩn đánh giá vi phạm")
    public ReviewResponse hideReview(@PathVariable Long id, @CurrentUserId Long adminId,
                                     @Valid @RequestBody(required = false) ModerationRequest request) {
        return moderation.hideReview(adminId, id, note(request));
    }

    /** 200 + the review as {@code visible} again (only an admin can, BR-35); counts in the average again. */
    @PostMapping("/reviews/{id}/restore")
    @Operation(summary = "UC-A17 Khôi phục đánh giá đã ẩn")
    public ReviewResponse restoreReview(@PathVariable Long id, @CurrentUserId Long adminId,
                                        @Valid @RequestBody(required = false) ModerationRequest request) {
        return moderation.restoreReview(adminId, id, note(request));
    }

    /** 200 + the thread as {@code hidden}; only its asker and admins see it from now on (BR-41). */
    @PostMapping("/questions/{id}/hide")
    @Operation(summary = "UC-A19 Ẩn câu hỏi vi phạm")
    public QuestionThreadResponse hideQuestion(@PathVariable Long id, @CurrentUserId Long adminId,
                                               @Valid @RequestBody(required = false) ModerationRequest request) {
        return moderation.hideQuestion(adminId, id, note(request));
    }

    /** 200 + the thread as {@code visible} again (only an admin, BR-41). */
    @PostMapping("/questions/{id}/restore")
    @Operation(summary = "UC-A19 Khôi phục câu hỏi đã ẩn")
    public QuestionThreadResponse restoreQuestion(@PathVariable Long id, @CurrentUserId Long adminId,
                                                  @Valid @RequestBody(required = false) ModerationRequest request) {
        return moderation.restoreQuestion(adminId, id, note(request));
    }

    /** 200 + the answer as {@code hidden}; its author can no longer edit it (BR-41). */
    @PostMapping("/answers/{id}/hide")
    @Operation(summary = "UC-A19 Ẩn phản hồi vi phạm")
    public AnswerResponse hideAnswer(@PathVariable Long id, @CurrentUserId Long adminId,
                                     @Valid @RequestBody(required = false) ModerationRequest request) {
        return moderation.hideAnswer(adminId, id, note(request));
    }

    /** 200 + the answer as {@code visible} again (only an admin, BR-41). */
    @PostMapping("/answers/{id}/restore")
    @Operation(summary = "UC-A19 Khôi phục phản hồi đã ẩn")
    public AnswerResponse restoreAnswer(@PathVariable Long id, @CurrentUserId Long adminId,
                                        @Valid @RequestBody(required = false) ModerationRequest request) {
        return moderation.restoreAnswer(adminId, id, note(request));
    }

    private static String note(ModerationRequest request) {
        return request == null ? null : request.reason();
    }
}
