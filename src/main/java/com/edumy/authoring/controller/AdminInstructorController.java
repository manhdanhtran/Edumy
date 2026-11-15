package com.edumy.authoring.controller;

import com.edumy.authoring.dto.response.InstructorRevocationImpactResponse;
import com.edumy.authoring.service.InstructorImpactQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin view of a user's instructor work (UC-A05 step 1). Lives in authoring because the courses and invitations it
 * counts are authoring's tables; the revocation itself stays in identity ({@code DELETE /admin/users/{id}/roles/
 * instructor}). Admin only: route rule {@code /api/v1/admin/**} plus {@code hasRole('ADMIN')}.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "authoring")
@PreAuthorize("hasRole('ADMIN')")
public class AdminInstructorController {

    private final InstructorImpactQueryService impact;

    public AdminInstructorController(InstructorImpactQueryService impact) {
        this.impact = impact;
    }

    /**
     * 200 {@code {mainCourseCount, coInstructorCourseCount, publishedCourseCount}} · 404 unknown user · 409
     * {@code INSTRUCTOR_ROLE_NOT_ACTIVE} (no active instructor role, nothing to revoke).
     */
    @GetMapping("/{userId}/instructor-revocation-impact")
    @Operation(summary = "UC-A05 Xem ảnh hưởng trước khi thu hồi quyền giảng viên (số khóa chính, cộng tác, đang công khai)")
    public InstructorRevocationImpactResponse revocationImpact(@PathVariable Long userId) {
        return impact.revocationImpact(userId);
    }
}
