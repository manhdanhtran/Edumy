package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.edumy.learning.api.CertificateIssued;
import com.edumy.learning.entity.Certificate;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MutableClock;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-S10 completion toggle with the BR-39 certificate issue, and the D-34 lesson visit, through the real filter chain
 * and MySQL: floor percent (D-33), idempotence (E2), unmark keeps the certificate (A1, BR-40), exactly one certificate
 * per enrollment also for concurrent last completions and re-toggling, snapshot of names (BR-40), free and purchase
 * enrollments alike, scoping to the caller's own enrollment (NFR-01).
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class ProgressWriteIT {

    static final long UNKNOWN_LESSON = 999_999_999L;

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    ApplicationEvents events;

    CourseFixtures fixtures;
    Session student;
    Session instructor;
    long course;
    long[] lessons;
    long enrollment;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-11-14T05:00:00Z"));
        fixtures = new CourseFixtures(jdbc);
        student = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        jdbc.sql("UPDATE users SET full_name = ? WHERE id = ?").params("Nguyễn Văn An", student.userId()).update();
        jdbc.sql("UPDATE users SET full_name = ? WHERE id = ?").params("Trần Thị Bình", instructor.userId()).update();
        course = fixtures.draftCourse(instructor.userId());
        fixtures.setStatus(course, "published", false);
        lessons = lessonsOf(course, 3);
        enrollment = fixtures.enrollment(student.userId(), course);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    long[] lessonsOf(long courseId, int count) {
        long chapter = fixtures.chapter(courseId);
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = fixtures.lesson(chapter, i + 1);
        }
        return ids;
    }

    MockHttpServletRequestBuilder complete(Session who, long lessonId, boolean completed) {
        return put("/api/v1/learn/lessons/" + lessonId + "/completion").header(HttpHeaders.AUTHORIZATION, who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"completed\": " + completed + "}");
    }

    MockHttpServletRequestBuilder visit(Session who, long lessonId) {
        return post("/api/v1/learn/lessons/" + lessonId + "/visit").header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    long certificates(long enrollmentId) {
        return jdbc.sql("SELECT COUNT(*) FROM certificates WHERE enrollment_id = ?").param(enrollmentId)
                .query(Long.class).single();
    }

    long progressRows(long enrollmentId) {
        return jdbc.sql("SELECT COUNT(*) FROM lesson_progresses WHERE enrollment_id = ?").param(enrollmentId)
                .query(Long.class).single();
    }

    // -------------------------------------------------------------------------------------------- UC-S10 toggle

    @Test
    void complete_flooredPercent_33_66_100_UCS10() throws Exception {
        mvc.perform(complete(student, lessons[0], true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessonId").value(lessons[0]))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").value(startsWith("2026-11-14T05:00:00")))
                .andExpect(jsonPath("$.completedLessons").value(1))
                .andExpect(jsonPath("$.totalLessons").value(3))
                .andExpect(jsonPath("$.progressPercent").value(33))
                .andExpect(jsonPath("$.certificateCode").value(nullValue()))
                .andExpect(jsonPath("$.certificateIssued").value(false));
        mvc.perform(complete(student, lessons[1], true))
                .andExpect(jsonPath("$.completedLessons").value(2))
                .andExpect(jsonPath("$.progressPercent").value(66))
                .andExpect(jsonPath("$.certificateCode").value(nullValue()));
        assertThat(certificates(enrollment)).isZero();

        mvc.perform(complete(student, lessons[2], true))
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.certificateIssued").value(true));
    }

    @Test
    void complete_isIdempotent_keepsFirstCompletedAt_oneRow_E2() throws Exception {
        mvc.perform(complete(student, lessons[0], true)).andExpect(status().isOk());
        Timestamp first = jdbc.sql("SELECT completed_at FROM lesson_progresses WHERE enrollment_id = ?")
                .param(enrollment).query(Timestamp.class).single();
        clock.advance(Duration.ofSeconds(30));

        mvc.perform(complete(student, lessons[0], true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(1))
                .andExpect(jsonPath("$.progressPercent").value(33));

        assertThat(progressRows(enrollment)).isOne();
        assertThat(jdbc.sql("SELECT completed_at FROM lesson_progresses WHERE enrollment_id = ?").param(enrollment)
                .query(Timestamp.class).single()).isEqualTo(first);
    }

    @Test
    void uncomplete_clearsCompletedAt_recomputes_idempotent_A1() throws Exception {
        mvc.perform(complete(student, lessons[0], true)).andExpect(status().isOk());
        mvc.perform(complete(student, lessons[1], true)).andExpect(status().isOk());

        for (int i = 0; i < 2; i++) {
            mvc.perform(complete(student, lessons[0], false))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.completed").value(false))
                    .andExpect(jsonPath("$.completedAt").value(nullValue()))
                    .andExpect(jsonPath("$.completedLessons").value(1))
                    .andExpect(jsonPath("$.progressPercent").value(33));
        }

        assertThat(jdbc.sql("SELECT COUNT(*) FROM lesson_progresses WHERE enrollment_id = ? AND is_completed = FALSE "
                + "AND completed_at IS NULL").param(enrollment).query(Long.class).single()).isOne();
    }

    @Test
    void uncomplete_neverStartedLesson_200_noRowCreated() throws Exception {
        mvc.perform(complete(student, lessons[2], false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completedLessons").value(0))
                .andExpect(jsonPath("$.progressPercent").value(0));

        assertThat(progressRows(enrollment)).isZero();
    }

    @Test
    void completion_progressShownByTheReads_UCS11() throws Exception {
        mvc.perform(complete(student, lessons[0], true)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.progress.completedLessons").value(1))
                .andExpect(jsonPath("$.progress.percent").value(33))
                .andExpect(jsonPath("$.chapters[0].lessons[0].completed").value(true))
                .andExpect(jsonPath("$.chapters[0].lessons[1].completed").value(false));
        mvc.perform(get("/api/v1/me/enrollments").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content[0].progressPercent").value(33));
    }

    @Test
    void completion_missingFlag_400_withoutToken_401() throws Exception {
        mvc.perform(put("/api/v1/learn/lessons/" + lessons[0] + "/completion")
                        .header(HttpHeaders.AUTHORIZATION, student.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", contains("completed")));
        expectProblem(mvc.perform(put("/api/v1/learn/lessons/" + lessons[0] + "/completion")
                .contentType(MediaType.APPLICATION_JSON).content("{\"completed\": true}")), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(post("/api/v1/learn/lessons/" + lessons[0] + "/visit")), 401, "UNAUTHENTICATED");
    }

    /** E1: only the caller's own enrollment counts; everything else is the same 404 as an unknown lesson. */
    @Test
    void completion_notEnrolled_otherCourse_instructor_404_identical_NFR01() throws Exception {
        Session stranger = tokens.newUserSession(RoleCode.STUDENT);
        long otherCourse = fixtures.draftCourse(instructor.userId());
        fixtures.setStatus(otherCourse, "published", false);
        long[] otherLessons = lessonsOf(otherCourse, 1);

        // not enrolled at all
        assertSameNotFound(mvc, complete(stranger, lessons[0], true), complete(stranger, UNKNOWN_LESSON, true));
        // enrolled in course A, lesson of course B (BR-24 at the door)
        assertSameNotFound(mvc, complete(student, otherLessons[0], true), complete(student, UNKNOWN_LESSON, true));
        // the course's own instructor has no progress (D-32)
        assertSameNotFound(mvc, complete(instructor, lessons[0], true), complete(instructor, UNKNOWN_LESSON, true));

        assertThat(progressRows(enrollment)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lesson_progresses").query(Long.class).single()).isZero();
    }

    @Test
    void completion_hiddenCourse_stillWorks_BR31() throws Exception {
        fixtures.setStatus(course, "hidden", false);

        mvc.perform(complete(student, lessons[0], true)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------- BR-39 / BR-40 issue

    @Test
    void certificate_issuedExactlyOnceAt100_snapshotCodeEventAndReads_BR39_BR40() throws Exception {
        mvc.perform(complete(student, lessons[0], true)).andExpect(status().isOk());
        mvc.perform(complete(student, lessons[1], true)).andExpect(status().isOk());
        assertThat(certificates(enrollment)).isZero();
        clock.advance(Duration.ofSeconds(20));
        Instant issuedAt = clock.instant();

        String body = mvc.perform(complete(student, lessons[2], true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.certificateIssued").value(true))
                .andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(body, "$.certificateCode");

        assertThat(code).matches(Certificate.CODE);
        assertThat(certificates(enrollment)).isOne();
        var row = jdbc.sql("""
                        SELECT certificate_code, student_name, course_title, instructor_name, issued_at
                        FROM certificates WHERE enrollment_id = ?""")
                .param(enrollment).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant().toString()}).single();
        String title = jdbc.sql("SELECT title FROM courses WHERE id = ?").param(course).query(String.class).single();
        assertThat(row[0]).isEqualTo(code);
        assertThat(row[1]).isEqualTo("Nguyễn Văn An");
        assertThat(row[2]).isEqualTo(title);
        assertThat(row[3]).isEqualTo("Trần Thị Bình");
        assertThat(Instant.parse(row[4])).isBetween(issuedAt, issuedAt.plusSeconds(5));
        long certificateId = jdbc.sql("SELECT id FROM certificates WHERE enrollment_id = ?").param(enrollment)
                .query(Long.class).single();
        assertThat(events.stream(CertificateIssued.class)).containsExactly(
                new CertificateIssued(certificateId, enrollment, student.userId(), course));

        // BR-40: the snapshot survives later renames
        jdbc.sql("UPDATE users SET full_name = 'Tên mới' WHERE id IN (?, ?)")
                .params(student.userId(), instructor.userId()).update();
        mvc.perform(get("/api/v1/me/certificates").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content[0].certificateCode").value(code))
                .andExpect(jsonPath("$.content[0].studentName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.content[0].instructorName").value("Trần Thị Bình"))
                .andExpect(jsonPath("$.content[0].enrollmentId").value(enrollment));
        mvc.perform(get("/api/v1/catalog/certificates/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateCode").value(code))
                .andExpect(jsonPath("$.studentName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.courseTitle").value(title))
                .andExpect(jsonPath("$.instructorName").value("Trần Thị Bình"));
        mvc.perform(get("/api/v1/me/enrollments").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content[0].certificateCode").value(code));
    }

    @Test
    void certificate_completeUncompleteComplete_stillOneCertificate_sameCode_A1() throws Exception {
        for (long lesson : lessons) {
            mvc.perform(complete(student, lesson, true)).andExpect(status().isOk());
        }
        String code = jdbc.sql("SELECT certificate_code FROM certificates WHERE enrollment_id = ?").param(enrollment)
                .query(String.class).single();

        // unmarking keeps the certificate and still reports it (BR-40)
        mvc.perform(complete(student, lessons[2], false))
                .andExpect(jsonPath("$.progressPercent").value(66))
                .andExpect(jsonPath("$.certificateCode").value(code))
                .andExpect(jsonPath("$.certificateIssued").value(false));
        assertThat(certificates(enrollment)).isOne();

        mvc.perform(complete(student, lessons[2], true))
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.certificateCode").value(code))
                .andExpect(jsonPath("$.certificateIssued").value(false));
        // a repeated completion at 100 % changes nothing either
        mvc.perform(complete(student, lessons[2], true))
                .andExpect(jsonPath("$.certificateCode").value(code))
                .andExpect(jsonPath("$.certificateIssued").value(false));

        assertThat(certificates(enrollment)).isOne();
        assertThat(events.stream(CertificateIssued.class)).hasSize(1);
    }

    @Test
    void certificate_purchaseEnrollment_issued_D45() throws Exception {
        Session buyer = tokens.newUserSession(RoleCode.STUDENT);
        long paidEnrollment = fixtures.purchaseEnrollment(buyer.userId(), course);

        for (long lesson : lessons) {
            mvc.perform(complete(buyer, lesson, true)).andExpect(status().isOk());
        }

        assertThat(certificates(paidEnrollment)).isOne();
        assertThat(certificates(enrollment)).isZero();
    }

    @Test
    void certificate_concurrentLastTwoCompletions_exactlyOne_BR39() throws Exception {
        for (int round = 0; round < 4; round++) {
            Session s = tokens.newUserSession(RoleCode.STUDENT);
            long e = fixtures.enrollment(s.userId(), course);
            mvc.perform(complete(s, lessons[0], true)).andExpect(status().isOk());

            List<String> bodies = parallel(
                    () -> body(complete(s, lessons[1], true)),
                    () -> body(complete(s, lessons[2], true)));

            assertThat(certificates(e)).as("round " + round).isOne();
            assertThat(bodies).as("round " + round).allMatch(b -> b.contains("\"progressPercent\":"));
            assertThat(bodies.stream().filter(b -> b.contains("\"certificateIssued\":true")).count())
                    .as("round " + round).isOne();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM lesson_progresses WHERE enrollment_id = ? AND is_completed")
                    .param(e).query(Long.class).single()).isEqualTo(3);
        }
    }

    @Test
    void certificate_concurrentCompleteOfTheSameLessonTwice_oneRow() throws Exception {
        Session s = tokens.newUserSession(RoleCode.STUDENT);
        long e = fixtures.enrollment(s.userId(), course);

        List<String> bodies = parallel(() -> body(complete(s, lessons[0], true)),
                () -> body(complete(s, lessons[0], true)), () -> body(complete(s, lessons[0], true)));

        assertThat(bodies).hasSize(3).allMatch(b -> b.contains("\"completedLessons\":1"));
        assertThat(progressRows(e)).isOne();
    }

    // -------------------------------------------------------------------------------------------- D-34 visit

    @Test
    void visit_createsRowThenMovesLastAccessed_completionUntouched_D34() throws Exception {
        Instant first = clock.instant();
        mvc.perform(visit(student, lessons[0])).andExpect(status().isNoContent());

        assertThat(progressRows(enrollment)).isOne();
        assertThat(jdbc.sql("SELECT is_completed FROM lesson_progresses WHERE enrollment_id = ?").param(enrollment)
                .query(Boolean.class).single()).isFalse();
        assertThat(lastAccessed(lessons[0])).isBetween(first, first.plusSeconds(5));

        clock.advance(Duration.ofSeconds(40));
        mvc.perform(visit(student, lessons[1])).andExpect(status().isNoContent());
        assertThat(progressRows(enrollment)).isEqualTo(2);

        // "Học tiếp" = the most recent visit
        mvc.perform(get("/api/v1/me/enrollments").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content[0].lastAccessedLessonId").value(lessons[1]))
                .andExpect(jsonPath("$.content[0].completedLessons").value(0));

        clock.advance(Duration.ofSeconds(40));
        Instant third = clock.instant();
        mvc.perform(visit(student, lessons[0])).andExpect(status().isNoContent());
        assertThat(progressRows(enrollment)).isEqualTo(2);
        assertThat(lastAccessed(lessons[0])).isBetween(third, third.plusSeconds(5));
        mvc.perform(get("/api/v1/me/enrollments").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content[0].lastAccessedLessonId").value(lessons[0]));
    }

    @Test
    void visit_ofACompletedLesson_keepsItCompleted_D34() throws Exception {
        mvc.perform(complete(student, lessons[0], true)).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(30));
        Instant later = clock.instant();

        mvc.perform(visit(student, lessons[0])).andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT is_completed FROM lesson_progresses WHERE enrollment_id = ?").param(enrollment)
                .query(Boolean.class).single()).isTrue();
        assertThat(lastAccessed(lessons[0])).isBetween(later, later.plusSeconds(5));
        mvc.perform(get("/api/v1/me/enrollments").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content[0].completedLessons").value(1));
    }

    @Test
    void visit_notEnrolledOrUnknown_404_identical_NFR01() throws Exception {
        Session stranger = tokens.newUserSession(RoleCode.STUDENT);

        assertSameNotFound(mvc, visit(stranger, lessons[0]), visit(stranger, UNKNOWN_LESSON));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM lesson_progresses").query(Long.class).single()).isZero();
    }

    /** D-32: instructors and admins read content without progress; the visit is accepted and records nothing. */
    @Test
    void visit_courseInstructorAndAdmin_204_noProgressRecorded_D32() throws Exception {
        Session admin = tokens.newUserSession(RoleCode.STUDENT, RoleCode.ADMIN);

        mvc.perform(visit(instructor, lessons[0])).andExpect(status().isNoContent());
        mvc.perform(visit(admin, lessons[0])).andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM lesson_progresses").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM enrollments WHERE student_id IN (?, ?)")
                .params(instructor.userId(), admin.userId()).query(Long.class).single()).isZero();
    }

    // --------------------------------------------------------------------------------------------------- helpers

    Instant lastAccessed(long lessonId) {
        return jdbc.sql("SELECT last_accessed_at FROM lesson_progresses WHERE enrollment_id = ? AND lesson_id = ?")
                .params(enrollment, lessonId).query(Timestamp.class).single().toInstant();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
    }

    /** Runs the tasks at the same moment on separate threads; results in task order. */
    @SafeVarargs
    private static List<String> parallel(Callable<String>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
