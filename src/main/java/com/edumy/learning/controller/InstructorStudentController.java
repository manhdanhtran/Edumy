package com.edumy.learning.controller;

import com.edumy.learning.dto.response.StudentProgressResponse;
import com.edumy.learning.service.EnrollmentQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Students of a course (UC-I19). Course instructor with an active instructor role in the database (BR-26, no role gives
 * 403); a course the caller does not instruct gives 404, identical to an unknown one.
 */
@RestController
@RequestMapping("/api/v1/instructor/courses/{courseId}/students")
@Tag(name = "learning")
public class InstructorStudentController {

    private final EnrollmentQueryService queries;

    public InstructorStudentController(EnrollmentQueryService queries) {
        this.queries = queries;
    }

    /** {@code page}, {@code size} (default 20, max 100); newest enrollment first ({@code sort} is ignored). */
    @GetMapping
    @PreAuthorize("@courseAccess.isInstructor(#courseId)")
    @Operation(summary = "UC-I19 Xem học viên của khóa học (họ tên, ngày ghi danh, nguồn, tiến độ %)")
    public PagedModel<StudentProgressResponse> students(@PathVariable Long courseId,
                                                        @ParameterObject Pageable pageable) {
        return new PagedModel<>(queries.listStudents(courseId, pageable));
    }
}
