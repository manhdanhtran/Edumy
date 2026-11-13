package com.edumy.shared.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Password policy of NFR-02 / docs/ai/04 §4.2 for every <b>new</b> password (register, reset, change): 8–72
 * characters, at least one letter and one digit, and at most {@value StrongPasswordValidator#MAX_BYTES} UTF-8 bytes
 * (BCrypt only uses the first 72 bytes and Spring's encoder rejects longer input; Vietnamese letters take 2–3 bytes).
 * {@code null} and {@code ""} are valid here: combine with {@code @NotBlank}. Texts: ValidationMessages.properties.
 * Not for the login or "current password" fields, which may hold a password set under an older policy.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "{edumy.password.policy}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
