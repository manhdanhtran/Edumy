package com.edumy.authoring.service;

import java.util.List;
import java.util.Map;

import com.edumy.authoring.dto.response.InvitationResponse;
import com.edumy.authoring.entity.InvitationStatus;
import com.edumy.authoring.mapper.CourseMapper;
import com.edumy.authoring.repository.CourseCoInstructorRepository;
import com.edumy.authoring.repository.CourseCoInstructorRepository.InvitationView;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.identity.api.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Co-instructor invitations received by an instructor (UC-I14/I15). */
@Service
@Transactional(readOnly = true)
public class InvitationQueryService {

    private final CourseCoInstructorRepository coInstructors;
    private final UserQueryApi users;
    private final CourseMapper mapper;

    public InvitationQueryService(CourseCoInstructorRepository coInstructors, UserQueryApi users,
                                  CourseMapper mapper) {
        this.coInstructors = coInstructors;
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * UC-I14 precondition (an invitation still pending, sent to the caller): the {@code pending} invitations of
     * {@code instructorId}, newest first, with the course and its main instructor. Statements: invitations + names
     * (the names query is skipped when there is no invitation).
     */
    public List<InvitationResponse> pendingOf(Long instructorId) {
        List<InvitationView> pending = coInstructors.findInvitationsOfInstructor(instructorId,
                InvitationStatus.PENDING);
        Map<Long, UserSummary> names = users.findSummaries(
                pending.stream().map(InvitationView::getMainInstructorId).toList());
        return pending.stream()
                .map(invitation -> mapper.toInvitation(invitation, names.get(invitation.getMainInstructorId())))
                .toList();
    }
}
