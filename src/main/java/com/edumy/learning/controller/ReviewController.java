package com.edumy.learning.controller;

import com.edumy.learning.dto.request.CreateReviewRequest;
import com.edumy.learning.dto.request.UpdateReviewRequest;
import com.edumy.learning.dto.response.ReviewResponse;
import com.edumy.learning.service.ReviewService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The student's own review of a course (UC-S12…S14). Login only: the enrollment (create) and the author (edit,
 * withdraw) are decided by the service from the caller's id. There is no GET here: the visible reviews, including the
 * caller's, are listed by {@code GET /catalog/courses/{slug}/reviews}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "learning")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * 201 + the new review; a withdrawn review of the enrollment is re-rated (same row) → 200. 404 unknown course · 403
     * {@code ENROLLMENT_REQUIRED} · 409 already visible (BR-18) or {@code REVIEW_HIDDEN_BY_ADMIN} (BR-35).
     */
    @PostMapping("/courses/{courseId}/reviews")
    @Operation(summary = "UC-S12 Đánh giá khóa học (1–5 sao, nhận xét tùy chọn; đánh giá lại sau khi gỡ)")
    public ResponseEntity<ReviewResponse> create(@CurrentUserId Long userId, @PathVariable Long courseId,
                                                 @Valid @RequestBody CreateReviewRequest request) {
        ReviewService.Result result = reviews.create(userId, courseId, request.rating(), request.comment());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.review());
    }

    /** 200 + the review; only the author's visible review (404 otherwise, identical); hidden → 409 (BR-35/BR-41). */
    @PatchMapping("/reviews/{id}")
    @Operation(summary = "UC-S13 Cập nhật đánh giá của mình")
    public ReviewResponse update(@CurrentUserId Long userId, @PathVariable Long id,
                                 @Valid @RequestBody UpdateReviewRequest request) {
        return reviews.update(userId, id, request.rating(), request.comment());
    }

    /** 204; the review becomes {@code withdrawn} (not physically deleted, BR-18) and can be re-rated. */
    @DeleteMapping("/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "UC-S14 Xóa đánh giá của mình (chuyển sang đã gỡ)")
    public void withdraw(@CurrentUserId Long userId, @PathVariable Long id) {
        reviews.withdraw(userId, id);
    }
}
