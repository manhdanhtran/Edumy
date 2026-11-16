package com.edumy.authoring.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-I02 / UC-I18 {@code GET /api/v1/instructor/courses[/{id}[/review-status]]} on the dev seed (read-only).
 * Seed: instructor 2 is main of 1, 2, 3, 4, 11 (declined co of 9); instructor 3 is main of 5, 6, 7, 12 (pending_review),
 * 13 (rejected) and accepted co of 1; instructor 4 is main of 8, 9, 10 (hidden), 14 (draft, no chapter) and has a
 * pending invitation to 5; user 5 (instructor role revoked) was removed from 3.
 */
@MySqlSeedSpringBootTest
class InstructorCourseControllerIT {

    static final String COURSES = "/api/v1/instructor/courses";
    static final long MISSING = Long.MAX_VALUE;

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private MockHttpServletRequestBuilder as(long userId, String path) {
        return get(COURSES + path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId));
    }

    private ResultActions perform(long userId, String path) throws Exception {
        return mvc.perform(as(userId, path));
    }

    // ------------------------------------------------------------------------------------------- list (UC-I02)

    @Test
    void list_mainInstructor_ownCoursesNewestFirst_anyStatus_UCI02() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.size").value(20))
                // createdAt desc; the declined invitation to course 9 does not count
                .andExpect(jsonPath("$.content[*].id", contains(11, 4, 3, 2, 1)))
                .andExpect(jsonPath("$.content[*].relation", contains("main", "main", "main", "main", "main")))
                .andExpect(jsonPath("$.content[0].status").value("draft"))
                .andExpect(jsonPath("$.content[0].price").value(999000))
                .andExpect(jsonPath("$.content[0].isFree").value(false))
                .andExpect(jsonPath("$.content[0].chapterCount").value(1))
                .andExpect(jsonPath("$.content[0].lessonCount").value(1))
                .andExpect(jsonPath("$.content[0].submittedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[3].isFree").value(true))
                .andExpect(jsonPath("$.content[4].title").value("Lập trình Java Spring Boot từ cơ bản đến nâng cao"))
                .andExpect(jsonPath("$.content[4].slug").value("lap-trinh-java-spring-boot-tu-co-ban-den-nang-cao"))
                .andExpect(jsonPath("$.content[4].thumbnailUrl").value("https://cdn.edumy.vn/thumbnails/course-1.jpg"))
                .andExpect(jsonPath("$.content[4].status").value("published"))
                .andExpect(jsonPath("$.content[4].categoryId").value(1))
                .andExpect(jsonPath("$.content[4].categoryName").value("Lập trình web"))
                .andExpect(jsonPath("$.content[4].chapterCount").value(3))
                .andExpect(jsonPath("$.content[4].lessonCount").value(9))
                .andExpect(jsonPath("$.content[4].reviewNote").value(nullValue()))
                .andExpect(jsonPath("$.content[4].submittedAt").value("2026-07-10T09:00:00Z"))
                .andExpect(jsonPath("$.content[4].publishedAt").value("2026-07-12T10:00:00Z"))
                .andExpect(jsonPath("$.content[4].createdAt").value("2026-06-10T09:00:00Z"))
                .andExpect(jsonPath("$.content[4].updatedAt").value("2026-09-15T08:00:00Z"));
    }

    @Test
    void list_acceptedCoInstructor_seesCollaborationAsCo_rejectionNote_UCI02() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_2, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.content[*].id", contains(12, 13, 7, 6, 5, 1)))
                .andExpect(jsonPath("$.content[*].relation", contains("main", "main", "main", "main", "main", "co")))
                .andExpect(jsonPath("$.content[0].status").value("pending_review"))
                .andExpect(jsonPath("$.content[0].chapterCount").value(2))
                .andExpect(jsonPath("$.content[0].lessonCount").value(4))
                .andExpect(jsonPath("$.content[1].status").value("rejected"))
                .andExpect(jsonPath("$.content[1].reviewNote").value(
                        "Video bài \"Giao diện và công cụ\" bị mất tiếng và mô tả khóa học còn sơ sài. "
                                + "Vui lòng bổ sung rồi gửi duyệt lại."));
    }

    @Test
    void list_pendingInvitationDoesNotCount_hiddenAndEmptyDraft_UCI02() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_3, "")
                .andExpect(status().isOk())
                // not course 5 (invitation still pending)
                .andExpect(jsonPath("$.content[*].id", contains(14, 10, 9, 8)))
                .andExpect(jsonPath("$.content[0].chapterCount").value(0))
                .andExpect(jsonPath("$.content[0].lessonCount").value(0))
                .andExpect(jsonPath("$.content[0].categoryName").value("Nhiếp ảnh"))
                .andExpect(jsonPath("$.content[1].status").value("hidden"))
                .andExpect(jsonPath("$.content[1].reviewNote").isNotEmpty());
    }

    @Test
    void list_pagination_sortIgnored() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_2, "?page=1&size=2")
                .andExpect(jsonPath("$.content[*].id", contains(7, 6)))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.page.totalPages").value(3));
        perform(SeedUserTokens.INSTRUCTOR_2, "?sort=title,asc&size=1").andExpect(jsonPath("$.content[0].id").value(12));
        perform(SeedUserTokens.INSTRUCTOR_2, "?page=9").andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void list_statementCount_independentOfPageSize_noNPlusOne() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_2, "").andExpect(status().isOk()); // warm-up: user-status, role caches

        sql.reset();
        perform(SeedUserTokens.INSTRUCTOR_2, "?size=2").andExpect(jsonPath("$.content", hasSize(2)));
        int small = sql.count();
        String smallStatements = sql.statements().toString();

        sql.reset();
        perform(SeedUserTokens.INSTRUCTOR_2, "?size=3").andExpect(jsonPath("$.content", hasSize(3)));
        int large = sql.count();

        // page + count + chapter/lesson counts of the page + enrolled students of the page (P13, EnrollmentCountPort)
        assertThat(small).as(smallStatements).isEqualTo(4);
        assertThat(large).as(sql.statements().toString()).isEqualTo(small);
    }

    // ------------------------------------------------------------------------------------------ detail (UC-I02)

    @Test
    void detail_mainInstructor_fullCourseWithContentAndCoInstructors_UCI02() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR, "/1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Lập trình Java Spring Boot từ cơ bản đến nâng cao"))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.price").value(799000))
                .andExpect(jsonPath("$.isFree").value(false))
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.relation").value("main"))
                .andExpect(jsonPath("$.categoryId").value(1))
                .andExpect(jsonPath("$.categoryName").value("Lập trình web"))
                .andExpect(jsonPath("$.categoryActive").value(true))
                .andExpect(jsonPath("$.mainInstructor.id").value(2))
                .andExpect(jsonPath("$.mainInstructor.fullName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.coInstructors", hasSize(1)))
                .andExpect(jsonPath("$.coInstructors[0].id").value(3))
                .andExpect(jsonPath("$.coInstructors[0].fullName").value("Lê Thị Mai"))
                .andExpect(jsonPath("$.coInstructors[0].invitationStatus").value("accepted"))
                .andExpect(jsonPath("$.coInstructors[0].invitedAt").value("2026-07-20T09:00:00Z"))
                .andExpect(jsonPath("$.coInstructors[0].respondedAt").value("2026-07-21T08:00:00Z"))
                .andExpect(jsonPath("$.coInstructors[0].email").doesNotExist())
                .andExpect(jsonPath("$.reviewedAt").value("2026-07-12T10:00:00Z"))
                .andExpect(jsonPath("$.publishedAt").value("2026-07-12T10:00:00Z"))
                .andExpect(jsonPath("$.chapters[*].id", contains(1, 2, 3)))
                .andExpect(jsonPath("$.chapters[*].sortOrder", contains(1, 2, 3)))
                .andExpect(jsonPath("$.chapters[0].title").value("Giới thiệu và cài đặt môi trường"))
                .andExpect(jsonPath("$.chapters[0].lessons[*].id", contains(1, 2, 3)))
                .andExpect(jsonPath("$.chapters[2].lessons[*].id", contains(7, 8, 9)))
                // the instructor edits the content: it is in the response
                .andExpect(jsonPath("$.chapters[0].lessons[0].contentType").value("video"))
                .andExpect(jsonPath("$.chapters[0].lessons[0].videoUrl").value("https://cdn.edumy.vn/videos/c1/l1.mp4"))
                .andExpect(jsonPath("$.chapters[0].lessons[0].articleContent").value(nullValue()))
                .andExpect(jsonPath("$.chapters[0].lessons[0].durationSeconds").value(312))
                .andExpect(jsonPath("$.chapters[0].lessons[0].isPreview").value(true))
                .andExpect(jsonPath("$.chapters[0].lessons[0].sortOrder").value(1))
                .andExpect(jsonPath("$.chapters[0].lessons[2].contentType").value("article"))
                .andExpect(jsonPath("$.chapters[0].lessons[2].videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.chapters[0].lessons[2].articleContent").value(
                        "Danh sách tài liệu: Spring Boot Reference, Baeldung, mã nguồn mẫu trên kho Git của khóa học."))
                .andExpect(jsonPath("$.chapters[0].lessons[2].isPreview").value(false));
    }

    @Test
    void detail_acceptedCoInstructor_relationCo_sameContent_UCI02() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_2, "/1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relation").value("co"))
                .andExpect(jsonPath("$.mainInstructor.id").value(2))
                .andExpect(jsonPath("$.chapters", hasSize(3)))
                .andExpect(jsonPath("$.coInstructors[*].id", contains(3)));
    }

    @Test
    void detail_listsEveryInvitationStatus_removedToo() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR, "/3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coInstructors[0].id").value(5))
                .andExpect(jsonPath("$.coInstructors[0].fullName").value("Võ Thanh Tâm"))
                .andExpect(jsonPath("$.coInstructors[0].invitationStatus").value("removed"));
        perform(SeedUserTokens.INSTRUCTOR_2, "/5")
                .andExpect(jsonPath("$.coInstructors[0].id").value(4))
                .andExpect(jsonPath("$.coInstructors[0].invitationStatus").value("pending"))
                .andExpect(jsonPath("$.coInstructors[0].respondedAt").value(nullValue()));
    }

    @Test
    void detail_pendingReviewCourse_readable_BR33() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_2, "/12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-28T09:00:00Z"))
                .andExpect(jsonPath("$.chapters[*].id", contains(23, 24)))
                .andExpect(jsonPath("$.chapters[0].lessons[1].articleContent").isNotEmpty());
    }

    @Test
    void detail_draftWithoutChapters_inactiveCategory() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_3, "/14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.isFree").value(true))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.categoryId").value(8))
                .andExpect(jsonPath("$.categoryActive").value(false))
                .andExpect(jsonPath("$.chapters", empty()))
                .andExpect(jsonPath("$.coInstructors", empty()));
    }

    @Test
    void detail_statementCount_constant_noNPlusOne() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR, "/1").andExpect(status().isOk()); // warm-up

        sql.reset();
        perform(SeedUserTokens.INSTRUCTOR, "/1").andExpect(jsonPath("$.chapters[2].lessons", hasSize(3)));
        int nineLessons = sql.count();
        String statements = sql.statements().toString();

        sql.reset();
        perform(SeedUserTokens.INSTRUCTOR, "/11").andExpect(jsonPath("$.chapters[0].lessons", hasSize(1)));
        int oneLesson = sql.count();

        // access check + course/category + chapters + lessons + co-instructors + user names
        assertThat(nineLessons).as(statements).isEqualTo(6);
        assertThat(oneLesson).as(sql.statements().toString()).isEqualTo(nineLessons);
    }

    // ------------------------------------------------------------------------------------ review status (UC-I18)

    @Test
    void reviewStatus_rejected_submittedReviewedAndReason_UCI18() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_2, "/13/review-status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(13))
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.submittedAt").value("2026-09-01T09:00:00Z"))
                .andExpect(jsonPath("$.reviewedAt").value("2026-09-03T10:00:00Z"))
                .andExpect(jsonPath("$.reviewNote").value(
                        "Video bài \"Giao diện và công cụ\" bị mất tiếng và mô tả khóa học còn sơ sài. "
                                + "Vui lòng bổ sung rồi gửi duyệt lại."))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist());
    }

    @Test
    void reviewStatus_hiddenPendingDraft_andCoInstructor_UCI18() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_3, "/10/review-status")
                .andExpect(jsonPath("$.status").value("hidden"))
                .andExpect(jsonPath("$.reviewedAt").value("2026-10-15T09:00:00Z"))
                .andExpect(jsonPath("$.reviewNote").value(
                        "Tạm ẩn khóa học: chương 2 bị báo cáo sử dụng tài liệu chưa ghi nguồn. "
                                + "Vui lòng bổ sung trích dẫn."));
        perform(SeedUserTokens.INSTRUCTOR_2, "/12/review-status")
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-28T09:00:00Z"))
                .andExpect(jsonPath("$.reviewedAt").value(nullValue()))
                .andExpect(jsonPath("$.reviewNote").value(nullValue()));
        perform(SeedUserTokens.INSTRUCTOR, "/11/review-status")
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.submittedAt").value(nullValue()));
        // accepted co-instructor is a course instructor ("GV của khóa")
        perform(SeedUserTokens.INSTRUCTOR_2, "/1/review-status")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    // -------------------------------------------------------------------------------------- IDOR / 404 (NFR-01)

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "/review-status"})
    void otherInstructorsCourse_sameNotFoundAsMissing_NFR01(String suffix) throws Exception {
        // instructor 4 asks for instructor 2's course 1
        assertSameNotFound(mvc, as(SeedUserTokens.INSTRUCTOR_3, "/1" + suffix),
                as(SeedUserTokens.INSTRUCTOR_3, "/" + MISSING + suffix));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "/review-status"})
    void pendingOrDeclinedInvitation_notACourseInstructor_404(String suffix) throws Exception {
        // pending: instructor 4 invited to course 5
        assertSameNotFound(mvc, as(SeedUserTokens.INSTRUCTOR_3, "/5" + suffix),
                as(SeedUserTokens.INSTRUCTOR_3, "/" + MISSING + suffix));
        // declined: instructor 2 invited to course 9
        assertSameNotFound(mvc, as(SeedUserTokens.INSTRUCTOR, "/9" + suffix),
                as(SeedUserTokens.INSTRUCTOR, "/" + MISSING + suffix));
    }

    @Test
    void list_doesNotLeakOtherInstructorsCourses() throws Exception {
        perform(SeedUserTokens.INSTRUCTOR_3, "?size=100").andExpect(jsonPath("$.content[*].id", not(hasItem(1))))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(5))));
    }

    // ------------------------------------------------------------------------------------------- 401 / 403

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "/1", "/1/review-status"})
    void withoutToken_401(String path) throws Exception {
        expectProblem(mvc.perform(get(COURSES + path)), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "/1", "/1/review-status", "/3", "/" + MISSING})
    void revokedInstructor_forgedClaim_403_BR26(String path) throws Exception {
        // user 5's instructor role was revoked (D-04, D-07); the JWT still claiming it does not help, even on
        // course 3 where it was a co-instructor (removed) — the role is checked first, so ids cannot be probed
        expectProblem(mvc.perform(get(COURSES + path).header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(SeedUserTokens.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR))),
                403, "ACCESS_DENIED");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "/1", "/1/review-status"})
    void studentOrAdmin_403(String path) throws Exception {
        expectProblem(perform(SeedUserTokens.STUDENT, path), 403, "ACCESS_DENIED");
        expectProblem(perform(SeedUserTokens.ADMIN, path), 403, "ACCESS_DENIED");
    }
}
