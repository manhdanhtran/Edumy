package com.edumy.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-07 price rule (0, or ≥ 10 000 and a multiple of 1 000), same as ck_courses_price. */
class CoursePriceValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    record Sample(@CoursePrice Long price) {
    }

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 10_000, 11_000, 15_000, 499_000, 10_000_000})
    void valid_BR07(long price) {
        assertThat(validator.validate(new Sample(price))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1_000, 1, 1_000, 9_000, 9_999, 10_500, 15_001})
    void invalid_BR07_vietnameseMessage(long price) {
        assertThat(validator.validate(new Sample(price))).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("Giá phải bằng 0 (miễn phí) hoặc từ 10.000 đ trở lên và là bội số của 1.000 đ");
    }

    @Test
    void null_isLeftToNotNull() {
        assertThat(validator.validate(new Sample(null))).isEmpty();
    }
}
