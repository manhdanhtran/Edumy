package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.support.MySqlSeedSpringBootTest;
import com.edumy.support.SeedUserTokens;
import com.edumy.support.SqlStatementCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-S08 / UC-S09 / UC-S11 / UC-S20 on the dev seed (read-only). Seed: student 6 = enrollments 2 (course 3, 0 of 5),
 * 1 (course 1, 4 of 9, last lesson 5) and 11 (course 2, 4 of 4, certificate 1); student 7 = enrollments 3 (course 5,
 * 6 of 6, certificate 2) and 12 (course 2, 1 of 4); student 10 = enrollment 6 on the hidden course 10 (1 of 4);
 * instructor 2 = main instructor of courses 1-4, instructor 3 = accepted co-instructor of course 1, course 12 is
 * pending_review, admin = user 1.
 */
@MySqlSeedSpringBootTest
class LearningReadsIT {

    static final long STUDENT_A = 6;
    static final long STUDENT_B = 7;

    @Autowired
    MockMvc mvc;
    @Autowired
    SeedUserTokens tokens;
    @Autowired
    SqlStatementCounter sql;

    private MockHttpServletRequestBuilder req(long userId, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, tokens.bearer(userId));
    }

    private ResultActions as(long userId, String path) throws Exception {
        return mvc.perform(req(userId, path));
    }

    // ---- UC-S08 my enrollments

    @Test
    void myEnrollments_derivedProgressLastLessonAndCertificate_UCS08() throws Exception {
        as(STUDENT_A, "/api/v1/me/enrollments")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].id", contains(2, 1, 11)))
                // enrollment 2: no progress at all
                .andExpect(jsonPath("$.content[0].course.id").value(3))
                .andExpect(jsonPath("$.content[0].course.title")
                        .value("ReactJS thực chiến: Xây dựng website thương mại điện tử"))
                .andExpect(jsonPath("$.content[0].course.thumbnailUrl")
                        .value("https://cdn.edumy.vn/thumbnails/course-3.jpg"))
                .andExpect(jsonPath("$.content[0].course.published").value(true))
                .andExpect(jsonPath("$.content[0].enrollSource").value("purchase"))
                .andExpect(jsonPath("$.content[0].completedLessons").value(0))
                .andExpect(jsonPath("$.content[0].totalLessons").value(5))
                .andExpect(jsonPath("$.content[0].progressPercent").value(0))
                .andExpect(jsonPath("$.content[0].lastAccessedLessonId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].lastAccessedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].certificateCode").value(nullValue()))
                // enrollment 1: 4 of 9 = floor(44.4) (D-33); last accessed lesson 5 (D-34)
                .andExpect(jsonPath("$.content[1].course.id").value(1))
                .andExpect(jsonPath("$.content[1].completedLessons").value(4))
                .andExpect(jsonPath("$.content[1].totalLessons").value(9))
                .andExpect(jsonPath("$.content[1].progressPercent").value(44))
                .andExpect(jsonPath("$.content[1].lastAccessedLessonId").value(5))
                .andExpect(jsonPath("$.content[1].lastAccessedAt").value("2026-10-30T21:00:00Z"))
                .andExpect(jsonPath("$.content[1].certificateCode").value(nullValue()))
                // enrollment 11: free, 100 %, certificate 1
                .andExpect(jsonPath("$.content[2].course.id").value(2))
                .andExpect(jsonPath("$.content[2].enrollSource").value("free"))
                .andExpect(jsonPath("$.content[2].progressPercent").value(100))
                .andExpect(jsonPath("$.content[2].lastAccessedLessonId").value(13))
                .andExpect(jsonPath("$.content[2].certificateCode").value("EDU-7K2M-9QXA-4F8T"))
                .andExpect(jsonPath("$.content[2].enrolledAt").value("2026-08-02T09:00:00Z"));
    }

    @Test
    void myEnrollments_onlyOwn_hiddenCourseStaysListed_BR31() throws Exception {
        as(STUDENT_B, "/api/v1/me/enrollments")
                .andExpect(jsonPath("$.content[*].id", contains(3, 12)))
                .andExpect(jsonPath("$.content[0].progressPercent").value(100))
                .andExpect(jsonPath("$.content[0].certificateCode").value("EDU-P3RD-8VNC-2HWE"))
                .andExpect(jsonPath("$.content[1].progressPercent").value(25))
                .andExpect(jsonPath("$.content[1].lastAccessedLessonId").value(11));
        as(10, "/api/v1/me/enrollments") // course 10 is hidden: still listed, flagged unpublished
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].course.id").value(10))
                .andExpect(jsonPath("$.content[0].course.published").value(false))
                .andExpect(jsonPath("$.content[0].progressPercent").value(25))
                .andExpect(jsonPath("$.content[0].lastAccessedLessonId").value(46));
        as(4, "/api/v1/me/enrollments") // an instructor who bought course 1 (D-12): 1 of 9 = 11 %
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].progressPercent").value(11));
        as(2, "/api/v1/me/enrollments").andExpect(status().isOk()).andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void myEnrollments_statementCount_constantAcrossPageSizes() throws Exception {
        as(STUDENT_A, "/api/v1/me/enrollments").andExpect(status().isOk()); // warm-up

        sql.reset();
        as(STUDENT_A, "/api/v1/me/enrollments?size=1").andExpect(jsonPath("$.content", hasSize(1)));
        // page + count + courses + lesson counts + certificates
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(5);

        sql.reset();
        as(STUDENT_A, "/api/v1/me/enrollments?size=3").andExpect(jsonPath("$.content", hasSize(3)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(5);
    }

    @Test
    void myEnrollments_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/me/enrollments")), 401, "UNAUTHENTICATED");
    }

    // ---- UC-S11 outline

    @Test
    void outline_student_chaptersLessonsAndProgress_UCS11() throws Exception {
        as(STUDENT_A, "/api/v1/learn/courses/1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(1))
                .andExpect(jsonPath("$.title").value("Lập trình Java Spring Boot từ cơ bản đến nâng cao"))
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.progress.completedLessons").value(4))
                .andExpect(jsonPath("$.progress.totalLessons").value(9))
                .andExpect(jsonPath("$.progress.percent").value(44))
                .andExpect(jsonPath("$.chapters", hasSize(3)))
                .andExpect(jsonPath("$.chapters[0].title").value("Giới thiệu và cài đặt môi trường"))
                .andExpect(jsonPath("$.chapters[0].lessons[*].id", contains(1, 2, 3)))
                .andExpect(jsonPath("$.chapters[0].lessons[0].title").value("Giới thiệu khóa học"))
                .andExpect(jsonPath("$.chapters[0].lessons[0].contentType").value("video"))
                .andExpect(jsonPath("$.chapters[0].lessons[0].durationSeconds").value(312))
                .andExpect(jsonPath("$.chapters[0].lessons[0].preview").value(true))
                .andExpect(jsonPath("$.chapters[0].lessons[0].completed").value(true))
                .andExpect(jsonPath("$.chapters[0].lessons[2].contentType").value("article"))
                .andExpect(jsonPath("$.chapters[0].lessons[2].completed").value(true))
                .andExpect(jsonPath("$.chapters[1].lessons[*].id", contains(4, 5, 6)))
                .andExpect(jsonPath("$.chapters[1].lessons[1].completed").value(false)) // visited, not completed
                .andExpect(jsonPath("$.chapters[1].lessons[2].completed").value(false)) // never visited
                .andExpect(jsonPath("$.chapters[2].lessons[*].id", contains(7, 8, 9)))
                .andExpect(jsonPath("$.chapters[2].lessons[2].completed").value(false))
                // the lesson content itself is not part of the outline
                .andExpect(jsonPath("$.chapters[0].lessons[0].videoUrl").doesNotExist());
    }

    @Test
    void outline_progressIsFloorOfCompletedOverTotal_D33() throws Exception {
        as(STUDENT_A, "/api/v1/learn/courses/2").andExpect(jsonPath("$.progress.percent").value(100));
        as(STUDENT_B, "/api/v1/learn/courses/2")
                .andExpect(jsonPath("$.progress.completedLessons").value(1))
                .andExpect(jsonPath("$.progress.totalLessons").value(4))
                .andExpect(jsonPath("$.progress.percent").value(25));
        as(STUDENT_A, "/api/v1/learn/courses/3")
                .andExpect(jsonPath("$.progress.completedLessons").value(0))
                .andExpect(jsonPath("$.progress.percent").value(0));
        as(8, "/api/v1/learn/courses/6") // progress row exists, nothing completed
                .andExpect(jsonPath("$.progress.percent").value(0));
    }

    @Test
    void outline_hiddenCourse_stillOpenToItsStudent_BR31() throws Exception {
        as(10, "/api/v1/learn/courses/10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.percent").value(25))
                .andExpect(jsonPath("$.chapters", hasSize(2)));
    }

    @Test
    void outline_courseInstructorAndCoInstructor_noProgress_D32() throws Exception {
        for (long instructor : new long[] {2, 3}) { // main instructor, accepted co-instructor
            as(instructor, "/api/v1/learn/courses/1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enrolled").value(false))
                    .andExpect(jsonPath("$.progress").value(nullValue()))
                    .andExpect(jsonPath("$.chapters", hasSize(3)))
                    .andExpect(jsonPath("$.chapters[0].lessons[0].completed").value(nullValue()));
        }
    }

    @Test
    void outline_adminReviewsPendingCourse_UCA10() throws Exception {
        as(SeedUserTokens.ADMIN, "/api/v1/learn/courses/12") // course 12 is pending_review
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(12))
                .andExpect(jsonPath("$.title").value("Deep Learning với PyTorch"))
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.progress").value(nullValue()))
                .andExpect(jsonPath("$.chapters", hasSize(2)))
                .andExpect(jsonPath("$.chapters[0].lessons", hasSize(2)));
        as(SeedUserTokens.ADMIN, "/api/v1/learn/lessons/49").andExpect(status().isOk());
        // the owning instructor reads it too, a stranger instructor and a student do not
        as(3, "/api/v1/learn/courses/12").andExpect(status().isOk());
        as(2, "/api/v1/learn/courses/12").andExpect(status().isNotFound());
        as(8, "/api/v1/learn/courses/12").andExpect(status().isNotFound());
    }

    @Test
    void outline_idor_notEnrolledOrNotRelated_404IdenticalToUnknown() throws Exception {
        assertSameNotFound(mvc, req(STUDENT_B, "/api/v1/learn/courses/1"),
                req(STUDENT_B, "/api/v1/learn/courses/" + Long.MAX_VALUE));
        // pending co-instructor invitation (course 5, instructor 4), removed co-instructor (course 3, instructor 5)
        assertSameNotFound(mvc, req(4, "/api/v1/learn/courses/5"),
                req(4, "/api/v1/learn/courses/" + Long.MAX_VALUE));
        assertSameNotFound(mvc, req(2, "/api/v1/learn/courses/5"), // instructor of other courses
                req(2, "/api/v1/learn/courses/" + Long.MAX_VALUE));
    }

    @Test
    void outline_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/learn/courses/1")), 401, "UNAUTHENTICATED");
    }

    @Test
    void outline_statementCount_independentOfCourseSize() throws Exception {
        as(STUDENT_A, "/api/v1/learn/courses/1").andExpect(status().isOk()); // warm-up
        // access check + course + chapters + lessons + enrollment + completed lessons
        sql.reset();
        as(STUDENT_A, "/api/v1/learn/courses/1").andExpect(jsonPath("$.chapters", hasSize(3))); // 9 lessons
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(6);

        sql.reset();
        as(STUDENT_A, "/api/v1/learn/courses/2").andExpect(jsonPath("$.chapters", hasSize(2))); // 4 lessons
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(6);
    }

    // ---- UC-S09 lesson content

    @Test
    void lesson_video_returnsContentAndCompletedFlag_UCS09() throws Exception {
        as(STUDENT_A, "/api/v1/learn/lessons/1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.courseId").value(1))
                .andExpect(jsonPath("$.chapterId").value(1))
                .andExpect(jsonPath("$.title").value("Giới thiệu khóa học"))
                .andExpect(jsonPath("$.contentType").value("video"))
                .andExpect(jsonPath("$.durationSeconds").value(312))
                .andExpect(jsonPath("$.videoUrl").value("https://cdn.edumy.vn/videos/c1/l1.mp4"))
                .andExpect(jsonPath("$.articleContent").value(nullValue()))
                .andExpect(jsonPath("$.completed").value(true));
        as(STUDENT_A, "/api/v1/learn/lessons/5").andExpect(jsonPath("$.completed").value(false)); // visited
        as(STUDENT_A, "/api/v1/learn/lessons/6").andExpect(jsonPath("$.completed").value(false)); // never visited
    }

    @Test
    void lesson_article_returnsText_UCS09() throws Exception {
        as(STUDENT_A, "/api/v1/learn/lessons/3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("article"))
                .andExpect(jsonPath("$.videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.articleContent").value(notNullValue()));
    }

    @Test
    void lesson_instructorAndAdmin_readWithoutProgress_D32() throws Exception {
        as(2, "/api/v1/learn/lessons/5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value("https://cdn.edumy.vn/videos/c1/l5.mp4"))
                .andExpect(jsonPath("$.completed").value(nullValue()));
        as(SeedUserTokens.ADMIN, "/api/v1/learn/lessons/5").andExpect(status().isOk());
    }

    @Test
    void lesson_doesNotRecordAVisit_D34() throws Exception {
        // a GET never writes: the "continue learning" lesson of enrollment 1 is still lesson 5
        as(STUDENT_A, "/api/v1/learn/lessons/9").andExpect(status().isOk());
        as(STUDENT_A, "/api/v1/me/enrollments")
                .andExpect(jsonPath("$.content[1].lastAccessedLessonId").value(5))
                .andExpect(jsonPath("$.content[1].lastAccessedAt").value("2026-10-30T21:00:00Z"));
    }

    @Test
    void lesson_notEnrolled_404IdenticalToUnknown() throws Exception {
        assertSameNotFound(mvc, req(8, "/api/v1/learn/lessons/1"),
                req(8, "/api/v1/learn/lessons/" + Long.MAX_VALUE)); // student 8 is not enrolled in course 1
        assertSameNotFound(mvc, req(STUDENT_B, "/api/v1/learn/lessons/5"),
                req(STUDENT_B, "/api/v1/learn/lessons/" + Long.MAX_VALUE));
        assertSameNotFound(mvc, req(4, "/api/v1/learn/lessons/23"), // instructor 4: pending invitation on course 5
                req(4, "/api/v1/learn/lessons/" + Long.MAX_VALUE));
    }

    @Test
    void lesson_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/learn/lessons/1")), 401, "UNAUTHENTICATED");
    }

    // ---- UC-S20 certificates

    @Test
    void myCertificates_snapshotOfTheOwner_UCS20() throws Exception {
        as(STUDENT_A, "/api/v1/me/certificates")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].certificateCode").value("EDU-7K2M-9QXA-4F8T"))
                .andExpect(jsonPath("$.content[0].enrollmentId").value(11))
                .andExpect(jsonPath("$.content[0].courseId").value(2))
                .andExpect(jsonPath("$.content[0].courseTitle").value("HTML và CSS cho người mới bắt đầu"))
                .andExpect(jsonPath("$.content[0].studentName").value("Đỗ Thị Hương"))
                .andExpect(jsonPath("$.content[0].instructorName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.content[0].issuedAt").value("2026-08-06T20:00:00Z"));
        as(STUDENT_B, "/api/v1/me/certificates")
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].certificateCode").value("EDU-P3RD-8VNC-2HWE"));
        as(8, "/api/v1/me/certificates").andExpect(status().isOk()).andExpect(jsonPath("$.content", empty()));
    }

    @Test
    void myCertificates_statementCount_oneJoinedQueryPlusCount() throws Exception {
        as(STUDENT_A, "/api/v1/me/certificates").andExpect(status().isOk()); // warm-up

        sql.reset();
        as(STUDENT_A, "/api/v1/me/certificates?size=1").andExpect(jsonPath("$.content", hasSize(1)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(2); // page + count

        sql.reset(); // a first page that is not full needs no count query
        as(STUDENT_A, "/api/v1/me/certificates?size=20").andExpect(jsonPath("$.content", hasSize(1)));
        assertThat(sql.count()).as(sql.statements().toString()).isEqualTo(1);
    }

    @Test
    void myCertificates_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(get("/api/v1/me/certificates")), 401, "UNAUTHENTICATED");
    }
}
