package com.edumy.commerce.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.edumy.authoring.api.CourseSummary;
import com.edumy.commerce.dto.response.CartResponse;
import com.edumy.identity.api.UserSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Reasons that the seed cannot show (no cart there holds an owned / own / now-free course): UC-S02, D-26. */
class CartMapperTest {

    private static final Long STUDENT = 6L;
    private final CartMapper mapper = Mappers.getMapper(CartMapper.class);
    private final UserSummary teacher = new UserSummary(2L, "GV", "gv@example.com", "active");

    private CourseSummary course(long price, boolean published, Long instructor) {
        return new CourseSummary(1L, "T", "t", null, price, published, instructor);
    }

    private CartResponse.Item item(CourseSummary course, boolean owned) {
        return mapper.toItem(line(), course, teacher, owned, STUDENT);
    }

    private com.edumy.commerce.entity.CartItem line() {
        com.edumy.commerce.entity.CartItem item = org.mockito.Mockito.mock(com.edumy.commerce.entity.CartItem.class);
        org.mockito.Mockito.when(item.getCourseId()).thenReturn(1L);
        org.mockito.Mockito.when(item.getAddedAt()).thenReturn(Instant.parse("2026-10-06T08:00:00Z"));
        return item;
    }

    @Test
    void purchasable() {
        var line = item(course(500_000, true, 2L), false);
        assertThat(line.purchasable()).isTrue();
        assertThat(line.unavailableReason()).isNull();
        assertThat(line.isFree()).isFalse();
        assertThat(line.instructor().fullName()).isEqualTo("GV");
    }

    @Test
    void notPublished_unavailable_evenWhenOwned() {
        assertThat(item(course(500_000, false, 2L), true).unavailableReason()).isEqualTo("course_unavailable");
    }

    @Test
    void owned() {
        assertThat(item(course(500_000, true, 2L), true).unavailableReason()).isEqualTo("already_owned");
    }

    @Test
    void ownCourse() {
        assertThat(item(course(500_000, true, STUDENT), false).unavailableReason()).isEqualTo("own_course");
    }

    @Test
    void nowFree() {
        var line = item(course(0, true, 2L), false);
        assertThat(line.unavailableReason()).isEqualTo("now_free");
        assertThat(line.isFree()).isTrue();
        assertThat(line.purchasable()).isFalse();
    }

    @Test
    void total_countsPurchasableLinesOnly() {
        var ok = item(course(500_000, true, 2L), false);
        var owned = item(course(300_000, true, 2L), true);
        var response = mapper.toResponse(List.of(ok, owned));
        assertThat(response.itemCount()).isEqualTo(2);
        assertThat(response.purchasableCount()).isEqualTo(1);
        assertThat(response.totalAmount()).isEqualTo(500_000);
    }
}
