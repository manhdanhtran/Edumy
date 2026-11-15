package com.edumy.learning.controller;

import com.edumy.learning.dto.request.CompletionRequest;
import com.edumy.learning.dto.response.LearningOutlineResponse;
import com.edumy.learning.dto.response.LessonContentResponse;
import com.edumy.learning.dto.response.ProgressResponse;
import com.edumy.learning.service.CertificateCatchUpService;
import com.edumy.learning.service.EnrollmentQueryService;
import com.edumy.learning.service.ProgressService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learning room: outline and lesson content. Open to the enrolled student, the course instructor and the admin
 * (D-32), whatever the course status (BR-31); anyone else gets 404, identical to an unknown id (NFR-01). Writes: the
 * lesson visit (D-34) and the manual completion toggle (UC-S10, enrolled students only).
 */
@RestController
@RequestMapping("/api/v1/learn")
@Tag(name = "learning")
public class LearningController {

    private final EnrollmentQueryService queries;
    private final ProgressService progress;
    private final CertificateCatchUpService catchUp;

    public LearningController(EnrollmentQueryService queries, ProgressService progress,
                              CertificateCatchUpService catchUp) {
        this.queries = queries;
        this.progress = progress;
        this.catchUp = catchUp;
    }

    /** UC-S11 / D-48: the caller's missing certificate of this course (100 % after a lesson deletion) is issued first. */
    @GetMapping("/courses/{courseId}")
    @PreAuthorize("@enrollmentAccess.canViewContent(#courseId)")
    @Operation(summary = "UC-S11 Xem đề cương và tiến độ khóa học (GV, Admin xem nội dung, không có tiến độ)")
    public LearningOutlineResponse outline(@PathVariable Long courseId, @CurrentUserId Long userId) {
        catchUp.issueMissing(userId, courseId);
        return queries.outline(userId, courseId);
    }

    /** Records nothing: the visit that feeds "continue learning" is {@code POST /learn/lessons/{id}/visit} (D-34). */
    @GetMapping("/lessons/{lessonId}")
    @PreAuthorize("@enrollmentAccess.canViewLesson(#lessonId)")
    @Operation(summary = "UC-S09 Xem nội dung bài học (video hoặc bài viết)")
    public LessonContentResponse lesson(@PathVariable Long lessonId, @CurrentUserId Long userId) {
        return queries.lesson(userId, lessonId);
    }

    /** 204. Students: the lesson's last-accessed time moves ("Học tiếp"). Instructor / admin: no enrollment, no-op (D-32). */
    @PostMapping("/lessons/{lessonId}/visit")
    @PreAuthorize("@enrollmentAccess.canViewLesson(#lessonId)")
    @Operation(summary = "UC-S09 D-34 Ghi nhận thời điểm truy cập bài học (Học tiếp); GV / Admin không có tiến độ")
    public ResponseEntity<Void> visit(@PathVariable Long lessonId, @CurrentUserId Long userId) {
        progress.visit(userId, lessonId);
        return ResponseEntity.noContent().build();
    }

    /** 200 + the progress after the change; only an enrolled student (D-32), anyone else 404 identical (NFR-01). */
    @PutMapping("/lessons/{lessonId}/completion")
    @PreAuthorize("@enrollmentAccess.isEnrolledForLesson(#lessonId)")
    @Operation(summary = "UC-S10 Đánh dấu / bỏ đánh dấu hoàn thành bài học (cấp chứng chỉ khi đạt 100%)")
    public ProgressResponse completion(@PathVariable Long lessonId, @CurrentUserId Long userId,
                                       @Valid @RequestBody CompletionRequest request) {
        return progress.setCompletion(userId, lessonId, request.completed());
    }
}
