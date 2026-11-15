package com.edumy.authoring.service;

import com.edumy.authoring.dto.response.InstructorRevocationImpactResponse;
import com.edumy.authoring.entity.InvitationStatus;
import com.edumy.authoring.repository.CourseCoInstructorRepository;
import com.edumy.authoring.repository.CourseRepository;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-A05 step 1 (P10, additive contract): the impact preview shown before the admin confirms the revocation. The
 * counts come from authoring's own tables (courses, course_co_instructors) — identity, which owns the revocation, never
 * reads them (docs/ai/01 §1.8: identity depends on nothing but shared). Three statements after the two checks.
 */
@Service
@Transactional(readOnly = true)
public class InstructorImpactQueryService {

    private final CourseRepository courses;
    private final CourseCoInstructorRepository coInstructors;
    private final UserQueryApi users;
    private final RoleQueryApi roles;

    public InstructorImpactQueryService(CourseRepository courses, CourseCoInstructorRepository coInstructors,
                                        UserQueryApi users, RoleQueryApi roles) {
        this.courses = courses;
        this.coInstructors = coInstructors;
        this.users = users;
        this.roles = roles;
    }

    /**
     * Unknown user → 404; no active instructor role → 409 {@code INSTRUCTOR_ROLE_NOT_ACTIVE} (the same answer UC-A05
     * E1 gives to the revocation itself, so the SPA can show it before the confirmation).
     */
    public InstructorRevocationImpactResponse revocationImpact(Long userId) {
        if (users.findSummary(userId).isEmpty()) {
            throw BusinessException.notFound();
        }
        if (!roles.hasActiveRole(userId, RoleCode.INSTRUCTOR)) {
            throw new BusinessException(ErrorCode.INSTRUCTOR_ROLE_NOT_ACTIVE);
        }
        return new InstructorRevocationImpactResponse(
                courses.countByMainInstructorId(userId),
                coInstructors.countByInstructorIdAndInvitationStatus(userId, InvitationStatus.ACCEPTED),
                courses.countPublishedInstructedBy(userId));
    }
}
