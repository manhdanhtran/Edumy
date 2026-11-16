package com.edumy.commerce.controller;

import static com.edumy.support.ProblemAssertions.expectProblem;
import static com.edumy.support.ProblemAssertions.withoutTraceAndInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import com.edumy.commerce.integration.FakeQrPaymentGateway;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CommerceFixtures;
import com.edumy.support.CommerceFixtures.FixtureOrder;
import com.edumy.support.CourseFixtures;
import com.edumy.support.JwtTestTokens;
import com.edumy.support.JwtTestTokens.Session;
import com.edumy.support.MySqlSpringBootTest;
import com.edumy.support.TestUsers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * UC-S04 T1 create order and UC-S16 cancel through HTTP on MySQL (P11-b, ADR-011): D-02, D-26 re-validation with
 * BR-32 by relation (also after the instructor role was revoked), BR-12 / D-14 locked prices, BR-27 total, AC-18 empty
 * cart, AC-21 replacement after the lookup (and the lookup that finds the old order paid: committed, then 409), Q1
 * identical order reused (E3, also concurrently), Q2 step-3 confirmation, provider down (503, nothing changed), UC-S16
 * E1 / E2, cart kept (post-conditions), NFR-01 identical 404.
 */
@MySqlSpringBootTest
class OrderWriteIT {

    static final String ORDERS = "/api/v1/orders";
    static final String CODE = "ORD-\\d{8}-[0-9A-HJKMNP-TV-Z]{8}";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestUsers users;
    @Autowired
    JwtTestTokens tokens;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FakeQrPaymentGateway gateway;
    @Autowired
    com.edumy.support.PaymentTestHooks hooks;
    @org.springframework.beans.factory.annotation.Value("${edumy.payment.webhook-secret}")
    String secret;

