package com.edumy.commerce.controller;

import static com.edumy.support.IdorAssertions.assertSameNotFound;
import static com.edumy.support.ProblemAssertions.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.edumy.authoring.api.CourseHidden;
import com.edumy.commerce.listener.CourseHiddenListener;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-S01 add to cart and UC-S03 remove from cart through the real filter chain and MySQL: every check of UC-S01
 * step 1 (BR-31 published only, BR-09 free, BR-10 owned, BR-32 own course, BR-11 duplicate) with its error code, the
 * lazily created cart (BR-22) also under concurrent first adds, concurrent double add, removal scoped to the caller's
 * own cart (NFR-01), and the cart flags following hide / unhide without any write (UC-A12 step 4, D-20) with the
 * {@code CourseHiddenListener} reaction.
 */
@MySqlSpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class CartWriteIT {

    static final String ITEMS = "/api/v1/cart/items";
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
    @Autowired
    CourseHiddenListener listener;

    CourseFixtures fixtures;
    Session student;
    Session instructor;
    long course;
    final List<Long> students = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = new CourseFixtures(jdbc);
        student = session(RoleCode.STUDENT);
        instructor = session(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        course = paidCourse(instructor, 499_000);
    }

    @AfterEach
    void cleanUp() {
        // carts reference users and courses (RESTRICT): their items go by cascade
        for (Long id : students) {
            jdbc.sql("DELETE FROM carts WHERE student_id = ?").param(id).update();
        }
        fixtures.deleteAll();
        users.deleteAll();
    }

    Session session(RoleCode... roles) {
        Session session = tokens.newUserSession(roles);
        students.add(session.userId());
        return session;
    }

    /** A published course of {@code main} at {@code price} (0 = free). */
    long paidCourse(Session main, long price) {
        long id = fixtures.draftCourse(main.userId());
        fixtures.setStatus(id, "published", false);
        jdbc.sql("UPDATE courses SET price = ? WHERE id = ?").params(price, id).update();
        return id;
    }

    MockHttpServletRequestBuilder add(Session who, Object courseId) {
        return post(ITEMS).header(HttpHeaders.AUTHORIZATION, who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\": " + courseId + "}");
    }

    MockHttpServletRequestBuilder remove(Session who, long courseId) {
        return delete(ITEMS + "/" + courseId).header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    MockHttpServletRequestBuilder cart(Session who) {
        return get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, who.bearer());
    }

    long cartRows(Session who) {
        return jdbc.sql("SELECT COUNT(*) FROM carts WHERE student_id = ?").param(who.userId()).query(Long.class)
                .single();
    }

    long itemRows(Session who) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM cart_items ci JOIN carts c ON c.id = ci.cart_id WHERE c.student_id = ?""")
                .param(who.userId()).query(Long.class).single();
    }

    long cartId(Session who) {
        return jdbc.sql("SELECT id FROM carts WHERE student_id = ?").param(who.userId()).query(Long.class).single();
    }

    // ------------------------------------------------------------------------------------------------- UC-S01 add

    @Test
    void add_paidPublished_201_cartCreatedLazily_secondAddReusesIt_UCS01() throws Exception {
        long second = paidCourse(instructor, 299_000);
        Instant now = Instant.parse("2026-11-14T05:00:00Z");
        clock.setInstant(now);
        assertThat(cartRows(student)).isZero();

        mvc.perform(add(student, course))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.totalAmount").value(499_000))
                .andExpect(jsonPath("$.items[0].courseId").value(course))
                .andExpect(jsonPath("$.items[0].price").value(499_000))
                .andExpect(jsonPath("$.items[0].purchasable").value(true))
                .andExpect(jsonPath("$.items[0].addedAt").value(org.hamcrest.Matchers.startsWith("2026-11-14T05:00:00")));
        assertThat(cartRows(student)).isOne();
        long cartId = cartId(student);
        assertThat(jdbc.sql("SELECT created_at FROM carts WHERE id = ?").param(cartId).query(Timestamp.class)
                .single().toInstant()).isBetween(now, now.plusSeconds(5));

        mvc.perform(add(student, second))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.totalAmount").value(798_000))
                .andExpect(jsonPath("$.items[*].courseId", contains(Math.toIntExact(course), Math.toIntExact(second))));
        assertThat(cartRows(student)).isOne();
        assertThat(cartId(student)).isEqualTo(cartId);
        assertThat(itemRows(student)).isEqualTo(2);
        mvc.perform(cart(student)).andExpect(jsonPath("$.items", hasSize(2)));
    }

    /** D-02 asks for a verified email before ordering (UC-S04), not for carting (UC-S01 precondition: logged in). */
    @Test
    void add_unverifiedEmail_201_D02() throws Exception {
        users.markEmailUnverified(student.userId());

        mvc.perform(add(student, course)).andExpect(status().isCreated());

        assertThat(itemRows(student)).isOne();
    }

    @Test
    void add_freeCourse_422_noCartCreated_BR09() throws Exception {
        long free = paidCourse(instructor, 0);

        expectProblem(mvc.perform(add(student, free)), 422, "CART_COURSE_FREE");

        assertThat(cartRows(student)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "pending_review", "rejected", "hidden"})
    void add_notPublished_404_sameBodyAsUnknown_BR31(String state) throws Exception {
        long other = paidCourse(instructor, 499_000);
        if (state.equals("pending_review")) {
            fixtures.markPendingReview(other);
        } else if (state.equals("draft")) {
            jdbc.sql("UPDATE courses SET status = 'draft', published_at = NULL WHERE id = ?").param(other).update();
        } else {
            fixtures.setStatus(other, state, false);
        }

        assertSameNotFound(mvc, add(student, other), add(student, UNKNOWN_COURSE));

        assertThat(cartRows(student)).isZero();
    }

    @Test
    void add_alreadyOwned_409_BR10() throws Exception {
        fixtures.enrollment(student.userId(), course);

        expectProblem(mvc.perform(add(student, course)), 409, "CART_COURSE_ALREADY_OWNED");

        assertThat(cartRows(student)).isZero();
    }

    @Test
    void add_mainInstructor_422_evenWithRevokedRole_BR32() throws Exception {
        expectProblem(mvc.perform(add(instructor, course)), 422, "CART_OWN_COURSE");
        users.revokeRole(instructor.userId(), RoleCode.INSTRUCTOR);
        expectProblem(mvc.perform(add(instructor, course)), 422, "CART_OWN_COURSE");

        assertThat(cartRows(instructor)).isZero();
    }

    @Test
    void add_acceptedCoInstructor_422_butPendingDeclinedRemovedMayBuy_BR32() throws Exception {
        Session co = session(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        long invitation = fixtures.coInstructor(course, co.userId(), instructor.userId(), "accepted");

        expectProblem(mvc.perform(add(co, course)), 422, "CART_OWN_COURSE");
        assertThat(cartRows(co)).isZero();

        // "co-instructor" means an accepted invitation: pending / declined / removed rows do not count (docs/ai/02)
        for (String state : List.of("pending", "declined", "removed")) {
            jdbc.sql("UPDATE course_co_instructors SET invitation_status = ?, responded_at = ? WHERE id = ?")
                    .params(state, state.equals("pending") ? null : Timestamp.from(Instant.now()), invitation)
                    .update();
            mvc.perform(add(co, course)).andExpect(status().isCreated());
            mvc.perform(remove(co, course)).andExpect(status().isNoContent());
        }
    }

    @Test
    void add_duplicate_409_oneRow_BR11() throws Exception {
        mvc.perform(add(student, course)).andExpect(status().isCreated());

        expectProblem(mvc.perform(add(student, course)), 409, "CART_ITEM_DUPLICATE");

        assertThat(itemRows(student)).isOne();
        assertThat(cartRows(student)).isOne();
    }

    @Test
    void add_concurrentDoubleAddOfOneCourse_oneCreatedOneConflict_oneRow_BR11() throws Exception {
        for (int round = 0; round < 3; round++) {
            Session s = session(RoleCode.STUDENT);
            List<Integer> statuses = parallel(() -> statusOf(add(s, course)), () -> statusOf(add(s, course)));

            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 409);
            assertThat(itemRows(s)).isOne();
            assertThat(cartRows(s)).isOne();
        }
    }

    @Test
    void add_concurrentFirstAddsOfDifferentCourses_bothCreated_oneCart_BR22() throws Exception {
        long second = paidCourse(instructor, 299_000);
        for (int round = 0; round < 3; round++) {
            Session s = session(RoleCode.STUDENT);
            List<Integer> statuses = parallel(() -> statusOf(add(s, course)), () -> statusOf(add(s, second)));

            assertThat(statuses).as("round " + round).containsExactly(201, 201);
            assertThat(cartRows(s)).as("one cart per account").isOne();
            assertThat(itemRows(s)).isEqualTo(2);
        }
    }

    @Test
    void add_missingOrNullCourseId_400_wrongTypeMalformed_400() throws Exception {
        for (String body : List.of("{}", "{\"courseId\": null}")) {
            mvc.perform(post(ITEMS).header(HttpHeaders.AUTHORIZATION, student.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[*].field", contains("courseId")));
        }
        expectProblem(mvc.perform(add(student, "\"abc\"")), 400, "MALFORMED_REQUEST");
        assertThat(cartRows(student)).isZero();
    }

    @Test
    void addAndRemove_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post(ITEMS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\": " + course + "}")), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(delete(ITEMS + "/" + course)), 401, "UNAUTHENTICATED");
    }

    // ---------------------------------------------------------------------------------------------- UC-S03 remove

    @Test
    void remove_present_204_otherItemsStay_UCS03() throws Exception {
        long second = paidCourse(instructor, 299_000);
        mvc.perform(add(student, course)).andExpect(status().isCreated());
        mvc.perform(add(student, second)).andExpect(status().isCreated());

        mvc.perform(remove(student, course)).andExpect(status().isNoContent());

        assertThat(itemRows(student)).isOne();
        mvc.perform(cart(student))
                .andExpect(jsonPath("$.items[*].courseId", contains(Math.toIntExact(second))))
                .andExpect(jsonPath("$.totalAmount").value(299_000));
        // the course can be added again afterwards
        mvc.perform(add(student, course)).andExpect(status().isCreated());
    }

    @Test
    void remove_absentOrNoCart_404_identicalBody_UCS03() throws Exception {
        long other = paidCourse(instructor, 299_000);
        mvc.perform(add(student, other)).andExpect(status().isCreated());

        // has a cart, the course is not in it
        assertSameNotFound(mvc, remove(student, course), remove(student, UNKNOWN_COURSE));
        // no cart at all
        Session noCart = session(RoleCode.STUDENT);
        assertSameNotFound(mvc, remove(noCart, other), remove(student, UNKNOWN_COURSE));
        assertThat(itemRows(student)).isOne();
    }

    /** IDOR: course X sits in A's cart and in B's cart; B's delete only touches B's row, A's stays. */
    @Test
    void remove_isScopedToTheCallersCart_otherStudentsRowUntouched_NFR01() throws Exception {
        Session a = session(RoleCode.STUDENT);
        Session b = session(RoleCode.STUDENT);
        mvc.perform(add(a, course)).andExpect(status().isCreated());
        mvc.perform(add(b, course)).andExpect(status().isCreated());

        mvc.perform(remove(b, course)).andExpect(status().isNoContent());

        assertThat(itemRows(a)).as("A's item").isOne();
        assertThat(itemRows(b)).isZero();
        // B has nothing left: the same call is now 404 and A's item is still there
        assertSameNotFound(mvc, remove(b, course), remove(b, UNKNOWN_COURSE));
        assertThat(itemRows(a)).isOne();
    }

    @Test
    void remove_courseOnlyInSomeoneElsesCart_404_theirRowUntouched_NFR01() throws Exception {
        Session a = session(RoleCode.STUDENT);
        Session b = session(RoleCode.STUDENT);
        mvc.perform(add(a, course)).andExpect(status().isCreated());
        long other = paidCourse(instructor, 299_000);
        mvc.perform(add(b, other)).andExpect(status().isCreated());

        assertSameNotFound(mvc, remove(b, course), remove(b, UNKNOWN_COURSE));

        assertThat(itemRows(a)).isOne();
        assertThat(itemRows(b)).isOne();
    }

    @Test
    void remove_unavailableLine_204_UCS03() throws Exception {
        mvc.perform(add(student, course)).andExpect(status().isCreated());
        fixtures.setStatus(course, "hidden", true);

        mvc.perform(remove(student, course)).andExpect(status().isNoContent());

        assertThat(itemRows(student)).isZero();
    }

    // ------------------------------------------------------- UC-A12 / UC-A16: cart flags follow hide and unhide

    /** Real moderation endpoints: the line is flagged, then purchasable again, with no cart write in between. */
    @Test
    void cartFlagsFollowHideAndUnhide_withoutAnyCartWrite_UCA12_UCA16(CapturedOutput output) throws Exception {
        Session admin = session(RoleCode.ADMIN);
        long other = paidCourse(instructor, 299_000);
        mvc.perform(add(student, course)).andExpect(status().isCreated());
        mvc.perform(add(student, other)).andExpect(status().isCreated());
        List<Long> itemIds = jdbc.sql("SELECT id FROM cart_items WHERE cart_id = ? ORDER BY id")
                .param(cartId(student)).query(Long.class).list();

        mvc.perform(post("/api/v1/admin/courses/" + course + "/hide").header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Vi phạm bản quyền\"}"))
                .andExpect(status().isOk());

        mvc.perform(cart(student))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.purchasableCount").value(1))
                .andExpect(jsonPath("$.totalAmount").value(299_000))
                .andExpect(jsonPath("$.items[0].courseId").value(course))
                .andExpect(jsonPath("$.items[0].purchasable").value(false))
                .andExpect(jsonPath("$.items[0].unavailableReason").value("course_unavailable"));
        // the listener ran after commit: course id and number of carts, no user id
        String line = "Course hidden, cart lines now flagged unavailable: courseId=" + course + ", carts=1";
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains(line));
        assertThat(output.getAll().lines().filter(l -> l.contains(line)).toList())
                .allSatisfy(l -> assertThat(l).doesNotContain("userId"));
        assertThat(jdbc.sql("SELECT id FROM cart_items WHERE cart_id = ? ORDER BY id").param(cartId(student))
                .query(Long.class).list()).as("nothing removed or rewritten").isEqualTo(itemIds);
        // UC-A12 step 3: nobody can add the hidden course any more
        expectProblem(mvc.perform(add(session(RoleCode.STUDENT), course)), 404, "RESOURCE_NOT_FOUND");

        mvc.perform(post("/api/v1/admin/courses/" + course + "/unhide")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk());

        mvc.perform(cart(student))
                .andExpect(jsonPath("$.purchasableCount").value(2))
                .andExpect(jsonPath("$.totalAmount").value(798_000))
                .andExpect(jsonPath("$.items[0].purchasable").value(true))
                .andExpect(jsonPath("$.items[0].unavailableReason").value(nullValue()));
    }

    @Test
    void courseHiddenListener_countsCartsOnly_idempotent_noWrite(CapturedOutput output) throws Exception {
        Session b = session(RoleCode.STUDENT);
        mvc.perform(add(student, course)).andExpect(status().isCreated());
        mvc.perform(add(b, course)).andExpect(status().isCreated());
        long untouched = paidCourse(instructor, 299_000);

        listener.on(new CourseHidden(course, "slug", "Lý do"));
        listener.on(new CourseHidden(course, "slug", "Lý do"));
        listener.on(new CourseHidden(untouched, "slug", "Lý do"));

        // @Async: wait for the three log lines
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains("courseId=" + untouched + ", carts=0")
                && output.getAll().lines().filter(l -> l.contains("courseId=" + course + ", carts=2")).count() == 2);
        assertThat(itemRows(student) + itemRows(b)).isEqualTo(2);
    }

    // ----------------------------------------------------------------------------------------------------- helpers

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
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
