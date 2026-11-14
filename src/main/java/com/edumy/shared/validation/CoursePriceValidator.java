package com.edumy.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link CoursePrice} (BR-07); the same rule as {@code ck_courses_price} and {@code Course.isValidPrice}. */
public class CoursePriceValidator implements ConstraintValidator<CoursePrice, Long> {

    static final long MIN_PAID_PRICE = 10_000L;
    static final long PRICE_STEP = 1_000L;

    @Override
    public boolean isValid(Long value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull reports it where the price is required
        }
        return value == 0 || (value >= MIN_PAID_PRICE && value % PRICE_STEP == 0);
    }
}
