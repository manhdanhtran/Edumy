package com.edumy.authoring.security;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edumy.identity.service.RoleCacheEvictor;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.AccessProbeController;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
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
 * {@code @courseAccess} / {@code @chapterAccess} / {@code @lessonAccess} end to end on MySQL through
 * {@link AccessProbeController}: {@code @PreAuthorize} → bean → BusinessException (404 / 409) or false (403) →
 * ProblemDetail (Q7, NFR-01, BR-26, BR-33, D-07).
 */
@MySqlSpringBootTest
class AuthoringAccessIT {

    static final String BASE = AccessProbeController.BASE;
    static final long MISSING = Long.MAX_VALUE;

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RoleCacheEvictor roleCache;

    CourseFixtures fixtures;
    Session ownerA;
    Session instructorB;
    long courseA;
    long chapterA;
    long lessonA;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        ownerA = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        instructorB = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        courseA = fixtures.draftCourse(ownerA.userId());
        chapterA = fixtures.chapter(courseA);
        lessonA = fixtures.lesson(chapterA);
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    private MockHttpServletRequestBuilder probe(Session session, String path, long id) {
        return get(BASE + path, id).header(HttpHeaders.AUTHORIZATION, session.bearer());
    }

    private void expectOk(Session session, String path, long id) throws Exception {
        mvc.perform(probe(session, path, id)).andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    @Test
    void mainInstructor_allChecksPass() throws Exception {
        expectOk(ownerA, "/courses/{id}/main", courseA);
        expectOk(ownerA, "/courses/{id}/instructor", courseA);
        expectOk(ownerA, "/courses/{id}/edit", courseA);
        expectOk(ownerA, "/chapters/{id}/edit", chapterA);
        expectOk(ownerA, "/lessons/{id}/edit", lessonA);
    }

    @Test
    void otherInstructor_onCourseOfA_sameBodyAsMissing_404_NFR01() throws Exception {
        for (String path : new String[] {"/courses/{id}/main", "/courses/{id}/instructor", "/courses/{id}/edit"}) {
            assertSameNotFound(mvc, probe(instructorB, path, courseA), probe(instructorB, path, MISSING));
        }
        assertSameNotFound(mvc, probe(instructorB, "/chapters/{id}/edit", chapterA),
                probe(instructorB, "/chapters/{id}/edit", MISSING));
        assertSameNotFound(mvc, probe(instructorB, "/lessons/{id}/edit", lessonA),
                probe(instructorB, "/lessons/{id}/edit", MISSING));
    }

    @Test
    void studentWithoutInstructorRole_403AccessDenied_problemWithTraceId() throws Exception {
        Session student = tokens.newUserSession(RoleCode.STUDENT);

        expectProblem(mvc.perform(probe(student, "/courses/{id}/main", courseA)), 403, "ACCESS_DENIED");
        // the same for an id that does not exist: no role → no lookup, nothing to enumerate
        expectProblem(mvc.perform(probe(student, "/courses/{id}/edit", MISSING)), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(probe(student, "/lessons/{id}/edit", lessonA)), 403, "ACCESS_DENIED");
    }

    @Test
    void instructorRoleRevokedInDatabase_staleJwt_403Immediately_D07_BR26() throws Exception {
        expectOk(ownerA, "/courses/{id}/edit", courseA); // roles now cached

        users.revokeRole(ownerA.userId(), RoleCode.INSTRUCTOR); // UC-A05 (the admin service will evict the same way)
        roleCache.evict(ownerA.userId());

        // the access token still claims "instructor"
        expectProblem(mvc.perform(probe(ownerA, "/courses/{id}/edit", courseA)), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(probe(ownerA, "/courses/{id}/main", courseA)), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(probe(ownerA, "/chapters/{id}/edit", chapterA)), 403, "ACCESS_DENIED");
    }

    @Test
    void pendingReview_instructorEdit_409CourseNotEditable_readsStillAllowed_BR33() throws Exception {
        fixtures.markPendingReview(courseA);

        expectProblem(mvc.perform(probe(ownerA, "/courses/{id}/edit", courseA)), 409, "COURSE_NOT_EDITABLE");
        expectProblem(mvc.perform(probe(ownerA, "/lessons/{id}/edit", lessonA)), 409, "COURSE_NOT_EDITABLE");
        expectOk(ownerA, "/courses/{id}/instructor", courseA);
        expectOk(ownerA, "/courses/{id}/main", courseA);
        // a stranger learns nothing about the status
        assertSameNotFound(mvc, probe(instructorB, "/courses/{id}/edit", courseA),
                probe(instructorB, "/courses/{id}/edit", MISSING));
    }

    @Test
    void acceptedCoInstructor_instructorAndEdit_butNotMain_BR04() throws Exception {
        fixtures.coInstructor(courseA, instructorB.userId(), ownerA.userId(), "accepted");

        expectOk(instructorB, "/courses/{id}/instructor", courseA);
        expectOk(instructorB, "/courses/{id}/edit", courseA);
        expectOk(instructorB, "/chapters/{id}/edit", chapterA);
        assertSameNotFound(mvc, probe(instructorB, "/courses/{id}/main", courseA),
                probe(instructorB, "/courses/{id}/main", MISSING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "declined", "removed"})
    void coInstructorNotAccepted_404(String invitationStatus) throws Exception {
        fixtures.coInstructor(courseA, instructorB.userId(), ownerA.userId(), invitationStatus);

        assertSameNotFound(mvc, probe(instructorB, "/courses/{id}/instructor", courseA),
                probe(instructorB, "/courses/{id}/instructor", MISSING));
        assertSameNotFound(mvc, probe(instructorB, "/lessons/{id}/edit", lessonA),
                probe(instructorB, "/lessons/{id}/edit", MISSING));
    }
}
