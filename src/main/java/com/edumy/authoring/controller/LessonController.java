package com.edumy.authoring.controller;

import java.net.URI;

import com.edumy.authoring.dto.request.CreateLessonRequest;
import com.edumy.authoring.dto.request.ReorderRequest;
import com.edumy.authoring.dto.request.UpdateLessonRequest;
import com.edumy.authoring.dto.response.ChapterResponse;
import com.edumy.authoring.dto.response.LessonResponse;
import com.edumy.authoring.service.CourseQueryService;
import com.edumy.authoring.service.CreateLessonCommand;
import com.edumy.authoring.service.LessonService;
import com.edumy.authoring.service.UpdateLessonCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lessons of a chapter (UC-I09…I12), for the main instructor and accepted co-instructors (BR-04) holding an
 * <b>active</b> instructor role in the database (BR-26: else 403). A chapter / lesson of a course the caller does not
 * teach answers 404 like an unknown id (NFR-01); a {@code pending_review} course 409 {@code COURSE_NOT_EDITABLE}.
 */
@RestController
@RequestMapping("/api/v1/instructor")
@Tag(name = "authoring")
public class LessonController {

    private final LessonService lessons;
    private final CourseQueryService queries;

    public LessonController(LessonService lessons, CourseQueryService queries) {
        this.lessons = lessons;
        this.queries = queries;
    }

    /** 201 + the new lesson (last position of the chapter). 400 when content does not match the type (D-22). */
    @PostMapping("/chapters/{id}/lessons")
    @PreAuthorize("@chapterAccess.canEditContent(#id)")
    @Operation(summary = "UC-I09 Thêm bài học (video hoặc bài viết, tùy chọn xem trước)")
    public ResponseEntity<LessonResponse> create(@PathVariable Long id,
                                                 @Valid @RequestBody CreateLessonRequest request) {
        Long lessonId = lessons.create(id, new CreateLessonCommand(request.title(), request.contentType(),
                request.videoUrl(), request.articleContent(), request.durationSeconds(),
                Boolean.TRUE.equals(request.isPreview())));
        return ResponseEntity.created(URI.create("/api/v1/instructor/lessons/" + lessonId))
                .body(queries.lesson(lessonId));
    }

    /** 200 + the lesson; only the fields sent change (effective at once on a published course, D-14). */
    @PatchMapping("/lessons/{id}")
    @PreAuthorize("@lessonAccess.canEditContent(#id)")
    @Operation(summary = "UC-I10 Cập nhật bài học (tiêu đề, nội dung, thời lượng, cờ xem trước)")
    public LessonResponse update(@PathVariable Long id, @Valid @RequestBody UpdateLessonRequest request) {
        lessons.update(id, new UpdateLessonCommand(request.title(), request.contentType(), request.videoUrl(),
                request.articleContent(), request.durationSeconds(), request.isPreview()));
        return queries.lesson(id);
    }

    /**
     * 204 = deleted with its progress, its questions become course-level questions; the following lessons move up.
     * 409 {@code COURSE_LAST_LESSON_DELETE_FORBIDDEN}: the only lesson of a published / hidden course (D-14).
     */
    @DeleteMapping("/lessons/{id}")
    @PreAuthorize("@lessonAccess.canEditContent(#id)")
    @Operation(summary = "UC-I11 Xóa bài học")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        lessons.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code ids} = every lesson of the chapter once, in the new order → 200 + the chapter with its lessons 1..n. 400
     * {@code VALIDATION_FAILED} on {@code ids} when an id is missing, foreign or repeated.
     */
    @PutMapping("/chapters/{id}/lessons/order")
    @PreAuthorize("@chapterAccess.canEditContent(#id)")
    @Operation(summary = "UC-I12 Sắp xếp bài học trong chương")
    public ChapterResponse reorder(@PathVariable Long id, @Valid @RequestBody ReorderRequest request) {
        lessons.reorder(id, request.ids());
        return queries.chapter(id);
    }
}
