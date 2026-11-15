package com.edumy.learning.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.edumy.learning.api.CertificateIssued;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
 * P10 Q-c / D-48 / UC-S20 step 1 / UC-S11 (ADR-009): an enrollment that reaches 100 % because a lesson was deleted gets
 * its certificate when the student opens "Chứng chỉ của tôi" or the course progress — exactly once, also under two
 * concurrent reads, and only for the calling student's own enrollments.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class CertificateCatchUpIT {

    static final String API = "/api/v1";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ApplicationEvents events;

    CourseFixtures fixtures;
    Session student;
    Session instructor;
    long course;
    long enrollment;
    long done;
    long removed;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new CourseFixtures(jdbc);
        student = tokens.newUserSession(RoleCode.STUDENT);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.userId());
        long chapter = fixtures.chapter(course);
        done = fixtures.lesson(chapter, 1);
        removed = fixtures.lesson(chapter, 2);
        fixtures.setStatus(course, "published", false);
        enrollment = fixtures.enrollment(student.userId(), course);
        complete(student, done); // 50 %: no certificate
        events.clear();
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    @Test
    void myCertificates_100PercentAfterLessonDeleted_issuedOnce_snapshot_D48_UCS20step1() throws Exception {
        deleteLesson(removed); // now 1 of 1 lesson done = 100 %, still no certificate
        assertThat(certificateCount()).isZero();

        mvc.perform(get(API + "/me/certificates").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].courseId").value(course))
                .andExpect(jsonPath("$.content[0].certificateCode").value(org.hamcrest.Matchers.matchesPattern(
                        "EDU-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")));
        assertThat(certificateCount()).isEqualTo(1);
        assertThat(events.stream(CertificateIssued.class)).hasSize(1);

        // idempotent: later reads (list and progress) issue nothing more
        mvc.perform(get(API + "/me/certificates").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get(API + "/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk());
        assertThat(certificateCount()).isEqualTo(1);
        assertThat(events.stream(CertificateIssued.class)).hasSize(1);
    }

    @Test
    void progressRead_100PercentAfterLessonDeleted_issuedOnce_D48_UCS11() throws Exception {
        deleteLesson(removed);

        mvc.perform(get(API + "/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.percent").value(100));
        assertThat(certificateCount()).isEqualTo(1);

        mvc.perform(get(API + "/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(status().isOk());
        assertThat(certificateCount()).isEqualTo(1);
        assertThat(events.stream(CertificateIssued.class)).hasSize(1);
    }

    @Test
    void below100Percent_orInstructorOrAdminReading_nothingIssued() throws Exception {
        mvc.perform(get(API + "/me/certificates").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get(API + "/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.progress.percent").value(50));
        deleteLesson(removed);
        // the course instructor and an admin read the outline (D-32): they have no enrollment, nothing to issue
        Session admin = tokens.newUserSession(RoleCode.ADMIN);
        mvc.perform(get(API + "/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, instructor.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get(API + "/learn/courses/" + course).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk());

        assertThat(certificateCount()).isZero();
    }

    /** Only the caller's own enrollments are scanned: another student at 100 % keeps waiting for their own read. */
    @Test
    void scansOnlyTheCallersEnrollments() throws Exception {
        Session other = tokens.newUserSession(RoleCode.STUDENT);
        long otherEnrollment = fixtures.enrollment(other.userId(), course);
        complete(other, done);
        deleteLesson(removed); // both at 100 %

        mvc.perform(get(API + "/me/certificates").header(HttpHeaders.AUTHORIZATION, student.bearer()))
                .andExpect(jsonPath("$.content", hasSize(1)));

        assertThat(certificatesOf(enrollment)).isEqualTo(1);
        assertThat(certificatesOf(otherEnrollment)).isZero();
    }

    /** Two concurrent reads: the enrollment row lock lets one issue, the other sees it — one certificate, two 200s. */
    @RepeatedTest(3)
    void twoConcurrentReads_exactlyOneCertificate_both200() throws Exception {
        deleteLesson(removed);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String path : List.of("/me/certificates", "/learn/courses/" + course)) {
                results.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(get(API + path).header(HttpHeaders.AUTHORIZATION, student.bearer()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(certificateCount()).isEqualTo(1);
    }

    private void complete(Session who, long lessonId) throws Exception {
        mvc.perform(put(API + "/learn/lessons/" + lessonId + "/completion")
                        .header(HttpHeaders.AUTHORIZATION, who.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\": true}"))
                .andExpect(status().isOk());
    }

    /** UC-I11 through the API (the course keeps one lesson, D-14): its progress rows go by ON DELETE CASCADE. */
    private void deleteLesson(long lessonId) throws Exception {
        MockHttpServletRequestBuilder delete = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(API + "/instructor/lessons/" + lessonId).header(HttpHeaders.AUTHORIZATION, instructor.bearer());
        mvc.perform(delete).andExpect(status().isNoContent());
    }

    private long certificateCount() {
        return certificatesOf(enrollment);
    }

    private long certificatesOf(long enrollmentId) {
        return jdbc.sql("SELECT COUNT(*) FROM certificates WHERE enrollment_id = ?").param(enrollmentId)
                .query(Long.class).single();
    }
}
