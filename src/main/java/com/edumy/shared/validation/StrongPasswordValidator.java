package com.edumy.shared.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Checks {@link StrongPassword}. Never puts the value in a message (NFR-08). */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    static final int MIN_CHARS = 8;
    static final int MAX_CHARS = 72;
    static final int MAX_BYTES = 72;
    static final String TOO_MANY_BYTES = "{edumy.password.too-many-bytes}";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // @NotBlank reports it
        }
        int chars = value.codePointCount(0, value.length());
        boolean letter = value.codePoints().anyMatch(Character::isLetter);
        boolean digit = value.codePoints().anyMatch(Character::isDigit);
        if (chars < MIN_CHARS || chars > MAX_CHARS || !letter || !digit) {
            return false; // default message: the whole policy
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(TOO_MANY_BYTES).addConstraintViolation();
            return false;
        }
        return true;
    }
}
