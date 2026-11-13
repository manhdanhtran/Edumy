package com.edumy.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * NFR-02 / docs/ai/04 §4.2 password policy with the real Hibernate Validator and ValidationMessages.properties
 * (Vietnamese texts).
 */
class StrongPasswordValidatorTest {

    static final String POLICY = "Mật khẩu phải dài 8–72 ký tự, có ít nhất một chữ cái và một chữ số";
    static final String TOO_MANY_BYTES =
            "Mật khẩu quá dài: tối đa 72 byte (chữ có dấu chiếm 2–3 byte), hãy dùng mật khẩu ngắn hơn";

    record Form(@StrongPassword String password) {
    }

    static ValidatorFactory factory;
    static Validator validator;

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
    @ValueSource(strings = {"Passw0rd", "abcdefg1", "12345678a", "mậtkhẩu1", "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"})
    void valid(String password) {
        assertThat(validator.validate(new Form(password))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pass0rd", "abcdefgh", "12345678", "        ", "!!!!!!!!1"})
    void tooShortOrMissingLetterOrDigit_policyMessage(String password) {
        assertThat(messages(password)).containsExactly(POLICY);
    }

    @Test
    void over72Characters_policyMessage() {
        assertThat(messages("a1".repeat(36) + "b")).containsExactly(POLICY);
        assertThat(validator.validate(new Form("a1".repeat(36)))).isEmpty(); // exactly 72
    }

    @Test
    void within72CharactersButOver72Utf8Bytes_bytesMessage() {
        String vietnamese = "ậ".repeat(30) + "a1"; // 32 characters, 92 bytes
        assertThat(vietnamese.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(72);

        Set<ConstraintViolation<Form>> violations = validator.validate(new Form(vietnamese));

        assertThat(violations).extracting(ConstraintViolation::getMessage).containsExactly(TOO_MANY_BYTES);
    }

    @Test
    void nullAndEmpty_leftToNotBlank() {
        assertThat(validator.validate(new Form(null))).isEmpty();
        assertThat(validator.validate(new Form(""))).isEmpty();
    }

    private static java.util.List<String> messages(String password) {
        return validator.validate(new Form(password)).stream().map(ConstraintViolation::getMessage).toList();
    }
}
