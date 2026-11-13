package com.edumy.authoring.mapper;

import java.util.List;

import com.edumy.authoring.dto.response.ChapterResponse;
import com.edumy.authoring.dto.response.InstructorCourseDetailResponse;
import com.edumy.authoring.dto.response.InstructorCourseSummaryResponse;
import com.edumy.authoring.dto.response.InvitationResponse;
import com.edumy.authoring.dto.response.PendingCourseResponse;
import com.edumy.authoring.dto.response.ReviewStatusResponse;
import com.edumy.authoring.entity.Course;
import com.edumy.authoring.entity.CourseCoInstructor;
import com.edumy.authoring.entity.CourseStatus;
import com.edumy.authoring.entity.InvitationStatus;
import com.edumy.authoring.repository.CourseCoInstructorRepository.InvitationView;
import com.edumy.authoring.repository.CourseRepository.InstructorCourseView;
import com.edumy.authoring.repository.CourseRepository.PendingCourseView;
import com.edumy.identity.api.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * Courses and invitations → authoring responses. User names come from {@code identity::api.UserSummary}, fetched by
 * the caller for a whole list at once; a {@code null} user leaves only the name empty (the id comes from the course).
 * {@code isFree} is derived from the price (docs/ai/05 §5.1), never stored.
 */
@Mapper(componentModel = "spring")
public interface CourseMapper {

    /** UC-I02 list row; {@code relation} {@code main}/{@code co}; counts of the course outline. */
    @Mapping(target = "isFree", expression = "java(course.getPrice() == 0)")
    InstructorCourseSummaryResponse toSummary(InstructorCourseView course, String relation, long chapterCount,
                                              long lessonCount);

    /** UC-I02 detail for an instructor of the course; {@code course.category} must be loaded. */
    @Mapping(target = "id", source = "course.id")
    @Mapping(target = "status", source = "course.status")
    @Mapping(target = "isFree", expression = "java(course.getPrice() == 0)")
    @Mapping(target = "categoryId", source = "course.category.id")
    @Mapping(target = "categoryName", source = "course.category.name")
    @Mapping(target = "categoryActive", source = "course.category.active")
    @Mapping(target = "mainInstructor", expression = "java(new InstructorCourseDetailResponse.Instructor("
            + "course.getMainInstructorId(), fullName(mainUser)))")
    InstructorCourseDetailResponse toDetail(Course course, String relation, UserSummary mainUser,
                                            List<InstructorCourseDetailResponse.CoInstructor> coInstructors,
                                            List<ChapterResponse> chapters);

    /** A co-instructor row of the course detail; {@code id} = the invited user's id. */
    @Mapping(target = "id", source = "row.instructorId")
    @Mapping(target = "fullName", source = "user.fullName")
    @Mapping(target = "invitationStatus", source = "row.invitationStatus")
    @Mapping(target = "invitedAt", source = "row.invitedAt")
    @Mapping(target = "respondedAt", source = "row.respondedAt")
    InstructorCourseDetailResponse.CoInstructor toCoInstructor(CourseCoInstructor row, UserSummary user);

    /** UC-I18. */
    @Mapping(target = "courseId", source = "id")
    ReviewStatusResponse toReviewStatus(Course course);

    /** UC-A09 queue row. */
    @Mapping(target = "id", source = "course.id")
    @Mapping(target = "isFree", expression = "java(course.getPrice() == 0)")
    @Mapping(target = "mainInstructor", expression = "java(new PendingCourseResponse.Instructor("
            + "course.getMainInstructorId(), fullName(user)))")
    PendingCourseResponse toPending(PendingCourseView course, UserSummary user);

    /** UC-I14 pending invitation; {@code user} = the main instructor. */
    @Mapping(target = "id", source = "invitation.id")
    @Mapping(target = "mainInstructor", expression = "java(new InvitationResponse.Instructor("
            + "invitation.getMainInstructorId(), fullName(user)))")
    InvitationResponse toInvitation(InvitationView invitation, UserSummary user);

    /** Name of a user from {@code identity::api}; {@code null} when the user was not found. */
    @Named("fullName")
    default String fullName(UserSummary user) {
        return user == null ? null : user.fullName();
    }

    /** Lower-case DB code. */
    default String courseStatusCode(CourseStatus status) {
        return status.code();
    }

    /** Lower-case DB code. */
    default String invitationStatusCode(InvitationStatus status) {
        return status.code();
    }
}
