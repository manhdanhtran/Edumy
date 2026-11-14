package com.edumy.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

import java.util.List;
import java.util.Map;

import com.edumy.authoring.api.CourseAccessApi;
import com.edumy.authoring.api.CourseHidden;
import com.edumy.authoring.api.CourseQueryApi;
import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.service.CartQueryService;
import com.edumy.commerce.service.CartService;
import com.edumy.identity.api.RoleQueryApi;
import com.edumy.identity.api.UserQueryApi;
import com.edumy.learning.api.EnrollmentApi;
import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.security.RoleCode;
import com.edumy.support.CourseFixtures;
import com.edumy.support.MySqlModuleTest;
import com.edumy.support.TestUsers;
import com.edumy.support.TestUsers.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Module test of {@code commerce} (authoring, learning and identity mocked through their {@code api}): the cart rules of
 * {@code CartService} — BR-31, BR-09, BR-10, BR-32, BR-11, the refusals coming from the mocked neighbours — and the
 * reaction of {@code CourseHiddenListener} to a published {@code CourseHidden} (UC-A12 step 4). The order / payment stubs
 * and the HTTP layer are covered by {@code CartWriteIT} / {@code CommerceStubsIT}.
 */
@ApplicationModuleTest(extraIncludes = "shared")
@MySqlModuleTest
class CommerceModuleIT {

    @MockitoBean
    CourseQueryApi courseQuery;
    @MockitoBean
    CourseAccessApi courseAccess;
    @MockitoBean
    EnrollmentApi enrollments;
    @MockitoBean
    UserQueryApi userQuery;
    @MockitoBean
    RoleQueryApi roles;
    @MockitoSpyBean
    CartQueryService cartQuery;

    @Autowired
    CartService carts;
    @Autowired
    TestUsers users;
    @Autowired
    CourseFixtures fixtures;
    @Autowired
    JdbcClient jdbc;

    TestUser student;
    TestUser instructor;
    long course;

    @BeforeEach
    void setUp() {
        student = users.create(RoleCode.STUDENT);
        instructor = users.create(RoleCode.INSTRUCTOR);
        course = fixtures.draftCourse(instructor.id());
        returnCourse(true, 499_000, instructor.id());
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE student_id = ?)")
                .param(student.id()).update();
        jdbc.sql("DELETE FROM carts WHERE student_id = ?").param(student.id()).update();
        fixtures.deleteAll();
        users.deleteAll();
    }

    // ------------------------------------------------------------------------------------------ CartService

    @Test
    void addItem_publishedPaidCourse_createsCartOnceAndRejectsTheDuplicate_UCS01_BR22_BR11() {
        carts.addItem(student.id(), course);

        assertThat(cartCourseIds()).containsExactly(course);
        assertCode(() -> carts.addItem(student.id(), course), ErrorCode.CART_ITEM_DUPLICATE);
        assertThat(cartCourseIds()).containsExactly(course);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM carts WHERE student_id = ?").param(student.id())
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void addItem_unpublishedOrUnknownCourse_404SameAsUnknown_BR31() {
        returnCourse(false, 499_000, instructor.id());
        assertCode(() -> carts.addItem(student.id(), course), ErrorCode.RESOURCE_NOT_FOUND);

        given(courseQuery.findSummaries(anyCollection())).willReturn(Map.of());
        assertCode(() -> carts.addItem(student.id(), course), ErrorCode.RESOURCE_NOT_FOUND);

        assertNoCart();
    }

    @Test
    void addItem_freeCourse_rejected_BR09() {
        returnCourse(true, 0, instructor.id());

        assertCode(() -> carts.addItem(student.id(), course), ErrorCode.CART_COURSE_FREE);

        assertNoCart();
    }

    @Test
    void addItem_alreadyOwned_rejectedByEnrollmentApi_BR10() {
        given(enrollments.owns(student.id(), course)).willReturn(true);

        assertCode(() -> carts.addItem(student.id(), course), ErrorCode.CART_COURSE_ALREADY_OWNED);

        assertNoCart();
    }

    @Test
    void addItem_ownCourse_mainInstructorOrAcceptedCoInstructor_rejected_BR32() {
        returnCourse(true, 499_000, student.id()); // the caller is the main instructor (any role state)
        assertCode(() -> carts.addItem(student.id(), course), ErrorCode.CART_OWN_COURSE);

        returnCourse(true, 499_000, instructor.id());
        given(courseAccess.isCourseInstructor(student.id(), course)).willReturn(true); // accepted co-instructor
        assertCode(() -> carts.addItem(student.id(), course), ErrorCode.CART_OWN_COURSE);

        assertNoCart();
    }

    @Test
    void removeItem_inCart_removed_otherwise404_UCS03() {
        carts.addItem(student.id(), course);

        carts.removeItem(student.id(), course);

        assertThat(cartCourseIds()).isEmpty();
        assertCode(() -> carts.removeItem(student.id(), course), ErrorCode.RESOURCE_NOT_FOUND);
        TestUser noCart = users.create(RoleCode.STUDENT);
        assertCode(() -> carts.removeItem(noCart.id(), course), ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------------------------------------------ CourseHiddenListener

    @Test
    void courseHidden_published_listenerRunsAfterCommitAndWritesNothing_UCA12(Scenario scenario) {
        carts.addItem(student.id(), course);

        scenario.publish(new CourseHidden(course, "khoa-hoc-an", "Vi phạm"))
                .andWaitForStateChange(this::countCartsHoldingCalls, calls -> calls > 0)
                .andVerify(calls -> {
                    assertThat(cartCourseIds()).containsExactly(course); // derived flag only, nothing written
                    assertThat(mockingDetails(cartQuery).getInvocations().stream()
                            .filter(i -> i.getMethod().getName().equals("countCartsHolding"))
                            .map(i -> i.getArguments()[0])).containsExactly(course);
                });
    }

    @Test
    void courseHidden_listenerFailureIsSwallowed_nothingPropagatesNothingWritten_UCA12(Scenario scenario) {
        carts.addItem(student.id(), course);
        doThrow(new IllegalStateException("simulated read failure")).when(cartQuery).countCartsHolding(course);

        scenario.publish(new CourseHidden(course, "khoa-hoc-an", "Vi phạm"))
                .andWaitForStateChange(this::countCartsHoldingCalls, calls -> calls > 0)
                .andVerify(calls -> assertThat(cartCourseIds()).containsExactly(course));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void returnCourse(boolean published, long price, Long mainInstructorId) {
        given(courseQuery.findSummaries(anyCollection())).willReturn(Map.of(course,
                new CourseSummary(course, "Khóa học kiểm thử", "khoa-hoc-kiem-thu", null, price, published,
                        mainInstructorId)));
    }

    private long countCartsHoldingCalls() {
        return mockingDetails(cartQuery).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("countCartsHolding")).count();
    }

    private List<Long> cartCourseIds() {
        return jdbc.sql("""
                SELECT ci.course_id FROM cart_items ci JOIN carts c ON c.id = ci.cart_id
                WHERE c.student_id = ? ORDER BY ci.course_id""").param(student.id()).query(Long.class).list();
    }

    private void assertNoCart() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM carts WHERE student_id = ?").param(student.id())
                .query(Long.class).single()).isZero();
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}
