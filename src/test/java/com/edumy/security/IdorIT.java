package com.edumy.security;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static com.edumy.support.ProblemAssertions.withoutTraceAndInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NFR-01 as one systematic matrix (docs/ai/04 §4.3, docs/ai/10-idor-coverage.md): for every protected endpoint with a
 * path id that identifies a user-owned or relationship-scoped resource, <b>another user's</b> id requested by a caller
 * with no relation to it answers exactly what a <b>missing</b> id answers — 404 {@code RESOURCE_NOT_FOUND}, bodies equal
 * except {@code traceId} / {@code instance} — and nothing of the owner's data is in the body. Read and write verbs.
 * <p>One row = one endpoint ({@link Row}); every test below is parameterized over the same table:
 * <ul>
 *   <li>{@link #stranger_404_identicalToMissing_noData}: each stranger of the row (a student with no enrollment, a
 *       classmate, an instructor of another course, a pending / declined / removed co-instructor, the admin where
 *       the endpoint is "own only") → the 404 pair;</li>
 *   <li>{@link #owner_succeeds_theRowIsNotVacuous}: the owner gets its documented success
 *       (or the BR-33 409), so a 404 above cannot come from a mistyped fixture;</li>
 *   <li>{@link #noToken_401}: no token → 401 {@code UNAUTHENTICATED} for every protected row;</li>
 *   <li>{@link #noInstructorRole_403_beforeAnyLookup}: the documented 403 {@code ACCESS_DENIED} of the instructor
 *       routes, identical for an existing and a missing id (it does not depend on the id);</li>
 *   <li>{@link #adminRoute_nonAdmin_403_sameForMissing}: the {@code /admin/**} routes with an id;</li>
 *   <li>{@link #publicCatalog_unpublishedOrNonPreview_404} and the two 403 tests of Q&amp;A / review create.</li>
 * </ul>
 * Two worlds are built (users with sessions, courses, content, enrollments, order, certificate, Q&amp;A, review, cart,
 * invitations): a shared one for every read-only check, and one for the owner step, whose rows run in table order
 * and consume resources. Both are removed after the class.
 */
@MySqlSpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdorIT {

    static final String API = "/api/v1";
    static final String MISSING_ID = Long.toString(Long.MAX_VALUE);
    /** Missing values of the non-numeric path variables (same shape as a real one). */
    static final Map<String, String> MISSING = Map.of(
            "orderCode", "ORD-00000000000000000000",
            "certCode", "EDU-ZZZZ-ZZZZ-ZZZZ",
            "draftSlug", "khong-ton-tai-0",
            "pendingSlug", "khong-ton-tai-0",
            "publishedSlug", "khong-ton-tai-0");
    static final Pattern TOKEN = Pattern.compile("\\{(\\w+)}");

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;

    /** Read-only world shared by every test but the owner step (strangers never change anything: they get 404 / 403). */
    World world;
    /** The owner step consumes resources (DELETE, submit, accept), so it has a world of its own. */
    World ownerWorld;

    @AfterAll
    void cleanUp() {
        for (World w : new World[] {world, ownerWorld}) {
            if (w != null) {
                w.tearDown();
            }
        }
        users.deleteAll();
    }

    // ================================================================================================== the table

    enum Kind { OWNED, ADMIN, PUBLIC }

    /** Who calls: {@link #ANONYMOUS} sends no token; {@link #REVOKED_INSTRUCTOR} has a token still claiming instructor. */
    enum Who {
        ANONYMOUS, OWNER_INSTRUCTOR, STUDENT_A, CLASSMATE, STRANGER_STUDENT, STRANGER_INSTRUCTOR, CO_PENDING,
        CO_PENDING_2, CO_DECLINED, CO_REMOVED, CO_ACCEPTED, ADMIN, REVOKED_INSTRUCTOR
    }

    /**
     * One endpoint. {@code path} (after {@code /api/v1}) and {@code body} hold {@code {token}}s resolved from the
     * world; {@code target} is the token replaced by a missing value in the "missing" request (default: the first one).
     * {@code owner} + {@code ownerStatus} (+ {@code ownerPath} when the success needs another resource) is the
     * non-vacuity control.
     */
    record Row(Kind kind, String method, String path, String body, Set<Who> strangers, Who owner, int ownerStatus,
               String ownerPath, String target) {

        String label() {
            return method + " " + path;
        }

        boolean instructorRoute() {
            return path.startsWith("/instructor/");
        }

        Row target(String token) {
            return new Row(kind, method, path, body, strangers, owner, ownerStatus, ownerPath, token);
        }

        Row ownerPath(String p) {
            return new Row(kind, method, path, body, strangers, owner, ownerStatus, p, target);
        }

        @Override
        public String toString() {
            return label();
        }
    }

    static Set<Who> set(Who... who) {
        return Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(who)));
    }

    static Set<Who> plus(Set<Who> base, Who... more) {
        Set<Who> all = EnumSet.copyOf(base);
        all.addAll(Arrays.asList(more));
        return Collections.unmodifiableSet(all);
    }

    static Set<Who> without(Set<Who> base, Who... remove) {
        Set<Who> all = EnumSet.copyOf(base);
        all.removeAll(Arrays.asList(remove));
        return Collections.unmodifiableSet(all);
    }

    /** Every caller with a token except {@code keep}. */
    static Set<Who> everyoneBut(Who... keep) {
        Set<Who> all = EnumSet.allOf(Who.class);
        all.remove(Who.ANONYMOUS);
        all.removeAll(Arrays.asList(keep));
        return Collections.unmodifiableSet(all);
    }

    /** Instructors that have no relation to the course (an invitation that is not accepted is no relation, BR-04). */
    static final Set<Who> INSTR_OUTSIDERS = set(Who.STRANGER_INSTRUCTOR, Who.CO_PENDING, Who.CO_PENDING_2,
            Who.CO_DECLINED, Who.CO_REMOVED);
    /** Main-instructor-only endpoints: an accepted co-instructor is a stranger there too (BR-06). */
    static final Set<Who> MAIN_ONLY = plus(INSTR_OUTSIDERS, Who.CO_ACCEPTED);
    /** No enrollment, not an instructor of the course, not the admin (D-32). */
    static final Set<Who> LEARN_OUTSIDERS = plus(INSTR_OUTSIDERS, Who.STRANGER_STUDENT);
    /** Accepting / declining: everybody but the invitee (two pending invitees, the inviter, an accepted co-instructor, ...). */
    static final Set<Who> INVITATION_STRANGERS = plus(INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, Who.CO_ACCEPTED);

    static Row owned(String method, String path, String body, Set<Who> strangers, Who owner, int ownerStatus) {
        Matcher first = TOKEN.matcher(path);
        if (!first.find()) {
            throw new IllegalArgumentException("a row needs a path id: " + path);
        }
        return new Row(Kind.OWNED, method, path, body, strangers, owner, ownerStatus, null, first.group(1));
    }

    static Row admin(String method, String path, String body) {
        Matcher first = TOKEN.matcher(path);
        first.find();
        return new Row(Kind.ADMIN, method, path, body, set(Who.STRANGER_STUDENT, Who.OWNER_INSTRUCTOR, Who.STUDENT_A),
                null, 0, null, first.group(1));
    }

    static Row pub(String path) {
        Matcher first = TOKEN.matcher(path);
        first.find();
        return new Row(Kind.PUBLIC, "GET", path, null, set(Who.ANONYMOUS, Who.STRANGER_STUDENT), null, 0, null,
                first.group(1));
    }

    static final String CHAPTER_BODY = "{\"title\": \"Chương mới\"}";
    static final String LESSON_BODY = "{\"title\": \"Bài mới\", \"contentType\": \"article\", \"articleContent\": \"Nội dung\"}";

    /**
     * The order matters for {@link #owner_succeeds_theRowIsNotVacuous}: its rows run one after the other in ONE world, so
     * a row that consumes a resource (DELETE, submit, accept) comes after every row that needs it.
     */
    static final List<Row> OWNED = List.of(
            // ---- authoring: instructor routes of a course (UC-I01…I20)
            owned("GET", "/instructor/courses/{course}", null, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 200),
            owned("GET", "/instructor/courses/{course}/review-status", null, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 200),
            owned("PATCH", "/instructor/courses/{draftCourse}", "{\"title\": \"Tiêu đề mới\"}", MAIN_ONLY,
                    Who.OWNER_INSTRUCTOR, 200),
            owned("POST", "/instructor/courses/{submitCourse}/submit", null, MAIN_ONLY, Who.OWNER_INSTRUCTOR, 200),
            owned("DELETE", "/instructor/courses/{draftCourse}", null, MAIN_ONLY, Who.OWNER_INSTRUCTOR, 204),
            owned("POST", "/instructor/courses/{course}/co-instructors", "{\"email\": \"{strangerInstructorEmail}\"}",
                    MAIN_ONLY, Who.OWNER_INSTRUCTOR, 201),
            owned("DELETE", "/instructor/courses/{course}/co-instructors/{coRemovableUser}", null, MAIN_ONLY,
                    Who.OWNER_INSTRUCTOR, 204).target("course"),
            owned("POST", "/instructor/courses/{course}/leave", null, INSTR_OUTSIDERS, Who.CO_ACCEPTED, 204),
            owned("GET", "/instructor/courses/{course}/students", null, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 200),
            owned("GET", "/instructor/questions?courseId={course}", null, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 200),
            // ---- authoring: invitations belong to the invitee (UC-I14, UC-I15)
            owned("POST", "/instructor/invitations/{coPendingInv}/accept", null, without(INVITATION_STRANGERS, Who.CO_PENDING), Who.CO_PENDING,
                    204),
            owned("POST", "/instructor/invitations/{coPendingInv2}/decline", null,
                    without(INVITATION_STRANGERS, Who.CO_PENDING_2),
                    Who.CO_PENDING_2, 204),
            // ---- authoring: chapters and lessons (404 before the BR-33 409, which only a course instructor sees)
            owned("PUT", "/instructor/courses/{course}/chapters/order", "{\"ids\": [{ch1}, {ch2}]}", INSTR_OUTSIDERS,
                    Who.OWNER_INSTRUCTOR, 200).target("course"),
            owned("POST", "/instructor/courses/{course}/chapters", CHAPTER_BODY, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 201),
            owned("POST", "/instructor/courses/{pendingCourse}/chapters", CHAPTER_BODY, INSTR_OUTSIDERS,
                    Who.OWNER_INSTRUCTOR, 409),
            owned("PATCH", "/instructor/chapters/{ch1}", "{\"title\": \"Tên mới\"}", INSTR_OUTSIDERS,
                    Who.OWNER_INSTRUCTOR, 200),
            owned("PATCH", "/instructor/chapters/{pendingCh}", "{\"title\": \"Tên mới\"}", INSTR_OUTSIDERS,
                    Who.OWNER_INSTRUCTOR, 409),
            owned("PUT", "/instructor/chapters/{ch1}/lessons/order", "{\"ids\": [{l1}, {l2}]}", INSTR_OUTSIDERS,
                    Who.OWNER_INSTRUCTOR, 200).target("ch1"),
            owned("POST", "/instructor/chapters/{ch1}/lessons", LESSON_BODY, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 201),
            owned("PATCH", "/instructor/lessons/{l1}", "{\"title\": \"Tên mới\"}", INSTR_OUTSIDERS,
                    Who.OWNER_INSTRUCTOR, 200),
            owned("PATCH", "/instructor/lessons/{pendingLesson}", "{\"title\": \"Tên mới\"}", INSTR_OUTSIDERS,
                    Who.OWNER_INSTRUCTOR, 409),
            owned("DELETE", "/instructor/lessons/{l2}", null, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 204),
            owned("DELETE", "/instructor/chapters/{ch2}", null, INSTR_OUTSIDERS, Who.OWNER_INSTRUCTOR, 204),
            // ---- learning: enrollment-scoped reads and writes (UC-S09, UC-S10, UC-S15, UC-S17, UC-S18)
            owned("GET", "/learn/courses/{course}", null, LEARN_OUTSIDERS, Who.STUDENT_A, 200),
            owned("GET", "/learn/lessons/{l1}", null, LEARN_OUTSIDERS, Who.STUDENT_A, 200),
            owned("POST", "/learn/lessons/{l1}/visit", null, LEARN_OUTSIDERS, Who.STUDENT_A, 204),
            owned("PUT", "/learn/lessons/{l1}/completion", "{\"completed\": true}",
                    plus(LEARN_OUTSIDERS, Who.OWNER_INSTRUCTOR, Who.ADMIN), Who.STUDENT_A, 200),
            owned("POST", "/courses/{draftCourse}/enrollments", null, set(Who.STRANGER_STUDENT, Who.CLASSMATE, Who.ADMIN),
                    Who.STRANGER_STUDENT, 201).ownerPath("/courses/{openCourse}/enrollments"),
            owned("GET", "/courses/{course}/questions", null, LEARN_OUTSIDERS, Who.STUDENT_A, 200),
            owned("POST", "/courses/{course}/questions", "{\"title\": \"Hỏi\", \"content\": \"Nội dung hỏi\"}",
                    plus(LEARN_OUTSIDERS, Who.OWNER_INSTRUCTOR, Who.ADMIN), Who.STUDENT_A, 201),
            // ---- learning: Q&A (UC-S18, UC-S19, UC-I21); the question is deleted after its answers
            owned("GET", "/questions/{question}", null, LEARN_OUTSIDERS, Who.STUDENT_A, 200),
            owned("GET", "/questions/{hiddenQuestion}", null, plus(LEARN_OUTSIDERS, Who.CLASSMATE, Who.OWNER_INSTRUCTOR),
                    Who.STUDENT_A, 200),
            owned("PATCH", "/questions/{question}", "{\"title\": \"Mới\", \"content\": \"Nội dung mới\"}",
                    everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 200),
            owned("POST", "/questions/{question}/resolve", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 200),
            owned("POST", "/questions/{question}/answers", "{\"content\": \"Trả lời mới\"}", LEARN_OUTSIDERS,
                    Who.STUDENT_A, 201),
            owned("PATCH", "/answers/{answer}", "{\"content\": \"Sửa\"}", everyoneBut(Who.OWNER_INSTRUCTOR),
                    Who.OWNER_INSTRUCTOR, 200),
            owned("DELETE", "/answers/{answer}", null, everyoneBut(Who.OWNER_INSTRUCTOR), Who.OWNER_INSTRUCTOR, 204),
            owned("DELETE", "/questions/{question}", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 204),
            // ---- learning: reviews and certificates (UC-S13, UC-S14, UC-S20)
            owned("PATCH", "/reviews/{review}", "{\"rating\": 4, \"comment\": \"Ổn\"}", everyoneBut(Who.STUDENT_A),
                    Who.STUDENT_A, 200),
            owned("DELETE", "/reviews/{review}", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 204),
            owned("GET", "/me/certificates/{certCode}/pdf", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 200),
            // ---- commerce: own orders, payments, cart (UC-S03, UC-S05, UC-S07, UC-S16)
            owned("GET", "/orders/{orderCode}", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 200),
            owned("GET", "/orders/{orderCode}/payments/current", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 200),
            owned("POST", "/orders/{orderCode}/cancel", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 200),
            owned("POST", "/orders/{orderCode}/payments", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 200),
            owned("DELETE", "/cart/items/{paidCourse}", null, everyoneBut(Who.STUDENT_A), Who.STUDENT_A, 204));


    /** {@code /admin/**}: role route, 403 for everybody else whether or not the id exists (admins see all by design). */
    static final List<Row> ADMIN = List.of(
            admin("POST", "/admin/courses/{course}/approve", null),
            admin("POST", "/admin/courses/{course}/reject", "{\"reason\": \"Lý do\"}"),
            admin("POST", "/admin/courses/{course}/hide", "{\"reason\": \"Lý do\"}"),
            admin("POST", "/admin/courses/{course}/unhide", null),
            admin("PATCH", "/admin/categories/{category}", "{\"name\": \"Tên mới\"}"),
            admin("DELETE", "/admin/categories/{category}", null),
            admin("POST", "/admin/users/{studentAId}/lock", null),
            admin("POST", "/admin/users/{studentAId}/unlock", null),
            admin("PUT", "/admin/users/{studentAId}/roles/instructor", null),
            admin("DELETE", "/admin/users/{studentAId}/roles/instructor", null),
            admin("POST", "/admin/payments/{payment}/resolve", "{\"action\": \"activated\", \"note\": \"Ghi chú\"}"),
            admin("POST", "/admin/reviews/{review}/hide", null),
            admin("POST", "/admin/reviews/{review}/restore", null),
            admin("POST", "/admin/questions/{question}/hide", null),
            admin("POST", "/admin/questions/{question}/restore", null),
            admin("POST", "/admin/answers/{answer}/hide", null),
            admin("POST", "/admin/answers/{answer}/restore", null));

    /** Public catalog: a draft / pending course and a lesson that is not a preview are "not found" to everybody. */
    static final List<Row> PUBLIC = List.of(
            pub("/catalog/courses/{draftSlug}"),
            pub("/catalog/courses/{pendingSlug}"),
            pub("/catalog/courses/{draftSlug}/reviews"),
            pub("/catalog/courses/{draftSlug}/lessons/{draftLesson}/preview"),
            pub("/catalog/courses/{publishedSlug}/lessons/{l1}/preview").target("l1"));

    static Stream<Row> owned() {
        return OWNED.stream();
    }

    static Stream<Row> protectedRows() {
        return Stream.concat(OWNED.stream(), ADMIN.stream());
    }

    static Stream<Row> instructorRows() {
        return OWNED.stream().filter(Row::instructorRoute);
    }

    static Stream<Row> adminRows() {
        return ADMIN.stream();
    }

    static Stream<Row> publicRows() {
        return PUBLIC.stream();
    }

    // ===================================================================================================== tests

    @ParameterizedTest(name = "{0}")
    @MethodSource("owned")
    void stranger_404_identicalToMissing_noData(Row row) throws Exception {
        World w = world();
        assertThat(row.strangers()).isNotEmpty();
        for (Who who : row.strangers()) {
            check(row + " as " + who, () -> w.assertNotFoundLikeMissing(row, who));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("owned")
    void owner_succeeds_theRowIsNotVacuous(Row row) throws Exception {
        World w = ownerWorld();
        String path = row.ownerPath() == null ? row.path() : row.ownerPath();
        MvcResult result = mvc.perform(w.request(row.method(), path, row.body(), row.owner(), null, false))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(row + " as owner " + row.owner() + ": " + result.getResponse().getContentAsString(
                        StandardCharsets.UTF_8))
                .isEqualTo(row.ownerStatus());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedRows")
    void noToken_401(Row row) throws Exception {
        // a 401 never depends on the id, so no world is needed: every token becomes a missing value
        MockHttpServletRequestBuilder anonymous = request(HttpMethod.valueOf(row.method()),
                API + resolveAll(row.path()));
        if (row.body() != null) {
            anonymous.contentType(MediaType.APPLICATION_JSON).content(resolveAll(row.body()));
        }
        expectProblem(mvc.perform(anonymous), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instructorRows")
    void noInstructorRole_403_beforeAnyLookup(Row row) throws Exception {
        World w = world();
        // student role only, and a token that still claims instructor after the admin revoked the role (BR-26, D-07)
        for (Who who : List.of(Who.STRANGER_STUDENT, Who.REVOKED_INSTRUCTOR)) {
            check(row + " as " + who, () -> {
                MvcResult real = expectProblem(mvc.perform(w.request(row, who, false)), 403, "ACCESS_DENIED");
                MvcResult missing = expectProblem(mvc.perform(w.request(row, who, true)), 403, "ACCESS_DENIED");
                assertThat(withoutTraceAndInstance(real)).as("403 must not depend on the id")
                        .isEqualTo(withoutTraceAndInstance(missing));
                w.assertNoDataIn(real);
            });
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminRows")
    void adminRoute_nonAdmin_403_sameForMissing(Row row) throws Exception {
        World w = world();
        for (Who who : row.strangers()) {
            check(row + " as " + who, () -> {
                MvcResult real = expectProblem(mvc.perform(w.request(row, who, false)), 403, "ACCESS_DENIED");
                MvcResult missing = expectProblem(mvc.perform(w.request(row, who, true)), 403, "ACCESS_DENIED");
                assertThat(withoutTraceAndInstance(real)).as("403 must not depend on the id")
                        .isEqualTo(withoutTraceAndInstance(missing));
                w.assertNoDataIn(real);
            });
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicRows")
    void publicCatalog_unpublishedOrNonPreview_404(Row row) throws Exception {
        World w = world();
        for (Who who : row.strangers()) {
            check(row + " as " + who, () -> w.assertNotFoundLikeMissing(row, who));
        }
    }

    @Test
    void publicCatalog_publishedCourse_isReadable_control() throws Exception {
        World w = world();
        mvc.perform(request(HttpMethod.GET, API + "/catalog/courses/" + w.ids.get("publishedSlug")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /**
     * BR-38: a caller who can read the thread (a classmate, the admin) but neither asked it nor instructs the course
     * gets 403 {@code QA_REPLY_NOT_ALLOWED}. Not an enumeration leak: the same caller reads the whole question with
     * GET (200) anyway; a caller who cannot see it (stranger, pending co-instructor…) gets the 404 of the main matrix.
     */
    @Test
    void answer_visibleButNotAllowed_403_QA_REPLY_NOT_ALLOWED_BR38() throws Exception {
        World w = world();
        String question = w.ids.get("question");
        for (Who who : List.of(Who.CLASSMATE, Who.ADMIN)) {
            mvc.perform(w.request("GET", "/questions/" + question, null, who, null, false))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            check("answer as " + who, () -> {
                MvcResult result = expectProblem(mvc.perform(w.request("POST", "/questions/" + question + "/answers",
                        "{\"content\": \"Em trả lời\"}", who, null, false)), 403, "QA_REPLY_NOT_ALLOWED");
                w.assertNoDataIn(result);
            });
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question_answers WHERE question_id = ?").param(Long.parseLong(question))
                .query(Long.class).single()).as("nothing written").isEqualTo(1L);
    }

    /**
     * UC-S12 E1: a caller with no enrollment in a <b>published</b> course gets 403 {@code ENROLLMENT_REQUIRED}; a
     * missing id gets 404. A published course id is public (catalog by slug, course cards), so telling "exists" here
     * discloses nothing; the 403 never depends on anything owned by another user.
     */
    @Test
    void reviewCreate_noEnrollmentInPublishedCourse_403_ENROLLMENT_REQUIRED_E1() throws Exception {
        World w = world();
        String body = "{\"rating\": 5, \"comment\": \"Tốt\"}";
        for (Who who : List.of(Who.STRANGER_STUDENT, Who.STRANGER_INSTRUCTOR, Who.OWNER_INSTRUCTOR, Who.ADMIN,
                Who.CO_ACCEPTED)) {
            check("review create as " + who, () -> {
                MvcResult result = expectProblem(mvc.perform(w.request("POST", "/courses/" + w.ids.get("course")
                        + "/reviews", body, who, null, false)), 403, "ENROLLMENT_REQUIRED");
                w.assertNoDataIn(result);
                expectProblem(mvc.perform(w.request("POST", "/courses/" + MISSING_ID + "/reviews", body, who, null,
                        false)), 404, "RESOURCE_NOT_FOUND");
            });
        }
    }

    /**
     * P10 403/404 principle: a course that is not public (draft, pending review) is invisible to a caller with no
     * enrollment — review create answers the 404 of an unknown id, identical body (MINOR-2 of the P10 security review).
     */
    @Test
    void reviewCreate_noEnrollmentInNonPublicCourse_404IdenticalToMissing_NFR01() throws Exception {
        World w = world();
        String body = "{\"rating\": 5, \"comment\": \"Tốt\"}";
        for (String course : List.of("draftCourse", "pendingCourse")) {
            for (Who who : List.of(Who.STRANGER_STUDENT, Who.STRANGER_INSTRUCTOR, Who.OWNER_INSTRUCTOR, Who.ADMIN)) {
                check("review create on " + course + " as " + who, () -> assertSameNotFound(mvc,
                        w.request("POST", "/courses/" + w.ids.get(course) + "/reviews", body, who, null, false),
                        w.request("POST", "/courses/" + MISSING_ID + "/reviews", body, who, null, false)));
            }
        }
    }

    // ================================================================================================== helpers

    interface Check {
        void run() throws Exception;
    }

    /** Runs {@code check}; a failure names the endpoint and the caller. */
    static void check(String context, Check check) {
        try {
            check.run();
        } catch (AssertionError e) {
            throw new AssertionError(context + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AssertionError(context + ": " + e, e);
        }
    }

    World world() {
        if (world == null) {
            world = new World();
        }
        return world;
    }

    World ownerWorld() {
        if (ownerWorld == null) {
            ownerWorld = new World();
        }
        return ownerWorld;
    }

    static String resolveAll(String template) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(MISSING.getOrDefault(m.group(1), MISSING_ID)));
        }
        return m.appendTail(out).toString();
    }

    /** User A's data and everybody around it (see {@link #build}); one per test. */
    class World {

        final CourseFixtures fixtures = new CourseFixtures(jdbc);
        final Map<Who, Session> sessions = new LinkedHashMap<>();
        final Map<String, String> ids = new HashMap<>();
        final List<String> secrets = new ArrayList<>();
        final List<Long> courses = new ArrayList<>();
        final List<Long> students = new ArrayList<>();
        final List<Long> orders = new ArrayList<>();

        World() {
            build();
        }

        Session session(Who who) {
            return sessions.get(who);
        }

        private Session newSession(Who who, RoleCode... roles) {
            Session session = tokens.newUserSession(roles);
            sessions.put(who, session);
            return session;
        }

        private long insert(String sql, Object... params) {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql(sql).params(params).update(keys);
            return keys.getKey().longValue();
        }

        private long publishedCourse(long mainInstructor, long category, long price) {
            long course = fixtures.draftCourse(mainInstructor, category);
            courses.add(course);
            fixtures.lesson(fixtures.chapter(course));
            if (price > 0) {
                jdbc.sql("UPDATE courses SET price = ? WHERE id = ?").params(price, course).update();
            }
            fixtures.setStatus(course, "published", false);
            return course;
        }

        private void build() {
            String marker = "SECRET-" + UUID.randomUUID().toString().substring(0, 13);
            secrets.add(marker);

            Session owner = newSession(Who.OWNER_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session studentA = newSession(Who.STUDENT_A, RoleCode.STUDENT);
            Session classmate = newSession(Who.CLASSMATE, RoleCode.STUDENT);
            newSession(Who.STRANGER_STUDENT, RoleCode.STUDENT);
            Session strangerInstructor = newSession(Who.STRANGER_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session coPending = newSession(Who.CO_PENDING, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session coPending2 = newSession(Who.CO_PENDING_2, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session coRemovable = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session coDeclined = newSession(Who.CO_DECLINED, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session coRemoved = newSession(Who.CO_REMOVED, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session coAccepted = newSession(Who.CO_ACCEPTED, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            Session admin = newSession(Who.ADMIN, RoleCode.ADMIN);
            Session revoked = newSession(Who.REVOKED_INSTRUCTOR, RoleCode.STUDENT, RoleCode.INSTRUCTOR);
            users.revokeRole(revoked.userId(), RoleCode.INSTRUCTOR); // the token above still claims instructor
            sessions.put(Who.ANONYMOUS, null);
            students.addAll(List.of(studentA.userId(), classmate.userId()));
            ids.put("strangerInstructorEmail", strangerInstructor.user().email());
            ids.put("coPendingUser", String.valueOf(coPending.userId()));
            ids.put("studentAId", String.valueOf(studentA.userId()));
            secrets.add(owner.user().email());
            secrets.add(studentA.user().email());

            // the main course: published, free, two chapters with three lessons, co-instructors in every state
            long category = fixtures.category(true);
            ids.put("category", String.valueOf(category));
            long course = fixtures.draftCourse(owner.userId(), category);
            courses.add(course);
            long ch1 = fixtures.chapter(course, 1);
            long l1 = fixtures.lesson(ch1, 1);
            long l2 = fixtures.lesson(ch1, 2);
            long ch2 = fixtures.chapter(course, 2);
            fixtures.lesson(ch2, 1);
            fixtures.setStatus(course, "published", false);
            ids.put("course", String.valueOf(course));
            ids.put("ch1", String.valueOf(ch1));
            ids.put("ch2", String.valueOf(ch2));
            ids.put("l1", String.valueOf(l1));
            ids.put("l2", String.valueOf(l2));
            long coPendingInv = fixtures.coInstructor(course, coPending.userId(), owner.userId(), "pending");
            fixtures.coInstructor(course, coDeclined.userId(), owner.userId(), "declined");
            fixtures.coInstructor(course, coRemoved.userId(), owner.userId(), "removed");
            fixtures.coInstructor(course, coAccepted.userId(), owner.userId(), "accepted");
            ids.put("coPendingInv", String.valueOf(coPendingInv));
            ids.put("coPendingInv2", String.valueOf(
                    fixtures.coInstructor(course, coPending2.userId(), owner.userId(), "pending")));
            fixtures.coInstructor(course, coRemovable.userId(), owner.userId(), "pending");
            ids.put("coRemovableUser", String.valueOf(coRemovable.userId()));

            // a draft (complete enough to submit and to delete) and a course waiting for review (BR-33)
            long draft = fixtures.draftCourse(owner.userId(), category);
            courses.add(draft);
            jdbc.sql("UPDATE courses SET description = 'Mô tả khóa học' WHERE id = ?").param(draft).update();
            long draftLesson = fixtures.lesson(fixtures.chapter(draft));
            ids.put("draftCourse", String.valueOf(draft));
            ids.put("draftLesson", String.valueOf(draftLesson));
            long pending = fixtures.draftCourse(owner.userId(), category);
            courses.add(pending);
            long pendingCh = fixtures.chapter(pending);
            long pendingLesson = fixtures.lesson(pendingCh);
            fixtures.markPendingReview(pending);
            ids.put("pendingCourse", String.valueOf(pending));
            ids.put("pendingCh", String.valueOf(pendingCh));
            ids.put("pendingLesson", String.valueOf(pendingLesson));
            // a draft that passes the BR-07 checklist, consumed by the submit row
            long submit = fixtures.draftCourse(owner.userId(), category);
            courses.add(submit);
            jdbc.sql("UPDATE courses SET description = 'Mô tả khóa học' WHERE id = ?").param(submit).update();
            fixtures.lesson(fixtures.chapter(submit));
            ids.put("submitCourse", String.valueOf(submit));

            // a free published course anybody may enrol in, two paid ones for the cart and the order
            ids.put("openCourse", String.valueOf(publishedCourse(owner.userId(), category, 0)));
            long paid = publishedCourse(owner.userId(), category, 499_000);
            long paid2 = publishedCourse(owner.userId(), category, 299_000);
            ids.put("paidCourse", String.valueOf(paid));

            // learning of student A (a purchase enrollment) and of a classmate
            long enrollment = fixtures.purchaseEnrollment(studentA.userId(), course);
            fixtures.enrollment(classmate.userId(), course);
            long question = fixtures.question(enrollment);
            long hidden = fixtures.question(enrollment);
            fixtures.questionVisibility(hidden, "hidden", admin.userId());
            long answer = fixtures.answer(question, owner.userId(), "instructor");
            long review = fixtures.review(enrollment, 5, "visible", null);
            String certCode = "EDU-" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 12)
                    .replaceAll("(.{4})(.{4})(.{4})", "$1-$2-$3");
            insert("""
                    INSERT INTO certificates (enrollment_id, certificate_code, student_name, course_title,
                                              instructor_name)
                    VALUES (?, ?, ?, ?, ?)""", enrollment, certCode, marker, marker, marker);
            ids.put("question", String.valueOf(question));
            ids.put("hiddenQuestion", String.valueOf(hidden));
            ids.put("answer", String.valueOf(answer));
            ids.put("review", String.valueOf(review));
            ids.put("certCode", certCode);
            secrets.add(certCode);

            // commerce: student A's cart and pending order with a pending QR; the classmate has a cart of his own
            long cartA = insert("INSERT INTO carts (student_id) VALUES (?)", studentA.userId());
            jdbc.sql("INSERT INTO cart_items (cart_id, course_id) VALUES (?, ?)").params(cartA, paid).update();
            long cartB = insert("INSERT INTO carts (student_id) VALUES (?)", classmate.userId());
            jdbc.sql("INSERT INTO cart_items (cart_id, course_id) VALUES (?, ?)").params(cartB, paid2).update();
            String orderCode = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            long order = insert("""
                    INSERT INTO orders (student_id, order_code, total_amount, status) VALUES (?, ?, 499000, 'pending')""",
                    studentA.userId(), orderCode);
            orders.add(order);
            jdbc.sql("INSERT INTO order_items (order_id, course_id, unit_price) VALUES (?, ?, 499000)")
                    .params(order, paid).update();
            long payment = insert("""
                    INSERT INTO payment_transactions (order_id, transaction_ref, amount, qr_data, qr_expires_at, status)
                    VALUES (?, ?, 499000, 'qr-data', CURRENT_TIMESTAMP(6) + INTERVAL 15 MINUTE, 'pending')""",
                    order, "TXN-" + UUID.randomUUID());
            ids.put("orderCode", orderCode);
            ids.put("payment", String.valueOf(payment));
            secrets.add(orderCode);

            // A's texts all carry the marker, so one substring check finds any of them in a response
            for (long id : courses) {
                jdbc.sql("UPDATE courses SET title = CONCAT(?, ' ', title) WHERE id = ?").params(marker, id).update();
            }
            jdbc.sql("UPDATE chapters SET title = CONCAT(?, ' ', title) WHERE course_id IN (%s)"
                    .formatted(idList(courses))).param(marker).update();
            jdbc.sql("UPDATE lessons SET title = CONCAT(?, ' ', title) WHERE chapter_id IN (SELECT id FROM chapters "
                    + "WHERE course_id IN (%s))".formatted(idList(courses))).param(marker).update();
            jdbc.sql("UPDATE course_questions SET title = ?, content = ? WHERE enrollment_id = ?")
                    .params(marker, marker, enrollment).update();
            jdbc.sql("UPDATE question_answers SET content = ? WHERE question_id = ?").params(marker, question).update();
            jdbc.sql("UPDATE course_reviews SET comment = ? WHERE enrollment_id = ?").params(marker, enrollment).update();
            for (long id : courses) {
                secrets.add(jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(id).query(String.class).single());
            }
            ids.put("draftSlug", slug(draft));
            ids.put("pendingSlug", slug(pending));
            ids.put("publishedSlug", slug(course));
        }

        private String slug(long course) {
            return jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();
        }

        private String idList(List<Long> list) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }

        /** Placeholders replaced from the world; the {@code target} one by the missing value when {@code missing}. */
        String resolve(String template, String target, boolean missing) {
            Matcher m = TOKEN.matcher(template);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String token = m.group(1);
                String value = missing && token.equals(target) ? MISSING.getOrDefault(token, MISSING_ID) : ids.get(token);
                assertThat(value).as("world value of {" + token + "}").isNotNull();
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            return m.appendTail(out).toString();
        }

        MockHttpServletRequestBuilder request(Row row, Who who, boolean missing) {
            return request(row.method(), row.path(), row.body(), who, row.target(), missing);
        }

        MockHttpServletRequestBuilder request(String method, String path, String body, Who who, String target,
                                              boolean missing) {
            MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .request(HttpMethod.valueOf(method), API + resolve(path, target, missing));
            if (who != Who.ANONYMOUS) {
                builder.header(HttpHeaders.AUTHORIZATION, session(who).bearer());
            }
            if (body != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(resolve(body, target, false));
            }
            return builder;
        }

        void assertNoDataIn(MvcResult result) throws Exception {
            // the problem echoes the path the caller asked for (`instance`): that is the caller's own input, not data
            String body = withoutTraceAndInstance(result);
            for (String secret : secrets) {
                assertThat(body).as("no data of the owner in the body").doesNotContain(secret);
            }
        }

        /** The core assertion: owner's id and a missing id answer the same 404, and the body carries no owner data. */
        void assertNotFoundLikeMissing(Row row, Who who) throws Exception {
            MvcResult others = mvc.perform(request(row, who, false)).andReturn();
            assertThat(others.getResponse().getStatus()).as("status of another user's id").isEqualTo(404);
            assertNoDataIn(others);
            assertSameNotFound(mvc, request(row, who, false), request(row, who, true));
        }

        /** Removes everything of the world, children first (the shared database stays clean). */
        void tearDown() {
            String courseIds = idList(courses);
            for (Long id : students) {
                jdbc.sql("DELETE FROM carts WHERE student_id = ?").param(id).update(); // items by cascade
            }
            for (Long order : orders) {
                jdbc.sql("DELETE FROM payment_transactions WHERE order_id = ?").param(order).update();
                jdbc.sql("DELETE FROM order_items WHERE order_id = ?").param(order).update();
                jdbc.sql("DELETE FROM orders WHERE id = ?").param(order).update();
            }
            // enrollments made by the owner step (UC-S15) and everything hanging on them
            String enrollments = "SELECT id FROM enrollments WHERE course_id IN (" + courseIds + ")";
            jdbc.sql("DELETE FROM question_answers WHERE question_id IN (SELECT id FROM course_questions WHERE "
                    + "enrollment_id IN (" + enrollments + "))").update();
            jdbc.sql("DELETE FROM course_questions WHERE enrollment_id IN (" + enrollments + ")").update();
            jdbc.sql("DELETE FROM course_reviews WHERE enrollment_id IN (" + enrollments + ")").update();
            jdbc.sql("DELETE FROM certificates WHERE enrollment_id IN (" + enrollments + ")").update();
            jdbc.sql("DELETE FROM lesson_progresses WHERE enrollment_id IN (" + enrollments + ")").update();
            jdbc.sql("DELETE FROM enrollments WHERE course_id IN (" + courseIds + ")").update();
            fixtures.deleteAll();
        }
    }
}