    int webhook(String ref, String orderCode, long amount) throws Exception {
        String body = "{\"transactionRef\":\"" + ref + "\",\"orderCode\":\"" + orderCode + "\",\"amount\":" + amount
                + ",\"status\":\"success\"}";
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mvc.perform(post("/api/v1/webhooks/payments/fake").contentType(MediaType.APPLICATION_JSON)
                        .content(body).header("X-Signature",
                                java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))))
                .andReturn().getResponse().getStatus();
    }

    CourseFixtures courses;
    CommerceFixtures commerce;
    Session student;
    Session instructor;
    long courseA;
    long courseB;

    @BeforeEach
    void setUp() {
        courses = new CourseFixtures(jdbc);
        commerce = new CommerceFixtures(jdbc);
        instructor = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR);
        student = tokens.newUserSession(RoleCode.STUDENT);
        commerce.track(student.userId());
        courseA = publishedCourse(instructor.userId(), 300_000);
        courseB = publishedCourse(instructor.userId(), 200_000);
    }

    @AfterEach
    void cleanUp() {
        commerce.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    long publishedCourse(long mainInstructorId, long price) {
        long id = courses.draftCourse(mainInstructorId);
        courses.setStatus(id, "published", false);
        setPrice(id, price);
        return id;
    }

    void setPrice(long courseId, long price) {
        jdbc.sql("UPDATE courses SET price = ? WHERE id = ?").params(price, courseId).update();
    }

    MockHttpServletRequestBuilder create(Session who, String body) {
        MockHttpServletRequestBuilder request = post(ORDERS).header(HttpHeaders.AUTHORIZATION, who.bearer());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    ResultActions create(String body) throws Exception {
        return mvc.perform(create(student, body));
    }

    ResultActions cancel(Session who, String code) throws Exception {
        return mvc.perform(post(ORDERS + "/" + code + "/cancel").header(HttpHeaders.AUTHORIZATION, who.bearer()));
    }

    static String codeOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.orderCode");
    }

    long orders(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM orders WHERE student_id = ? AND status = ?")
                .params(student.userId(), status).query(Long.class).single();
    }

    long allOrders() {
        return jdbc.sql("SELECT COUNT(*) FROM orders WHERE student_id = ?").param(student.userId())
                .query(Long.class).single();
    }

    /** A pending order of the student with a pending attempt {@code ref}. */
    FixtureOrder pendingOrder(String ref, long... courseIds) {
        CommerceFixtures.OrderBuilder builder = commerce.order(student.userId());
        for (long id : courseIds) {
            builder.line(id, jdbc.sql("SELECT price FROM courses WHERE id = ?").param(id).query(Long.class).single());
        }
        FixtureOrder order = builder.insert();
        if (ref != null) {
            commerce.pendingPayment(order, ref);
        }
        return order;
    }

    // ---- T1 create order (UC-S04)

    @Test
    void create_newPendingOrder_201_linesAtTheCurrentPrice_totalIsTheSum_cartKept_BR12_BR27() throws Exception {
        commerce.cart(student.userId(), courseA, courseB);

        create(null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderCode", matchesPattern(CODE)))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.totalAmount").value(500_000))
                .andExpect(jsonPath("$.items[*].courseId", contains((int) courseA, (int) courseB)))
                .andExpect(jsonPath("$.items[*].unitPrice", contains(300_000, 200_000)))
                .andExpect(jsonPath("$.payments", empty()))
                .andExpect(jsonPath("$.cancelReason").value(nullValue()));

        assertThat(orders("pending")).isEqualTo(1);
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseA, courseB); // not removed yet
    }

    @Test
    void create_paymentMethodQrAccepted_anyOtherOr_invalidExpectations_400() throws Exception {
        commerce.cart(student.userId(), courseA);
        expectProblem(create("{\"paymentMethod\":\"card\"}"), 400, "VALIDATION_FAILED");
        expectProblem(create("{\"expectedTotalAmount\":-1}"), 400, "VALIDATION_FAILED");
        expectProblem(create("{\"expectedCourseIds\":[0]}"), 400, "VALIDATION_FAILED");
        assertThat(allOrders()).isZero();
        create("{\"paymentMethod\":\"qr\"}").andExpect(status().isCreated());
    }

    @Test
    void create_unverifiedEmail_403_noOrder_D02() throws Exception {
        commerce.cart(student.userId(), courseA);
        users.markEmailUnverified(student.userId());
        expectProblem(create(null), 403, "EMAIL_NOT_VERIFIED");
        assertThat(allOrders()).isZero();
    }

    @Test
    void create_noCart_emptyCart_onlyInvalidLines_422CartEmpty_noOrder_AC18() throws Exception {
        expectProblem(create(null), 422, "CART_EMPTY"); // no cart at all
        commerce.cart(student.userId());
        expectProblem(create(null), 422, "CART_EMPTY"); // a cart without lines
        long hidden = publishedCourse(instructor.userId(), 100_000);
        courses.setStatus(hidden, "hidden", false);
        commerce.cart(student.userId(), hidden);
        expectProblem(create(null), 422, "CART_EMPTY"); // only an unavailable line
        assertThat(allOrders()).isZero();
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(hidden); // never removed by T1
    }

    @Test
    void create_invalidLinesLeftOut_hiddenOwnedFreeAndOwnCourses_BR32ByRelationAfterRoleRevocation_D26()
            throws Exception {
        Session former = tokens.newUserSession(RoleCode.STUDENT, RoleCode.INSTRUCTOR); // a student who built courses
        commerce.track(former.userId());
        long hidden = publishedCourse(instructor.userId(), 100_000);
        courses.setStatus(hidden, "hidden", false);
        long owned = publishedCourse(instructor.userId(), 100_000);
        courses.purchaseEnrollment(former.userId(), owned);
        long nowFree = publishedCourse(instructor.userId(), 0);
        long ownMain = publishedCourse(former.userId(), 100_000);
        long ownCo = publishedCourse(instructor.userId(), 100_000);
        courses.coInstructor(ownCo, former.userId(), instructor.userId(), "accepted");
        users.revokeRole(former.userId(), RoleCode.INSTRUCTOR); // BR-32 is a relation, not a role
        commerce.cart(former.userId(), hidden, courseA, owned, nowFree, ownMain, ownCo);

        mvc.perform(create(former, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[*].courseId", contains((int) courseA)))
                .andExpect(jsonPath("$.totalAmount").value(300_000));
    }

    @Test
    void create_priceChangedAfterwards_theOrderKeepsTheLockedPrice_D14() throws Exception {
        commerce.cart(student.userId(), courseA);
        String code = codeOf(create(null).andExpect(status().isCreated()));

        setPrice(courseA, 999_000);

        assertThat(commerce.orderColumn(code, "total_amount", Long.class)).isEqualTo(300_000);
        assertThat(jdbc.sql("SELECT oi.unit_price FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.order_code = ?").param(code).query(Long.class).single()).isEqualTo(300_000);
    }

    @Test
    void create_again_sameCart_200_theSamePendingOrder_noLookup_Q1_E3() throws Exception {
        commerce.cart(student.userId(), courseA, courseB);
        String first = codeOf(create(null).andExpect(status().isCreated()));

        String second = codeOf(create(null).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(allOrders()).isEqualTo(1);
        assertThat(gateway.totalQueries()).isZero();
    }

    @Test
    void create_cartChanged_oldOrderLookedUpThenReplaced_beforeTheNewOneIsCreated_AC21() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = pendingOrder(ref, courseA);
        commerce.cart(student.userId(), courseA, courseB);

        String code = codeOf(create(null).andExpect(status().isCreated()));

        assertThat(code).isNotEqualTo(old.code());
        assertThat(commerce.orderColumn(old.code(), "status", String.class)).isEqualTo("cancelled");
        assertThat(commerce.orderColumn(old.code(), "cancel_reason", String.class)).isEqualTo("replaced");
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("expired");
        assertThat(gateway.queryCount(ref)).isEqualTo(1);
        Timestamp cancelledAt = commerce.orderColumn(old.code(), "cancelled_at", Timestamp.class);
        Timestamp createdAt = commerce.orderColumn(code, "created_at", Timestamp.class);
        assertThat(cancelledAt).isBeforeOrEqualTo(createdAt);
        assertThat(orders("pending")).isEqualTo(1); // never two pending orders (BR-29)
    }

    @Test
    void create_samePricesButAPriceChanged_replaces_Q1() throws Exception {
        FixtureOrder old = pendingOrder(null, courseA);
        commerce.cart(student.userId(), courseA);
        setPrice(courseA, 350_000);

        create(null).andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(350_000));

        assertThat(commerce.orderColumn(old.code(), "cancel_reason", String.class)).isEqualTo("replaced");
    }

    @Test
    void create_oldOrderFoundPaidByTheLookup_recordedAndCommitted_then409_noNewOrder_UCS04Step2() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = pendingOrder(ref, courseA);
        commerce.cart(student.userId(), courseA, courseB);
        gateway.simulatePaid(ref, 300_000);

        expectProblem(create(null), 409, "ORDER_ALREADY_PAID");

        assertThat(commerce.orderColumn(old.code(), "status", String.class)).isEqualTo("paid"); // committed
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("success");
        assertThat(commerce.enrollments(student.userId())).containsExactlyEntriesOf(Map.of(courseA, "purchase"));
        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseB); // back to the cart
        assertThat(allOrders()).isEqualTo(1);
    }

    @Test
    void create_providerDownForTheOldOrder_503_nothingChanged() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = pendingOrder(ref, courseA);
        commerce.cart(student.userId(), courseA, courseB);
        gateway.setUnavailable(true);

        expectProblem(create(null), 503, "PAYMENT_PROVIDER_UNAVAILABLE");

        assertThat(commerce.orderColumn(old.code(), "status", String.class)).isEqualTo("pending");
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("pending");
        assertThat(allOrders()).isEqualTo(1);
    }

    @Test
    void create_expectationsNoLongerTrue_409OrderCartChanged_oldOrderUntouched_Q2() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = pendingOrder(ref, courseA);
        long hidden = publishedCourse(instructor.userId(), 100_000);
        commerce.cart(student.userId(), courseA, courseB, hidden);
        courses.setStatus(hidden, "hidden", false);

        MvcResult total = expectProblem(create("{\"expectedTotalAmount\":600000}"), 409, "ORDER_CART_CHANGED");
        assertThat(JsonPath.<List<String>>read(total.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.errors[*].field")).containsExactly("expectedTotalAmount");
        MvcResult lines = expectProblem(create("{\"expectedCourseIds\":[" + courseA + "," + courseB + "," + hidden
                + "]}"), 409, "ORDER_CART_CHANGED");
        assertThat(JsonPath.<List<String>>read(lines.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.errors[*].message")).singleElement().asString()
                .contains("#" + hidden).contains("không còn được bán");

        assertThat(commerce.orderColumn(old.code(), "status", String.class)).isEqualTo("pending");
        assertThat(gateway.totalQueries()).isZero();
        create("{\"expectedTotalAmount\":500000,\"expectedCourseIds\":[" + courseB + "," + courseA + "]}")
                .andExpect(status().isCreated());
    }

    /**
     * UC-S04 E3 / AC-21, forced interleaving (review P11-R-03): the first request stops inside the lookup of the old
     * order's attempt while it holds the student's cart; the second must wait for that lock, then finds the order the
     * first one created and returns it (200) — one replacement, one lookup, one pending order.
     */
    @Test
    void create_secondRequestWaitsForTheCart_thenGetsTheOrderTheFirstCreated_201And200_UCS04E3_AC21()
            throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = pendingOrder(ref, courseA);
        commerce.cart(student.userId(), courseA, courseB);

        com.edumy.support.Interleave.Results<MvcResult, MvcResult> results =
                com.edumy.support.Interleave.secondWaitsForTheFirst(jdbc, gateway.holdNextQuery(),
                        () -> mvc.perform(create(student, null)).andReturn(),
                        () -> mvc.perform(create(student, null)).andReturn());

        assertThat(results.first().getResponse().getStatus()).isEqualTo(201);
        assertThat(results.second().getResponse().getStatus()).isEqualTo(200);
        String created = JsonPath.read(results.first().getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.orderCode");
        assertThat((String) JsonPath.read(results.second().getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.orderCode")).isEqualTo(created);
        assertThat(commerce.orderColumn(old.code(), "cancel_reason", String.class)).isEqualTo("replaced");
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("expired");
        assertThat(gateway.queryCount(ref)).isEqualTo(1);
        assertThat(orders("pending")).isEqualTo(1);
        assertThat(allOrders()).isEqualTo(2);
    }

    /** Same race with a webhook paying the old order while T1 holds it: T1 sees it paid (409), the webhook ignores. */
    @Test
    void create_webhookForTheOldOrderWaitsForT1_t1FindsItPaid409_oneEffect() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder old = pendingOrder(ref, courseA);
        commerce.cart(student.userId(), courseA, courseB);
        gateway.simulatePaid(ref, 300_000);

        com.edumy.support.Interleave.Results<Integer, Integer> results =
                com.edumy.support.Interleave.secondWaitsForTheFirst(jdbc, gateway.holdNextQuery(),
                        () -> mvc.perform(create(student, null)).andReturn().getResponse().getStatus(),
                        () -> webhook(ref, old.code(), 300_000));

        assertThat(results.first()).isEqualTo(409); // ORDER_ALREADY_PAID, recorded by T1's lookup
        assertThat(results.second()).isEqualTo(200); // the webhook found it applied
        assertThat(commerce.orderColumn(old.code(), "status", String.class)).isEqualTo("paid");
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA);
        assertThat(hooks.published()).hasSize(1);
        assertThat(allOrders()).isEqualTo(1);
    }
    // ---- UC-S16 cancel

    @Test
    void cancel_orderWithoutAttempt_200_cancelledByTheUser_cartKept_UCS16() throws Exception {
        FixtureOrder order = pendingOrder(null, courseA);
        commerce.cart(student.userId(), courseA);

        cancel(student, order.code())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCode").value(order.code()))
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.cancelReason").value("user"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        assertThat(commerce.cartCourseIds(student.userId())).containsExactly(courseA); // step 3
        assertThat(gateway.totalQueries()).isZero();
    }

    @Test
    void cancel_pendingAttemptLookedUpNotPaid_attemptExpired_orderCancelled_UCS16() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrder(ref, courseA);

        cancel(student, order.code())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.payments[0].status").value("expired"));

        assertThat(gateway.queryCount(ref)).isEqualTo(1);
    }

    @Test
    void cancel_lookupFindsItPaid_notCancelled_paymentRecorded_409_UCS16E1() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrder(ref, courseA);
        gateway.simulatePaid(ref, 300_000);

        expectProblem(cancel(student, order.code()), 409, "ORDER_ALREADY_PAID");

        assertThat(commerce.orderColumn(order.code(), "status", String.class)).isEqualTo("paid");
        assertThat(commerce.orderColumn(order.code(), "cancelled_at", Timestamp.class)).isNull();
        assertThat(commerce.enrollments(student.userId())).containsOnlyKeys(courseA);
    }

    @Test
    void cancel_providerDown_503_orderStaysPending_UCS16E2() throws Exception {
        String ref = CommerceFixtures.newReference();
        FixtureOrder order = pendingOrder(ref, courseA);
        gateway.setUnavailable(true);

        expectProblem(cancel(student, order.code()), 503, "PAYMENT_PROVIDER_UNAVAILABLE");

        assertThat(commerce.orderColumn(order.code(), "status", String.class)).isEqualTo("pending");
        assertThat(commerce.paymentColumn(ref, "status", String.class)).isEqualTo("pending");
    }

    @Test
    void cancel_notPending_409_andEmptyJsonBodyAccepted() throws Exception {
        FixtureOrder paid = commerce.order(student.userId()).line(courseA, 300_000).paid().insert();
        expectProblem(cancel(student, paid.code()), 409, "DATA_CONFLICT");

        FixtureOrder pending = pendingOrder(null, courseB);
        mvc.perform(post(ORDERS + "/" + pending.code() + "/cancel").header(HttpHeaders.AUTHORIZATION, student.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        expectProblem(cancel(student, pending.code()), 409, "DATA_CONFLICT"); // already cancelled
    }

    @Test
    void cancel_anotherStudentsOrder_404IdenticalToAnUnknownCode_untouched_NFR01() throws Exception {
        FixtureOrder order = pendingOrder(null, courseA);
        Session other = tokens.newUserSession(RoleCode.STUDENT);

        MvcResult foreign = expectProblem(cancel(other, order.code()), 404, "RESOURCE_NOT_FOUND");
        MvcResult unknown = expectProblem(cancel(other, "ORD-20260101-UNKNOWN0"), 404, "RESOURCE_NOT_FOUND");

        assertThat(withoutTraceAndInstance(foreign)).isEqualTo(withoutTraceAndInstance(unknown));
        assertThat(commerce.orderColumn(order.code(), "status", String.class)).isEqualTo("pending");
    }

    @Test
    void createAndCancel_withoutToken_401() throws Exception {
        expectProblem(mvc.perform(post(ORDERS)), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(post(ORDERS + "/ORD-20260101-UNKNOWN0/cancel")), 401, "UNAUTHENTICATED");
    }
}
