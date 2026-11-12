package com.edumy.commerce.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.edumy.shared.error.BusinessException;
import com.edumy.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

class CartTest {

    static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    static final Long STUDENT = 8L;

    @Test
    void create_emptyCartOfStudent_BR22() {
        Cart cart = Cart.create(STUDENT, NOW);

        assertThat(cart.getStudentId()).isEqualTo(STUDENT);
        assertThat(cart.getCreatedAt()).isEqualTo(NOW);
        assertThat(cart.getItems()).isEmpty();
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.getId()).isNull();
    }

    @Test
    void create_nullArguments_rejected() {
        assertThatThrownBy(() -> Cart.create(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Cart.create(STUDENT, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void addItem_newCourse_added_UCS01() {
        Cart cart = Cart.create(STUDENT, NOW);

        CartItem first = cart.addItem(7L, NOW.plusSeconds(1));
        CartItem second = cart.addItem(9L, NOW.plusSeconds(2));

        assertThat(cart.getItems()).containsExactly(first, second);
        assertThat(first.getCart()).isSameAs(cart);
        assertThat(first.getCourseId()).isEqualTo(7L);
        assertThat(first.getAddedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(cart.courseIds()).containsExactly(7L, 9L);
        assertThat(cart.contains(9L)).isTrue();
        assertThat(cart.findItem(9L)).containsSame(second);
        assertThat(cart.findItem(1L)).isEmpty();
        assertThat(cart.isEmpty()).isFalse();
    }

    @Test
    void addItem_courseAlreadyInCart_rejected_BR11() {
        Cart cart = Cart.create(STUDENT, NOW);
        cart.addItem(7L, NOW);

        assertThatThrownBy(() -> cart.addItem(7L, NOW.plusSeconds(5)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CART_ITEM_DUPLICATE));
        assertThat(cart.getItems()).hasSize(1);
    }

    @Test
    void addItem_nullArguments_rejected() {
        Cart cart = Cart.create(STUDENT, NOW);
        assertThatThrownBy(() -> cart.addItem(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cart.addItem(7L, null)).isInstanceOf(NullPointerException.class);
        assertThat(cart.getItems()).isEmpty();
    }

    @Test
    void removeItem_presentCourse_removed_UCS03() {
        Cart cart = Cart.create(STUDENT, NOW);
        cart.addItem(7L, NOW);
        CartItem kept = cart.addItem(9L, NOW);

        assertThat(cart.removeItem(7L)).isTrue();

        assertThat(cart.getItems()).containsExactly(kept);
    }

    @Test
    void removeItem_absentCourse_false_nothingChanged() {
        Cart cart = Cart.create(STUDENT, NOW);
        cart.addItem(7L, NOW);

        assertThat(cart.removeItem(9L)).isFalse();

        assertThat(cart.courseIds()).containsExactly(7L);
    }

    @Test
    void removeItem_thenAddAgain_allowed() {
        Cart cart = Cart.create(STUDENT, NOW);
        cart.addItem(7L, NOW);
        cart.removeItem(7L);

        cart.addItem(7L, NOW.plusSeconds(1));

        assertThat(cart.courseIds()).containsExactly(7L);
    }

    @Test
    void removeCourses_boughtCoursesOnly_othersKept_UCS05() {
        Cart cart = Cart.create(STUDENT, NOW);
        cart.addItem(1L, NOW);
        cart.addItem(2L, NOW);
        cart.addItem(3L, NOW);

        int removed = cart.removeCourses(List.of(1L, 3L, 99L));

        assertThat(removed).isEqualTo(2);
        assertThat(cart.courseIds()).containsExactly(2L);
        assertThat(cart.removeCourses(Set.of())).isZero();
    }

    @Test
    void getItems_readOnlyView() {
        Cart cart = Cart.create(STUDENT, NOW);
        cart.addItem(7L, NOW);

        assertThatThrownBy(() -> cart.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equals_byIdOnly() {
        Cart a = Cart.create(STUDENT, NOW);
        Cart b = Cart.create(STUDENT, NOW);

        assertThat(a).isEqualTo(a).isNotEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("studentId=8");
    }
}
