package com.edumy.authoring.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static com.edumy.support.ProblemAssertions.withoutTraceAndInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.edumy.authoring.api.CoInstructorInvited;
import com.edumy.authoring.api.CourseContentChanged;
import com.edumy.shared.error.ConstraintViolationTranslator;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.IdorAssertions;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.SqlStatementCounter;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Co-instructor invitations through the real filter chain and MySQL: UC-I13 invite (E1 not eligible, E2 already
 * invited, E3 / BR-02 main instructor — service and trigger, D-18 row reuse), UC-I14 accept / UC-I15 decline (invitee
 * only, IDOR, revoked → 409), UC-I16 remove (rights end at once, AC-17), UC-I20 leave (main refused), BR-26 roles,
 * BR-06 main only (AC-05), NFR-01. The invitation mail itself: {@code AuthoringMailListenerIT}.
 */
@MySqlSpringBootTest
@RecordApplicationEvents
class CoInstructorWriteIT {

    static final String COURSES = "/api/v1/instructor/courses";
    static final String INVITATIONS = "/api/v1/instructor/invitations";

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
    @Autowired
    SqlStatementCounter sql;
    @Autowired
    ConstraintViolationTranslator translator;

    CourseFixtures fixtures;
    Session main;
    Session invitee;
    long course;

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        main = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        invitee = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(main.userId());
    }

    @AfterEach
    void cleanUp() {
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------------ UC-I13 invite

    @Test
    void invite_instructor_201Pending_rowAndEvent_UCI13() throws Exception {
        jdbc.sql("UPDATE users SET full_name = 'Trần Đồng Giảng' WHERE id = ?").param(invitee.userId()).update();

        sql.reset();
        mvc.perform(invite(course, main, invitee.user().email()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        COURSES + "/" + course + "/co-instructors/" + invitee.userId()))
                .andExpect(jsonPath("$.id").value(invitee.userId()))
                .andExpect(jsonPath("$.fullName").value("Trần Đồng Giảng"))
                .andExpect(jsonPath("$.invitationStatus").value("pending"))
                .andExpect(jsonPath("$.invitedAt").isNotEmpty())
                .andExpect(jsonPath("$.respondedAt").value(nullValue()));
        assertThat(sql.statements()).as("the course row is locked first")
                .anyMatch(statement -> statement.toLowerCase().contains("for update"));

        Map<String, Object> row = row(course, invitee.userId());
        assertThat(row.get("invitation_status")).isEqualTo("pending");
        assertThat(((Number) row.get("invited_by")).longValue()).isEqualTo(main.userId());
        assertThat(row.get("responded_at")).isNull();
        long id = ((Number) row.get("id")).longValue();
        assertThat(events.stream(CoInstructorInvited.class))
                .containsExactly(new CoInstructorInvited(id, course, invitee.userId(), main.userId()));

        // UC-I13 step 6 / UC-I14: shown in the course detail and in the invitee's pending list
        mvc.perform(get(COURSES + "/" + course).header(HttpHeaders.AUTHORIZATION, main.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coInstructors[0].id").value(invitee.userId()))
                .andExpect(jsonPath("$.coInstructors[0].invitationStatus").value("pending"));
        mvc.perform(get(INVITATIONS).header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].courseId").value(course));
    }

    @Test
    void invite_emailWithOtherCaseAndSpaces_found_UCI13() throws Exception {
        mvc.perform(invite(course, main, "  " + invitee.user().email().toUpperCase() + " "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(invitee.userId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"declined", "removed"})
    void invite_declinedOrRemoved_reusesTheSameRow_backToPending_D18(String previous) throws Exception {
        TestUser otherMain = users.create(RoleCode.INSTRUCTOR); // a different earlier inviter, to see it renewed
        long id = fixtures.coInstructor(course, invitee.userId(), otherMain.id(), previous);
        jdbc.sql("UPDATE course_co_instructors SET invited_at = '2020-01-01 00:00:00' WHERE id = ?").param(id).update();

        mvc.perform(invite(course, main, invitee.user().email()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitationStatus").value("pending"))
                .andExpect(jsonPath("$.respondedAt").value(nullValue()));

        Map<String, Object> row = row(course, invitee.userId());
        assertThat(((Number) row.get("id")).longValue()).as("same row, D-18").isEqualTo(id);
        assertThat(row.get("invitation_status")).isEqualTo("pending");
        assertThat(row.get("responded_at")).isNull();
        assertThat(((Number) row.get("invited_by")).longValue()).isEqualTo(main.userId());
        assertThat(row.get("invited_at").toString()).doesNotStartWith("2020");
        assertThat(rows(course)).isOne();
        assertThat(events.stream(CoInstructorInvited.class)).extracting(CoInstructorInvited::invitationId)
                .containsExactly(id);
    }

    @Test
    void invite_accept_leave_reinvite_sameRowAgain_D18() throws Exception {
        mvc.perform(invite(course, main, invitee.user().email())).andExpect(status().isCreated());
        long id = invitationId(course, invitee.userId());
        mvc.perform(post(INVITATIONS + "/" + id + "/accept").header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(post(COURSES + "/" + course + "/leave").header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isNoContent());

        mvc.perform(invite(course, main, invitee.user().email())).andExpect(status().isCreated());

        assertThat(invitationId(course, invitee.userId())).isEqualTo(id);
        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo("pending");
        assertThat(rows(course)).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "accepted"})
    void invite_pendingOrAccepted_409AlreadyInvited_nothingChanges_UCI13E2(String current) throws Exception {
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), current);

        expectProblem(mvc.perform(invite(course, main, invitee.user().email())), 409,
                "COURSE_CO_INSTRUCTOR_ALREADY_INVITED");

        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo(current);
        assertThat(invitationId(course, invitee.userId())).isEqualTo(id);
        assertThat(events.stream(CoInstructorInvited.class)).isEmpty();
    }

    /** E1: unknown email, student without instructor role and revoked instructor get the same answer (no probing). */
    @Test
    void invite_unknownEmailOrNoActiveInstructorRole_422NotEligible_identicalBodies_UCI13E1() throws Exception {
        TestUser student = users.create(RoleCode.STUDENT);
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);

        MvcResult unknown = expectProblem(mvc.perform(invite(course, main, "khong-ton-tai-" + course + "@edumy.test")),
                422, "COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE");
        MvcResult noRole = expectProblem(mvc.perform(invite(course, main, student.email())),
                422, "COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE");
        MvcResult revokedRole = expectProblem(mvc.perform(invite(course, main, revoked.email())),
                422, "COURSE_CO_INSTRUCTOR_NOT_ELIGIBLE");

        jsonPath("$.title").value("Tài khoản chưa đăng ký làm giảng viên").match(unknown);
        assertThat(withoutTraceAndInstance(noRole)).isEqualTo(withoutTraceAndInstance(unknown));
        assertThat(withoutTraceAndInstance(revokedRole)).isEqualTo(withoutTraceAndInstance(unknown));
        assertThat(rows(course)).isZero();
    }

    @Test
    void invite_mainInstructorsOwnEmail_422IsMain_BR02_UCI13E3() throws Exception {
        expectProblem(mvc.perform(invite(course, main, main.user().email())), 422, "COURSE_CO_INSTRUCTOR_IS_MAIN");

        assertThat(rows(course)).isZero();
    }

    /** BR-02 second line of defence: the V5 triggers refuse the row and the translator gives the same code. */
    @Test
    void trigger_mainInstructorAsCoInstructor_insertOrUpdate_mapsToIsMain_BR02() {
        Throwable insert = catchThrowable(() -> jdbc.sql("""
                        INSERT INTO course_co_instructors (course_id, instructor_id, invited_by) VALUES (?, ?, ?)""")
                .params(course, main.userId(), invitee.userId()).update());
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), "pending");
        Throwable update = catchThrowable(() -> jdbc.sql("""
                        UPDATE course_co_instructors SET instructor_id = ?, invited_by = ? WHERE id = ?""")
                .params(main.userId(), invitee.userId(), id).update());

        for (Throwable thrown : new Throwable[] {insert, update}) {
            assertThat(thrown).isNotNull();
            assertThat(translator.translate(thrown)).get()
                    .extracting(ConstraintViolationTranslator.TranslatedViolation::code)
                    .isEqualTo(ErrorCode.COURSE_CO_INSTRUCTOR_IS_MAIN);
        }
        assertThat(rows(course)).isOne();
    }

    @Test
    void invite_pendingReviewOrPublishedCourse_allowed_BR05_BR21() throws Exception {
        fixtures.markPendingReview(course);
        mvc.perform(invite(course, main, invitee.user().email())).andExpect(status().isCreated());

        long published = fixtures.draftCourse(main.userId());
        fixtures.setStatus(published, "published", true);
        mvc.perform(invite(published, main, invitee.user().email())).andExpect(status().isCreated());
    }

    @Test
    void invite_invalidEmail_400() throws Exception {
        expectField(mvc.perform(json(post(COURSES + "/" + course + "/co-instructors"), main, "{}")), "email");
        expectField(mvc.perform(json(post(COURSES + "/" + course + "/co-instructors"), main,
                "{\"email\": \"khong-phai-email\"}")), "email");
    }

    /** AC-05: an accepted co-instructor edits lessons but cannot invite (nor remove): BR-06 → 404 like a stranger. */
    @Test
    void inviteAndRemove_byAcceptedCoInstructor_404_BR06_AC05() throws Exception {
        fixtures.coInstructor(course, invitee.userId(), main.userId(), "accepted");
        Session third = tokens.newUserSession(RoleCode.INSTRUCTOR);
        mvc.perform(addChapter(course, invitee)).andExpect(status().isCreated());

        expectProblem(mvc.perform(invite(course, invitee, third.user().email())), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(removeRequest(course, main.userId(), invitee)), 404, "RESOURCE_NOT_FOUND");
        assertThat(rows(course)).isOne();
    }

    @Test
    void inviteAndRemove_otherInstructorsCourse_404IdenticalToMissing_NFR01() throws Exception {
        Session stranger = tokens.newUserSession(RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, invitee.userId(), main.userId(), "pending");

        IdorAssertions.assertSameNotFound(mvc,
                invite(course, stranger, invitee.user().email()),
                invite(Long.MAX_VALUE, stranger, invitee.user().email()));
        IdorAssertions.assertSameNotFound(mvc,
                removeRequest(course, invitee.userId(), stranger),
                removeRequest(Long.MAX_VALUE, invitee.userId(), stranger));
        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo("pending");
    }

    @Test
    void invite_revokedMainWithStaleJwt_403_student_403_withoutToken_401_BR26() throws Exception {
        TestUser revokedMain = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long own = fixtures.draftCourse(revokedMain.id());
        users.revokeRole(revokedMain.id(), RoleCode.INSTRUCTOR);
        Session student = tokens.newUserSession(RoleCode.STUDENT);

        expectProblem(mvc.perform(invite(own, tokens.session(revokedMain), invitee.user().email())),
                403, "ACCESS_DENIED");
        expectProblem(mvc.perform(invite(course, student, invitee.user().email())), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(post(COURSES + "/" + course + "/co-instructors")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"a@edumy.test\"}")),
                401, "UNAUTHENTICATED");
        assertThat(rows(own)).isZero();
    }

    // ------------------------------------------------------------------------------------- UC-I14 / UC-I15 respond

    @Test
    void accept_pending_204_accepted_coEditsAtOnce_listsTheCourse_UCI14_BR04() throws Exception {
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), "pending");
        expectProblem(mvc.perform(addChapter(course, invitee)), 404, "RESOURCE_NOT_FOUND"); // pending: no rights

        mvc.perform(post(INVITATIONS + "/" + id + "/accept").header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isNoContent());

        Map<String, Object> row = row(course, invitee.userId());
        assertThat(row.get("invitation_status")).isEqualTo("accepted");
        assertThat(row.get("responded_at")).isNotNull();
        mvc.perform(addChapter(course, invitee)).andExpect(status().isCreated());
        mvc.perform(get(COURSES).header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(jsonPath("$.content[0].id").value(course))
                .andExpect(jsonPath("$.content[0].relation").value("co"));
        mvc.perform(get(INVITATIONS).header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(jsonPath("$").isEmpty());
        assertThat(events.stream(CourseContentChanged.class)).as("draft course: no catalog event").isEmpty();
    }

    @Test
    void accept_publishedCourse_courseContentChanged() throws Exception {
        fixtures.setStatus(course, "published", true);
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), "pending");
        String slug = jdbc.sql("SELECT slug FROM courses WHERE id = ?").param(course).query(String.class).single();

        mvc.perform(post(INVITATIONS + "/" + id + "/accept").header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isNoContent());

        assertThat(events.stream(CourseContentChanged.class)).containsExactly(new CourseContentChanged(course, slug));
    }

    @Test
    void decline_pending_204_declined_noRights_UCI15_BR21() throws Exception {
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), "pending");
        fixtures.markPendingReview(course); // the review is not affected by the answer (BR-21, AC-06)

        mvc.perform(post(INVITATIONS + "/" + id + "/decline").header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isNoContent());

        Map<String, Object> row = row(course, invitee.userId());
        assertThat(row.get("invitation_status")).isEqualTo("declined");
        assertThat(row.get("responded_at")).isNotNull();
        assertThat(jdbc.sql("SELECT status FROM courses WHERE id = ?").param(course).query(String.class).single())
                .isEqualTo("pending_review");
        expectProblem(mvc.perform(get(COURSES + "/" + course).header(HttpHeaders.AUTHORIZATION, invitee.bearer())),
                404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "declined", "removed"})
    void acceptAndDecline_notPending_409InvitationNotPending_UCI14E(String current) throws Exception {
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), current);

        expectProblem(mvc.perform(post(INVITATIONS + "/" + id + "/accept")
                .header(HttpHeaders.AUTHORIZATION, invitee.bearer())), 409, "INVITATION_NOT_PENDING");
        expectProblem(mvc.perform(post(INVITATIONS + "/" + id + "/decline")
                .header(HttpHeaders.AUTHORIZATION, invitee.bearer())), 409, "INVITATION_NOT_PENDING");
        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo(current);
    }

    @Test
    void acceptAndDecline_someoneElsesInvitation_404IdenticalToMissing_NFR01() throws Exception {
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), "pending");
        Session other = tokens.newUserSession(RoleCode.INSTRUCTOR);

        for (String action : new String[] {"/accept", "/decline"}) {
            IdorAssertions.assertSameNotFound(mvc,
                    post(INVITATIONS + "/" + id + action).header(HttpHeaders.AUTHORIZATION, other.bearer()),
                    post(INVITATIONS + "/" + Long.MAX_VALUE + action).header(HttpHeaders.AUTHORIZATION, other.bearer()));
            // the inviting main instructor cannot answer for the invitee either
            expectProblem(mvc.perform(post(INVITATIONS + "/" + id + action)
                    .header(HttpHeaders.AUTHORIZATION, main.bearer())), 404, "RESOURCE_NOT_FOUND");
        }
        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo("pending");
    }

    @Test
    void accept_inviteeRoleRevoked_403_withoutToken_401_BR26() throws Exception {
        TestUser revoked = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long id = fixtures.coInstructor(course, revoked.id(), main.userId(), "pending");
        users.revokeRole(revoked.id(), RoleCode.INSTRUCTOR);
        Session stale = tokens.session(revoked);

        expectProblem(mvc.perform(post(INVITATIONS + "/" + id + "/accept")
                .header(HttpHeaders.AUTHORIZATION, stale.bearer())), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(post(INVITATIONS + "/" + id + "/decline")
                .header(HttpHeaders.AUTHORIZATION, stale.bearer())), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(post(INVITATIONS + "/" + id + "/accept")), 401, "UNAUTHENTICATED");
        assertThat(row(course, revoked.id()).get("invitation_status")).isEqualTo("pending");
    }

    // ------------------------------------------------------------------------------------------------ UC-I16 remove

    @Test
    void remove_acceptedCo_204_removed_editingRefusedAtOnce_UCI16_AC17() throws Exception {
        fixtures.coInstructor(course, invitee.userId(), main.userId(), "accepted");
        mvc.perform(addChapter(course, invitee)).andExpect(status().isCreated());

        sql.reset();
        mvc.perform(removeRequest(course, invitee.userId(), main)).andExpect(status().isNoContent());
        assertThat(sql.statements()).anyMatch(statement -> statement.toLowerCase().contains("for update"));

        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo("removed");
        expectProblem(mvc.perform(addChapter(course, invitee)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(get(COURSES + "/" + course).header(HttpHeaders.AUTHORIZATION, invitee.bearer())),
                404, "RESOURCE_NOT_FOUND");
        mvc.perform(get(COURSES).header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(jsonPath("$.content[*].id", not(hasItem((int) course))));
    }

    @Test
    void remove_pendingInvitation_revoked_thenAccept409_UCI16_UCI14E() throws Exception {
        long id = fixtures.coInstructor(course, invitee.userId(), main.userId(), "pending");

        mvc.perform(removeRequest(course, invitee.userId(), main)).andExpect(status().isNoContent());

        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo("removed");
        expectProblem(mvc.perform(post(INVITATIONS + "/" + id + "/accept")
                .header(HttpHeaders.AUTHORIZATION, invitee.bearer())), 409, "INVITATION_NOT_PENDING");
        mvc.perform(get(INVITATIONS).header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void remove_publishedCourse_acceptedCo_courseContentChanged_pendingNot() throws Exception {
        fixtures.setStatus(course, "published", true);
        Session pendingOne = tokens.newUserSession(RoleCode.INSTRUCTOR);
        fixtures.coInstructor(course, invitee.userId(), main.userId(), "accepted");
        fixtures.coInstructor(course, pendingOne.userId(), main.userId(), "pending");

        mvc.perform(removeRequest(course, pendingOne.userId(), main)).andExpect(status().isNoContent());
        assertThat(events.stream(CourseContentChanged.class)).isEmpty();
        mvc.perform(removeRequest(course, invitee.userId(), main)).andExpect(status().isNoContent());
        assertThat(events.stream(CourseContentChanged.class)).extracting(CourseContentChanged::courseId)
                .containsExactly(course);
    }

    @ParameterizedTest
    @ValueSource(strings = {"declined", "removed"})
    void remove_declinedOrRemoved_404_unchanged(String current) throws Exception {
        fixtures.coInstructor(course, invitee.userId(), main.userId(), current);

        expectProblem(mvc.perform(removeRequest(course, invitee.userId(), main)), 404, "RESOURCE_NOT_FOUND");
        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo(current);
    }

    @Test
    void remove_notInvitedUserOrMainItself_404() throws Exception {
        expectProblem(mvc.perform(removeRequest(course, invitee.userId(), main)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(removeRequest(course, main.userId(), main)), 404, "RESOURCE_NOT_FOUND");
        expectProblem(mvc.perform(removeRequest(course, Long.MAX_VALUE, main)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void remove_revokedMain_403_withoutToken_401_BR26() throws Exception {
        TestUser revokedMain = users.create(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long own = fixtures.draftCourse(revokedMain.id());
        fixtures.coInstructor(own, invitee.userId(), revokedMain.id(), "accepted");
        users.revokeRole(revokedMain.id(), RoleCode.INSTRUCTOR);

        expectProblem(mvc.perform(removeRequest(own, invitee.userId(), tokens.session(revokedMain))),
                403, "ACCESS_DENIED");
        expectProblem(mvc.perform(delete(COURSES + "/" + own + "/co-instructors/" + invitee.userId())),
                401, "UNAUTHENTICATED");
        assertThat(row(own, invitee.userId()).get("invitation_status")).isEqualTo("accepted");
    }

    // ------------------------------------------------------------------------------------------------ UC-I20 leave

    @Test
    void leave_acceptedCo_204_removed_courseLeavesTheList_UCI20_D19() throws Exception {
        fixtures.coInstructor(course, invitee.userId(), main.userId(), "accepted");

        mvc.perform(post(COURSES + "/" + course + "/leave").header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(status().isNoContent());

        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo("removed");
        mvc.perform(get(COURSES).header(HttpHeaders.AUTHORIZATION, invitee.bearer()))
                .andExpect(jsonPath("$.content").isEmpty());
        expectProblem(mvc.perform(addChapter(course, invitee)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void leave_byMainInstructor_403MainLeaveForbidden_BR02() throws Exception {
        expectProblem(mvc.perform(post(COURSES + "/" + course + "/leave")
                .header(HttpHeaders.AUTHORIZATION, main.bearer())), 403, "COURSE_MAIN_INSTRUCTOR_LEAVE_FORBIDDEN");
        assertThat(jdbc.sql("SELECT main_instructor_id FROM courses WHERE id = ?").param(course)
                .query(Long.class).single()).isEqualTo(main.userId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "declined", "removed"})
    void leave_withoutAcceptedInvitation_404IdenticalToMissing_NFR01(String current) throws Exception {
        fixtures.coInstructor(course, invitee.userId(), main.userId(), current);

        IdorAssertions.assertSameNotFound(mvc,
                post(COURSES + "/" + course + "/leave").header(HttpHeaders.AUTHORIZATION, invitee.bearer()),
                post(COURSES + "/" + Long.MAX_VALUE + "/leave").header(HttpHeaders.AUTHORIZATION, invitee.bearer()));
        assertThat(row(course, invitee.userId()).get("invitation_status")).isEqualTo(current);
    }

    @Test
    void leave_studentOnly_403_withoutToken_401() throws Exception {
        Session student = tokens.newUserSession(RoleCode.STUDENT);

        expectProblem(mvc.perform(post(COURSES + "/" + course + "/leave")
                .header(HttpHeaders.AUTHORIZATION, student.bearer())), 403, "ACCESS_DENIED");
        expectProblem(mvc.perform(post(COURSES + "/" + course + "/leave")), 401, "UNAUTHENTICATED");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder invite(long courseId, Session caller, String email) {
        return json(post(COURSES + "/" + courseId + "/co-instructors"), caller, "{\"email\": \"" + email + "\"}");
    }

    private static MockHttpServletRequestBuilder removeRequest(long courseId, long userId, Session caller) {
        return delete(COURSES + "/" + courseId + "/co-instructors/" + userId)
                .header(HttpHeaders.AUTHORIZATION, caller.bearer());
    }

    /** UC-I05 as a probe of the editing rights (BR-04). */
    private static MockHttpServletRequestBuilder addChapter(long courseId, Session caller) {
        return json(post(COURSES + "/" + courseId + "/chapters"), caller, "{\"title\": \"Chương thử\"}");
    }

    private Map<String, Object> row(long courseId, long instructorId) {
        return jdbc.sql("""
                        SELECT id, invitation_status, invited_by, invited_at, responded_at FROM course_co_instructors
                        WHERE course_id = ? AND instructor_id = ?""")
                .params(courseId, instructorId).query().singleRow();
    }

    private long invitationId(long courseId, long instructorId) {
        return ((Number) row(courseId, instructorId).get("id")).longValue();
    }

    private long rows(long courseId) {
        return jdbc.sql("SELECT COUNT(*) FROM course_co_instructors WHERE course_id = ?").param(courseId)
                .query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session caller,
                                                      String body) {
        return request.header(HttpHeaders.AUTHORIZATION, caller.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static void expectField(ResultActions actions, String field) throws Exception {
        MvcResult result = expectProblem(actions, 400, "VALIDATION_FAILED");
        jsonPath("$.errors[*].field", hasItem(field)).match(result);
    }
}
