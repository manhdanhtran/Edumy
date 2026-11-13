package com.edumy.learning.controller;

import com.edumy.learning.dto.response.LearningOutlineResponse;
import com.edumy.learning.dto.response.LessonContentResponse;
import com.edumy.learning.service.EnrollmentQueryService;
import com.edumy.shared.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learning room: outline and lesson content. Open to the enrolled student, the course instructor and the admin
 * (D-32), whatever the course status (BR-31); anyone else gets 404, identical to an unknown id (NFR-01).
 */
@RestController
@RequestMapping("/api/v1/learn")
@Tag(name = "learning")
public class LearningController {

    private final EnrollmentQueryService queries;

    public LearningController(EnrollmentQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/courses/{courseId}")
    @PreAuthorize("@enrollmentAccess.canViewContent(#courseId)")
    @Operation(summary = "UC-S11 Xem đề cương và tiến độ khóa học (GV, Admin xem nội dung, không có tiến độ)")
    public LearningOutlineResponse outline(@PathVariable Long courseId, @CurrentUserId Long userId) {
        return queries.outline(userId, courseId);
    }

    /** Records nothing: the visit that feeds "continue learning" is {@code POST /learn/lessons/{id}/visit} (D-34). */
    @GetMapping("/lessons/{lessonId}")
    @PreAuthorize("@enrollmentAccess.canViewLesson(#lessonId)")
    @Operation(summary = "UC-S09 Xem nội dung bài học (video hoặc bài viết)")
    public LessonContentResponse lesson(@PathVariable Long lessonId, @CurrentUserId Long userId) {
        return queries.lesson(userId, lessonId);
    }
}
