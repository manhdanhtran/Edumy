package com.edumy.learning.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-S15 free enrollment through the real filter chain and MySQL: BR-31 published only, BR-09 free only, BR-32 not the
 * course's own instructor, BR-17 one enrollment per student and course (idempotent, also under concurrency), D-02
 * unverified email allowed, response = the UC-S08 row.
 */
@MySqlSpringBootTest
class EnrollmentWriteIT {

    static final long UNKNOWN_COURSE = 999_999_999L;

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

    CourseFixtures fixtures;
    Session student;
    Session instructor;
    long course;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        student = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = course(0);
        long chapter = fixtures.chapter(course);
        fixtures.lesson(chapter, 1);
        fixtures.lesson(chapter, 2);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    /** A published course of {@code instructor} at {@code price} (0 = free). */
    long course(long price) {
        long id = fixtures.draftCourse(instructor.userId());
        fixtures.setStatus(id, "published", false);
        jdbc.sql("UPDATE courses SET price = ? WHERE id = ?").params(price, id).update();
        return id;
    }

    MockHttpServletRequestBuilder enroll(Session who, long courseId) {
        return post("/api/v1/courses/" + courseId + "/enrollments").header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    long rows(Session who, long courseId) {
        return jdbc.sql("SELECT COUNT(*) FROM enrollments WHERE student_id = ? AND course_id = ?")
                .params(who.userId(), courseId).query(Long.class).single();
    }

    long track(Session who, long courseId) {
        long id = jdbc.sql("SELECT id FROM enrollments WHERE student_id = ? AND course_id = ?")
                .params(who.userId(), courseId).query(Long.class).single();
        fixtures.trackEnrollment(id);
        return id;
    }

    @Test
    void enroll_freePublished_201_freeSourceNoOrder_responseIsTheUCS08Row_UCS15() throws Exception {
        Instant now = Instant.parse("2026-11-14T05:00:00Z");
        clock.setInstant(now);

        mvc.perform(enroll(student, course))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.course.id").value(course))
                .andExpect(jsonPath("$.course.published").value(true))
                .andExpect(jsonPath("$.enrollSource").value("free"))
                .andExpect(jsonPath("$.enrolledAt").value(startsWith("2026-11-14T05:00:00")))
                .andExpect(jsonPath("$.completedLessons").value(0))
                .andExpect(jsonPath("$.totalLessons").value(2))
                .andExpect(jsonPath("$.progressPercent").value(0))
                .andExpect(jsonPath("$.lastAccessedLessonId").value(nullValue()))
                .andExpect(jsonPath("$.certificateCode").value(nullValue()));

        long id = track(student, course);
        assertThat(jdbc.sql("SELECT enroll_source FROM enrollments WHERE id = ?").param(id).query(String.class)
                .single()).isEqualTo("free");
        assertThat(jdbc.sql("SELECT order_id FROM enrollments WHERE id = ?").param(id).query(Long.class)
                .optional()).isEmpty();
        assertThat(jdbc.sql("SELECT enrolled_at FROM enrollments WHERE id = ?").param(id).query(Timestamp.class)
                .single().toInstant()).isBetween(now, now.plusSeconds(5));
        // no order, no transaction
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE student_id = ?").param(student.userId())
                .query(Long.class).single()).isZero();
        // the course now shows up in "my courses" and the learning room is open
        mvc.perform(get("/api/v1/me/enrollments").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/v1/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.enrolled").value(true));
    }

    /** UC-S15 step 4 "mở khóa đã ghi danh, không tạo thêm": 200 and the existing row, nothing written. */
    @Test
    void enroll_alreadyEnrolled_200_existingRowNothingNew_BR17() throws Exception {
        clock.setInstant(Instant.parse("2026-11-14T05:00:00Z"));
        mvc.perform(enroll(student, course)).andExpect(status().isCreated());
        long id = track(student, course);
        clock.setInstant(Instant.parse("2026-11-14T05:01:00Z"));

        mvc.perform(enroll(student, course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.enrolledAt").value(startsWith("2026-11-14T05:00:00")));

        assertThat(rows(student, course)).isOne();
    }

    @Test
    void enroll_alreadyEnrolledByAnotherRoute_200_BR17() throws Exception {
        long id = fixtures.purchaseEnrollment(student.userId(), course);

        mvc.perform(enroll(student, course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.enrollSource").value("purchase"));

        assertThat(rows(student, course)).isOne();
    }

    @Test
    void enroll_unverifiedEmail_201_D02() throws Exception {
        users.markEmailUnverified(student.userId());

        mvc.perform(enroll(student, course)).andExpect(status().isCreated());

        track(student, course);
    }

    @Test
    void enroll_paidCourse_422_nothingWritten_BR09() throws Exception {
        long paid = course(499_000);

        expectProblem(mvc.perform(enroll(student, paid)), 422, "ENROLLMENT_COURSE_NOT_FREE");

        assertThat(rows(student, paid)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "pending_review", "rejected", "hidden"})
    void enroll_notPublished_404_sameBodyAsUnknown_BR31(String state) throws Exception {
        long other = course(0);
        if (state.equals("pending_review")) {
            fixtures.markPendingReview(other);
        } else if (state.equals("draft")) {
            jdbc.sql("UPDATE courses SET status = 'draft', published_at = NULL WHERE id = ?").param(other).update();
        } else {
            fixtures.setStatus(other, state, false);
        }

        assertSameNotFound(mvc, enroll(student, other), enroll(student, UNKNOWN_COURSE));

        assertThat(rows(student, other)).isZero();
    }

    @Test
    void enroll_mainInstructor_422_evenWithRevokedRole_BR32() throws Exception {
        expectProblem(mvc.perform(enroll(instructor, course)), 422, "ENROLLMENT_OWN_COURSE");
        users.revokeRole(instructor.userId(), RoleCode.INSTRUCTOR);
        expectProblem(mvc.perform(enroll(instructor, course)), 422, "ENROLLMENT_OWN_COURSE");

        assertThat(rows(instructor, course)).isZero();
    }

    @Test
    void enroll_acceptedCoInstructor_422_butDeclinedMayEnroll_BR32() throws Exception {
        Session co = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long invitation = fixtures.coInstructor(course, co.userId(), instructor.userId(), "accepted");

        expectProblem(mvc.perform(enroll(co, course)), 422, "ENROLLMENT_OWN_COURSE");
        assertThat(rows(co, course)).isZero();

        // "co-instructor" means an accepted invitation (docs/ai/02)
        jdbc.sql("UPDATE course_co_instructors SET invitation_status = 'declined', responded_at = ? WHERE id = ?")
                .params(Timestamp.from(Instant.now()), invitation).update();
        mvc.perform(enroll(co, course)).andExpect(status().isCreated());
        track(co, course);
    }

    /** An instructor of another course enrolls like any student. */
    @Test
    void enroll_instructorOfAnotherCourse_201() throws Exception {
        Session other = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);

        mvc.perform(enroll(other, course)).andExpect(status().isCreated());

        track(other, course);
    }

    @Test
    void enroll_concurrentTripleEnroll_oneRow_oneCreated_BR17() throws Exception {
        for (int round = 0; round < 3; round++) {
            Session s = tokens.newUserSession(RoleCode.STUDENT);
            List<Integer> statuses = parallel(() -> statusOf(enroll(s, course)), () -> statusOf(enroll(s, course)),
                    () -> statusOf(enroll(s, course)));

            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 200, 200);
            assertThat(rows(s, course)).isOne();
            track(s, course);
        }
    }

    @Test
    void enroll_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post("/api/v1/courses/" + course + "/enrollments")), 401, "UNAUTHENTICATED");
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** Runs the tasks at the same moment on separate threads; results in task order. */
    @SafeVarargs
    private static List<Integer> parallel(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS)); // P10 DT-m4: never hang
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
