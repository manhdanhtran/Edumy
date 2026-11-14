package com.edumy.shared.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Course price rule BR-07 (docs/ai/05 §5.1, {@code ck_courses_price}): 0 (free) or at least 10 000 VND and a multiple
 * of 1 000. {@code null} is valid here: combine with {@code @NotNull} where the price is required. Text:
 * {@code edumy.course.price.invalid} in ValidationMessages.properties.
 */
@Documented
@Constraint(validatedBy = CoursePriceValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CoursePrice {

    String message() default "{edumy.course.price.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
