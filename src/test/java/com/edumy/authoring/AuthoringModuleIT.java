package com.edumy.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.edumy.authoring.api.CourseApproved;
import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseRejected;
import com.edumy.authoring.api.CourseUnhidden;
import com.edumy.authoring.service.CourseModerationService;
import com.edumy.authoring.service.CourseService;
import com.edumy.authoring.service.UpdateCourseCommand;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.MySqlModuleTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Module test of {@code authoring} (identity mocked through its {@code api}): the services of the course lifecycle and
 * the event contract of docs/ai/01 §1.10 — state change, payload of the published event, and no event when the
 * operation is refused. The HTTP side (status codes, ProblemDetail, IDOR) is covered by {@code AdminCourseReviewWriteIT}
 * / {@code InstructorCourseWriteIT}.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@MySqlModuleTest
class AuthoringModuleIT {

    @MockitoBean
    RoleQueryApi roles;
    @MockitoBean
    UserQueryApi userQuery;

    @Autowired
    CourseModerationService moderation;
    @Autowired
    CourseService courseService;
    @Autowired
    TestUsers users;
    @Autowired
    CourseFixtures fixtures;
    @Autowired
    JdbcClient jdbc;

    TestUser admin;
    TestUser instructor;

    @BeforeEach
    void setUp() {
        admin = users.create(RoleCode.ADMIN);
        instructor = users.create(RoleCode.INSTRUCTOR);
        given(roles.hasActiveRole(admin.id(), RoleCode.ADMIN)).willReturn(true);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    @Test
    void approve_pendingReview_publishesCourseApproved_UCA10(Scenario scenario) {
        long course = pendingCourse();

        scenario.stimulate(() -> moderation.approve(admin.id(), course))
                .andWaitForEventOfType(CourseApproved.class)
                .matching(event -> event.courseId().equals(course))
                .toArriveAndVerify(event -> assertThat(event.slug()).isEqualTo(slug(course)));

        assertThat(status(course)).isEqualTo("published");
        assertThat(publishedAtSet(course)).isTrue();
    }

    @Test
    void reject_pendingReview_publishesCourseRejectedWithReason_UCA11_BR08(Scenario scenario) {
        long course = pendingCourse();

        scenario.stimulate(() -> moderation.reject(admin.id(), course, "Thiếu nội dung"))
                .andWaitForEventOfType(CourseRejected.class)
                .matching(event -> event.courseId().equals(course))
                .toArriveAndVerify(event -> {
                    assertThat(event.slug()).isEqualTo(slug(course));
                    assertThat(event.reason()).isEqualTo("Thiếu nội dung");
                });

        assertThat(status(course)).isEqualTo("rejected");
    }

    @Test
    void hide_published_publishesCourseHidden_thenUnhidePublishesCourseUnhidden_UCA12_UCA16(Scenario scenario) {
        long course = publishedCourse();

        scenario.stimulate(() -> moderation.hide(admin.id(), course, "Vi phạm bản quyền"))
                .andWaitForEventOfType(CourseHidden.class)
                .matching(event -> event.courseId().equals(course))
                .toArriveAndVerify(event -> {
                    assertThat(event.slug()).isEqualTo(slug(course));
                    assertThat(event.reason()).isEqualTo("Vi phạm bản quyền");
                });
        assertThat(status(course)).isEqualTo("hidden");

        scenario.stimulate(() -> moderation.unhide(admin.id(), course))
                .andWaitForEventOfType(CourseUnhidden.class)
                .matching(event -> event.courseId().equals(course))
                .toArriveAndVerify(event -> assertThat(event.slug()).isEqualTo(slug(course)));
        assertThat(status(course)).isEqualTo("published");
    }

    @Test
    void moderation_refusedOperations_changeNothingAndPublishNothing_BR26(PublishedEvents events) {
        long draft = fixtures.draftCourse(instructor.id());
        long pending = pendingCourse();
        TestUser notAdmin = users.create(RoleCode.STUDENT);
        given(roles.hasActiveRole(notAdmin.id(), RoleCode.ADMIN)).willReturn(false);

        assertCode(() -> moderation.approve(admin.id(), draft), ErrorCode.COURSE_INVALID_TRANSITION);
        assertCode(() -> moderation.hide(admin.id(), pending, "x"), ErrorCode.COURSE_INVALID_TRANSITION);
        assertCode(() -> moderation.unhide(admin.id(), pending), ErrorCode.COURSE_INVALID_TRANSITION);
        assertCode(() -> moderation.approve(admin.id(), Long.MAX_VALUE), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> moderation.approve(notAdmin.id(), pending), ErrorCode.ACCESS_DENIED);

        assertThat(status(draft)).isEqualTo("draft");
        assertThat(status(pending)).isEqualTo("pending_review");
        assertThat(events.ofType(CourseApproved.class)).isEmpty();
        assertThat(events.ofType(CourseHidden.class)).isEmpty();
        assertThat(events.ofType(CourseUnhidden.class)).isEmpty();
        assertThat(events.ofType(CourseRejected.class)).isEmpty();
    }

    @Test
    void update_publishedCourse_publishesCourseContentChanged_D14(Scenario scenario) {
        long course = publishedCourse();

        scenario.stimulate(() -> courseService.update(course, new UpdateCourseCommand("Tên mới", null, null, null, null)))
                .andWaitForEventOfType(CourseContentChanged.class)
                .matching(event -> event.courseId().equals(course))
                .toArriveAndVerify(event -> assertThat(event.slug()).isEqualTo(slug(course)));

        assertThat(jdbc.sql("SELECT title FROM courses WHERE id = ?").param(course).query(String.class).single())
                .isEqualTo("Tên mới");
        assertThat(status(course)).isEqualTo("published");
    }

    @Test
    void update_draftEmptyOrPendingReview_publishesNothing_BR33(PublishedEvents events) {
        long draft = fixtures.draftCourse(instructor.id());
        long pending = pendingCourse();

        courseService.update(draft, new UpdateCourseCommand("Bản nháp mới", null, null, null, null));
        courseService.update(draft, new UpdateCourseCommand(null, null, null, null, null));
        assertCode(() -> courseService.update(pending, new UpdateCourseCommand("x", null, null, null, null)),
                ErrorCode.COURSE_NOT_EDITABLE);

        assertThat(events.ofType(CourseContentChanged.class)).isEmpty();
        assertThat(jdbc.sql("SELECT title FROM courses WHERE id = ?").param(pending).query(String.class).single())
                .isNotEqualTo("x");
    }

    private long pendingCourse() {
        long course = fixtures.draftCourse(instructor.id());
        fixtures.markPendingReview(course);
        return course;
    }

    private long publishedCourse() {
        long course = fixtures.draftCourse(instructor.id());
        fixtures.setStatus(course, "published", true);
        return course;
    }

    private String status(long courseId) {
        return jdbc.sql("SELECT status FROM courses WHERE id = ?").param(courseId).query(String.class).single();
    }

    private String slug(long courseId) {
        return jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(courseId).query(String.class).single();
    }

    private boolean publishedAtSet(long courseId) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM courses WHERE id = ?").param(courseId)
                .query(Boolean.class).single();
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}
